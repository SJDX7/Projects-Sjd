package com.example.watchtogetherbysjd

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// An SdpObserver that does nothing by default, so we only override what we need
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

/**
 * Voice chat between phones in the same room.
 * Phones connect directly to each other (WebRTC). Firebase is only used so
 * the phones can find each other and exchange connection details.
 */
class VoiceEngine(
    private val context: Context,
    private val db: FirebaseDatabase
) {
    companion object {
        private var webrtcInitialized = false
    }

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // Free public relay (helps on mobile data). It may not always be available.
        PeerConnection.IceServer.builder(
            listOf(
                "turn:openrelay.metered.ca:80",
                "turn:openrelay.metered.ca:443",
                "turn:openrelay.metered.ca:443?transport=tcp"
            )
        ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val connectedPeers = ConcurrentHashMap<String, Boolean>()
    private val knownPeers = ConcurrentHashMap<String, Boolean>()
    private val remoteReady = ConcurrentHashMap<String, Boolean>()
    private val pendingIce = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private var myId = ""
    private var roomCode = ""
    @Volatile
    private var active = false
    private var remoteVolume = 1.0

    private var voiceRef: DatabaseReference? = null
    private var peersListener: ChildEventListener? = null
    private var signalsRef: DatabaseReference? = null
    private var signalsListener: ChildEventListener? = null

    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    fun isActive(): Boolean = active

    // ---------- Audio routing (earphones or speaker) ----------

    private fun isHeadsetType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) return true
        return false
    }

    // Plug or unplug earphones during a call: switch the sound automatically
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (active) applyRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (active) applyRoute()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRoute() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headsetPresent = outputs.any { isHeadsetType(it.type) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = am.availableCommunicationDevices
                val target = if (headsetPresent) {
                    available.firstOrNull { isHeadsetType(it.type) }
                } else {
                    available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                if (target != null) {
                    am.setCommunicationDevice(target)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                am.isSpeakerphoneOn = !headsetPresent
                val bluetooth = outputs.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (bluetooth) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- Start / stop ----------

    fun start(room: String) {
        if (active) return
        active = true
        roomCode = room
        myId = UUID.randomUUID().toString().take(8)

        // WebRTC setup
        if (!webrtcInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            webrtcInitialized = true
        }
        val adm = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        audioDeviceModule = adm
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        factory = f
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("audio-$myId", audioSource)
        audioTrack?.setEnabled(true)

        // Send sound to earphones if connected, otherwise to the loudspeaker
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousMode = am.mode
        previousSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute()
        am.registerAudioDeviceCallback(deviceCallback, null)

        // Firebase signaling
        val voice = db.getReference("rooms").child(roomCode).child("voice")
        voiceRef = voice

        val mySignals = voice.child("signals").child(myId)
        signalsRef = mySignals
        mySignals.onDisconnect().removeValue()
        val sListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleSignal(snapshot)
                snapshot.ref.removeValue()
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        signalsListener = sListener
        mySignals.addChildEventListener(sListener)

        val myPresence = voice.child("peers").child(myId)
        myPresence.setValue(true)
        myPresence.onDisconnect().removeValue()

        val pListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val other = snapshot.key ?: return
                if (other == myId) return
                knownPeers[other] = true
                // The phone with the bigger id starts the connection
                if (myId > other) {
                    createPeer(other, true)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val other = snapshot.key ?: return
                knownPeers.remove(other)
                closePeer(other)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        peersListener = pListener
        voice.child("peers").addChildEventListener(pListener)
    }

    fun stop() {
        if (!active) return
        active = false

        try {
            peersListener?.let { voiceRef?.child("peers")?.removeEventListener(it) }
            signalsListener?.let { signalsRef?.removeEventListener(it) }
            voiceRef?.child("peers")?.child(myId)?.removeValue()
            signalsRef?.removeValue()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        peersListener = null
        signalsListener = null

        for (id in peers.keys.toList()) {
            closePeer(id)
        }
        knownPeers.clear()
        connectedPeers.clear()

        try {
            audioTrack?.dispose()
            audioSource?.dispose()
            factory?.dispose()
            audioDeviceModule?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
        audioSource = null
        factory = null
        audioDeviceModule = null

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        am.mode = previousMode
        am.isSpeakerphoneOn = previousSpeaker
    }

    // ---------- Controls ----------

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    // 1.0 is normal. Lower is quieter, higher is louder (up to about 3).
    fun setRemoteVolume(volume: Double) {
        remoteVolume = volume
        applyVolume()
    }

    fun statusText(): String {
        if (!active) return ""
        val total = knownPeers.size
        if (total == 0) return "Voice on. Waiting for others to join voice."
        val ok = connectedPeers.values.count { it }
        return "Voice on. Connected to $ok of $total."
    }

    private fun applyVolume() {
        for (pc in peers.values) {
            try {
                for (receiver in pc.receivers) {
                    (receiver.track() as? AudioTrack)?.setVolume(remoteVolume)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------- Connections ----------

    private fun createPeer(other: String, initiator: Boolean): PeerConnection? {
        peers[other]?.let { return it }
        val f = factory ?: return null

        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        connectedPeers[other] = true
                        applyVolume()
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        connectedPeers[other] = false
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                sendSignal(
                    other,
                    mapOf(
                        "type" to "ice",
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "candidate" to candidate.sdp
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: return null

        peers[other] = pc
        val track = audioTrack
        if (track != null) {
            pc.addTrack(track, listOf("stream-$myId"))
        }

        if (initiator) {
            val constraints = MediaConstraints()
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    pc.setLocalDescription(SimpleSdpObserver(), desc)
                    sendSignal(other, mapOf("type" to "offer", "sdp" to desc.description))
                }
            }, constraints)
        }
        return pc
    }

    private fun closePeer(other: String) {
        remoteReady.remove(other)
        pendingIce.remove(other)
        connectedPeers.remove(other)
        val pc = peers.remove(other) ?: return
        try {
            pc.close()
            pc.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun markRemoteReady(other: String, pc: PeerConnection) {
        remoteReady[other] = true
        val waiting = pendingIce.remove(other)
        if (waiting != null) {
            for (candidate in waiting) {
                pc.addIceCandidate(candidate)
            }
        }
    }

    // ---------- Signaling messages ----------

    private fun sendSignal(to: String, data: Map<String, Any?>) {
        if (!active) return
        val message = HashMap<String, Any?>(data)
        message["from"] = myId
        db.getReference("rooms").child(roomCode).child("voice")
            .child("signals").child(to).push().setValue(message)
    }

    private fun handleSignal(snapshot: DataSnapshot) {
        if (!active) return
        val data = snapshot.value as? Map<*, *> ?: return
        val from = data["from"] as? String ?: return
        val type = data["type"] as? String ?: return

        when (type) {
            "offer" -> {
                val sdp = data["sdp"] as? String ?: return
                val pc = createPeer(from, false) ?: return
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        markRemoteReady(from, pc)
                        pc.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                if (desc == null) return
                                pc.setLocalDescription(SimpleSdpObserver(), desc)
                                sendSignal(from, mapOf("type" to "answer", "sdp" to desc.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }

            "answer" -> {
                val sdp = data["sdp"] as? String ?: return
                val pc = peers[from] ?: return
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        markRemoteReady(from, pc)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            "ice" -> {
                val mid = data["sdpMid"] as? String
                val index = (data["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                val sdp = data["candidate"] as? String ?: return
                val candidate = IceCandidate(mid, index, sdp)
                val pc = peers[from]
                if (pc != null && remoteReady[from] == true) {
                    pc.addIceCandidate(candidate)
                } else {
                    pendingIce.getOrPut(from) { java.util.Collections.synchronizedList(mutableListOf()) }
                        .add(candidate)
                }
            }
        }
    }
}