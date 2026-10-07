package com.example.watchtogetherbysjd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(ThemeStore.isDark(this@MainActivity)) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WatchTogetherScreen(
                        darkTheme = dark,
                        onDarkThemeChange = { value ->
                            dark = value
                            ThemeStore.setDark(this@MainActivity, value)
                        }
                    )
                }
            }
        }
    }
}

// Remembers the dark theme choice on this phone (first time it follows the phone's own setting)
object ThemeStore {
    private const val PREFS = "settings"

    fun isDark(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains("dark")) return sp.getBoolean("dark", false)
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark", dark)
            .apply()
    }
}

// Dark theme on/off switch
@Composable
fun DarkThemeRow(dark: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (dark) "Dark theme: On" else "Dark theme: Off",
            style = MaterialTheme.typography.titleMedium
        )
        Switch(checked = dark, onCheckedChange = onChange)
    }
}

// Maximum number of people in one room
const val MAX_ROOM_SIZE = 6

// Latest playback state received from the room
class RemoteState {
    var known = false
    var playing = false
    var positionMs = 0L
    var updatedAt = 0L
    var offset = 0L
    var by = ""
    var seekId = 0L
}

// Local player flags
class PlayerFlags {
    var starting = false
    var playWanted = false
    var pfd: ParcelFileDescriptor? = null
    var layout: VLCVideoLayout? = null
    var viewsAttached = false
    var rate = 1.0f
    var lastRateChange = 0L
    var lastSeekAt = 0L
    var lastAppliedSeekId = 0L
    var lastReactionAt = 0L
    var lastChatAt = 0L
}

// A small round button that sits on top of the video
@Composable
fun IconBubble(
    onClick: () -> Unit,
    color: Color = Color(0x99000000),
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// Full screen icon (four corners). exit = true draws the "leave full screen" version
@Composable
fun FullscreenIcon(exit: Boolean) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val l = w * 0.35f
        val s = 3f
        val c = Color.White
        if (!exit) {
            drawLine(c, Offset(0f, 0f), Offset(l, 0f), s)
            drawLine(c, Offset(0f, 0f), Offset(0f, l), s)
            drawLine(c, Offset(w, 0f), Offset(w - l, 0f), s)
            drawLine(c, Offset(w, 0f), Offset(w, l), s)
            drawLine(c, Offset(0f, h), Offset(l, h), s)
            drawLine(c, Offset(0f, h), Offset(0f, h - l), s)
            drawLine(c, Offset(w, h), Offset(w - l, h), s)
            drawLine(c, Offset(w, h), Offset(w, h - l), s)
        } else {
            drawLine(c, Offset(0f, l), Offset(l, l), s)
            drawLine(c, Offset(l, 0f), Offset(l, l), s)
            drawLine(c, Offset(w, l), Offset(w - l, l), s)
            drawLine(c, Offset(w - l, 0f), Offset(w - l, l), s)
            drawLine(c, Offset(0f, h - l), Offset(l, h - l), s)
            drawLine(c, Offset(l, h), Offset(l, h - l), s)
            drawLine(c, Offset(w, h - l), Offset(w - l, h - l), s)
            drawLine(c, Offset(w - l, h), Offset(w - l, h - l), s)
        }
    }
}

// Settings icon (three dots)
@Composable
fun MoreIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val r = size.width * 0.11f
        drawCircle(Color.White, r, Offset(size.width / 2f, size.height * 0.15f))
        drawCircle(Color.White, r, Offset(size.width / 2f, size.height * 0.5f))
        drawCircle(Color.White, r, Offset(size.width / 2f, size.height * 0.85f))
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (if (ms < 0) 0 else ms) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun WatchTogetherScreen(
    darkTheme: Boolean = false,
    onDarkThemeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val db = remember { FirebaseDatabase.getInstance() }

    var isHost by remember { mutableStateOf(true) }
    var voiceOn by remember { mutableStateOf(false) }
    var micMuted by remember { mutableStateOf(false) }
    var voiceVolume by remember { mutableStateOf(1f) }
    var voiceStatus by remember { mutableStateOf("") }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }

    // Profile (saved on this phone) and everyone's profile in the room
    var myProfile by remember { mutableStateOf(ProfileStore.load(context)) }
    var showProfile by remember { mutableStateOf(false) }
    var memberProfiles by remember { mutableStateOf<Map<String, UserProfile>>(emptyMap()) }

    // Room state
    var roomCode by remember { mutableStateOf("") }
    var joined by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var sharedUrl by remember { mutableStateOf("") }
    var memberCount by remember { mutableStateOf(0) }

    // Playback control approval
    var requestSent by remember { mutableStateOf(false) }
    var pendingRequests by remember { mutableStateOf<List<String>>(emptyList()) }
    var controllerIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Chat
    var showChat by remember { mutableStateOf(false) }
    var chatUnread by remember { mutableStateOf(0) }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }

    // Playback UI state
    var currentPos by remember { mutableStateOf(0L) }
    var totalDur by remember { mutableStateOf(0L) }
    var isPlayingUi by remember { mutableStateOf(false) }
    var swipeLabel by remember { mutableStateOf("") }

    // Debug info (temporary)
    var debugText by remember { mutableStateOf("") }
    var lastSeek by remember { mutableStateOf("") }
    var lastEvent by remember { mutableStateOf("") }

    // Seek bar state
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(0f) }
    // Keeps the bar at the new spot right after a seek (VLC reports the old time for a moment)
    var seekHoldPos by remember { mutableStateOf(-1L) }
    var seekHoldUntil by remember { mutableStateOf(0L) }

    // Emoji reactions floating on the video, and the "check for updates" button counter
    val reactions = remember { mutableStateListOf<FloatingReaction>() }
    var updateTrigger by remember { mutableStateOf(0) }

    val remote = remember { RemoteState() }
    val flags = remember { PlayerFlags() }
    val deviceId = remember { java.util.UUID.randomUUID().toString() }
    val voice = remember { VoiceEngine(context, db) }

    // VLC engine
    val libVLC = remember(context) { LibVLC(context, arrayListOf<String>()) }
    val mediaPlayer = remember(libVLC) { MediaPlayer(libVLC) }

    // Only the host, or a guest the host has approved, can control the video
    val hasControl = controllerIds.contains(deviceId)
    val canControl = isHost || hasControl

    // Orientation / full screen
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showFull = joined && isLandscape
    var showControls by remember { mutableStateOf(true) }

    // Settings (audio track choice)
    var showSettings by remember { mutableStateOf(false) }
    var audioTrackList by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedAudioTrack by remember { mutableStateOf(-1) }

    // Manual sync fine-tune for this phone (milliseconds to play ahead)
    var syncOffsetMs by remember { mutableStateOf(0L) }

    // Which page of the settings dialog is open (0 = Room, 1 = Playback, 2 = Voice)
    var settingsTab by remember { mutableStateOf(0) }

    // Hardware video decoding (turn off if the video stutters on this phone)
    var hwDecoding by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.setEventListener(null)
                mediaPlayer.stop()
                mediaPlayer.release()
                libVLC.release()
                flags.pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            voice.stop()
        }
    }

    // Keep the screen awake while inside a room
    DisposableEffect(joined) {
        view.keepScreenOn = joined
        onDispose { view.keepScreenOn = false }
    }

    // Hide the status/navigation bars in full screen
    DisposableEffect(showFull) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (showFull) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // Keep the navigation bar hidden in full screen, even when the chat keyboard opens
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(showFull, imeOpen, showChat) {
        if (showFull) {
            val window = activity?.window
            if (window != null) {
                val c = WindowCompat.getInsetsController(window, window.decorView)
                repeat(4) {
                    c.hide(WindowInsetsCompat.Type.systemBars())
                    delay(150)
                }
            }
        }
    }

    // Status bar and navigation bar icons: dark on the light theme, light on the dark theme
    SideEffect {
        val window = activity?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = !darkTheme
            c.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Fix black screen: release the video surface when the app is in the background
    // and attach it again when the app comes back
    DisposableEffect(activity) {
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            try {
                if (event == Lifecycle.Event.ON_STOP) {
                    if (flags.viewsAttached) {
                        mediaPlayer.detachViews()
                        flags.viewsAttached = false
                    }
                } else if (event == Lifecycle.Event.ON_START) {
                    val layout = flags.layout
                    if (layout != null && !flags.viewsAttached) {
                        mediaPlayer.attachViews(layout, null, false, false)
                        flags.viewsAttached = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // When the host approves this phone, tell the guest
    LaunchedEffect(hasControl) {
        if (hasControl) {
            requestSent = false
            if (!isHost) {
                Toast.makeText(context, "The host gave you playback control", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Helper functions ----------

    fun curTime(): Long = mediaPlayer.time.coerceAtLeast(0L)

    fun curLength(): Long = mediaPlayer.length.coerceAtLeast(0L)

    // Name shown for a person in the room
    fun nameOf(id: String): String {
        return memberProfiles[id]?.name ?: ("Guest " + id.take(4).uppercase())
    }

    fun pushState(playing: Boolean, position: Long, isSeek: Boolean = false) {
        if (isSeek) {
            // A new unique id tells every phone "this is a real seek, jump exactly here"
            val newId = maxOf(System.currentTimeMillis() + remote.offset, flags.lastAppliedSeekId + 1)
            flags.lastAppliedSeekId = newId
        }
        db.getReference("rooms").child(roomCode).child("state").setValue(
            mapOf(
                "playing" to playing,
                "positionMs" to position,
                "by" to deviceId,
                "seekId" to flags.lastAppliedSeekId,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        )
    }

    fun setPlaying(playing: Boolean) {
        flags.playWanted = playing
        if (playing) mediaPlayer.play() else mediaPlayer.pause()
    }

    // Best known current position (uses the new spot while VLC is still catching up after a seek)
    fun basePos(): Long {
        return if (seekHoldPos >= 0 && System.currentTimeMillis() < seekHoldUntil) seekHoldPos else curTime()
    }

    fun doSeek(target: Long) {
        val t = if (target < 0) 0L else target
        mediaPlayer.time = t
        flags.lastSeekAt = System.currentTimeMillis()
        seekHoldPos = t
        seekHoldUntil = System.currentTimeMillis() + 1500L
        currentPos = t
    }

    fun applyRemote() {
        if (!remote.known || selectedMediaUri == null || flags.starting) return
        // Ignore changes this phone made itself
        if (remote.by == deviceId) return
        val now = System.currentTimeMillis()
        val serverNow = now + remote.offset
        val elapsed = serverNow - remote.updatedAt
        val safeElapsed = if (elapsed in 0..10000) elapsed else 0L
        val target = if (remote.playing) {
            remote.positionMs + safeElapsed + syncOffsetMs
        } else {
            remote.positionMs
        }
        val diff = target - basePos()          // positive = this phone is behind
        val stateChanged = flags.playWanted != remote.playing
        val forcedSeek = remote.seekId > flags.lastAppliedSeekId

        // Right after any seek, VLC reports the old time for a moment, so skip drift correction
        val cooling = !forcedSeek && !stateChanged && (now - flags.lastSeekAt < 2500L)
        val seekLimit = if (!remote.playing) 250L else if (stateChanged) 400L else 1200L

        if (forcedSeek) {
            flags.lastAppliedSeekId = remote.seekId
            lastSeek = "remote seek: " + curTime() + " -> " + target
            doSeek(target)
        } else if (!cooling && abs(diff) > seekLimit) {
            lastSeek = "drift fix: " + curTime() + " -> " + target
            doSeek(target)
        }

        if (flags.rate != 1.0f) {
            flags.rate = 1.0f
            mediaPlayer.setRate(1.0f)
        }

        if (stateChanged) {
            setPlaying(remote.playing)
        }
    }

    fun loadMedia(uri: Uri) {
        try {
            mediaPlayer.stop()
            flags.pfd?.close()
            flags.pfd = null
            val media: Media
            if (uri.scheme == "content" || uri.scheme == "file") {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    Toast.makeText(context, "Cannot open this file", Toast.LENGTH_SHORT).show()
                    return
                }
                flags.pfd = pfd
                media = Media(libVLC, pfd.fileDescriptor)
            } else {
                media = Media(libVLC, uri)
            }
            media.setHWDecoderEnabled(hwDecoding, false)
            selectedMediaUri = uri
            mediaPlayer.media = media
            media.release()
            flags.starting = true
            flags.playWanted = false
            flags.rate = 1.0f
            mediaPlayer.setRate(1.0f)
            mediaPlayer.play()
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot play this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePlay() {
        if (!canControl || selectedMediaUri == null) return
        val newPlaying = !flags.playWanted
        setPlaying(newPlaying)
        pushState(newPlaying, curTime())
    }

    fun seekBy(deltaMs: Long) {
        if (!canControl || selectedMediaUri == null) return
        val len = curLength()
        val newPos = (basePos() + deltaMs).coerceIn(0L, if (len > 0) len else Long.MAX_VALUE)
        lastSeek = "my seek: " + basePos() + " -> " + newPos
        doSeek(newPos)
        pushState(flags.playWanted, newPos, true)
    }

    fun seekToPosition(positionMs: Long) {
        if (!canControl || selectedMediaUri == null) return
        val len = curLength()
        val newPos = positionMs.coerceIn(0L, if (len > 0) len else Long.MAX_VALUE)
        lastSeek = "bar seek: " + basePos() + " -> " + newPos
        doSeek(newPos)
        pushState(flags.playWanted, newPos, true)
    }

    // Send an emoji to everyone in the room (it also shows on this phone)
    fun sendReaction(emoji: String) {
        val now = System.currentTimeMillis()
        if (now - flags.lastReactionAt < 300) return
        flags.lastReactionAt = now
        val ref = db.getReference("rooms").child(roomCode).child("reactions").push()
        ref.setValue(
            mapOf(
                "emoji" to emoji,
                "by" to deviceId,
                "at" to ServerValue.TIMESTAMP
            )
        )
        // Clean up so old reactions do not pile up in the database
        scope.launch {
            delay(8000)
            ref.removeValue()
        }
    }

    // Send a chat message to everyone in the room
    fun sendChat(text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - flags.lastChatAt < 400) return
        flags.lastChatAt = now
        val p = myProfile
        db.getReference("rooms").child(roomCode).child("chat").push().setValue(
            mapOf(
                "by" to deviceId,
                "name" to (p?.name ?: ("Guest " + deviceId.take(4).uppercase())),
                "avatar" to (p?.avatar ?: "🙂"),
                "host" to isHost,
                "text" to t,
                "at" to ServerValue.TIMESTAMP
            )
        )
    }

    // Guest: ask the host for playback control
    fun requestControl() {
        if (isHost || hasControl || requestSent) return
        requestSent = true
        val ref = db.getReference("rooms").child(roomCode).child("requests").child(deviceId)
        ref.onDisconnect().removeValue()
        ref.setValue(true)
        Toast.makeText(context, "Request sent to the host", Toast.LENGTH_SHORT).show()
    }

    // Host: allow or deny a guest's request
    fun answerRequest(guestId: String, approve: Boolean) {
        val room = db.getReference("rooms").child(roomCode)
        if (approve) {
            room.child("controllers").child(guestId).setValue(true)
        }
        room.child("requests").child(guestId).removeValue()
    }

    // Host: take control away from a guest
    fun revokeControl(guestId: String) {
        db.getReference("rooms").child(roomCode).child("controllers").child(guestId).removeValue()
    }

    fun goLandscape() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun goPortrait() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Return to automatic rotation shortly after
        scope.launch {
            delay(1500)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun loadAudioTracks() {
        try {
            val tracks = mediaPlayer.getAudioTracks()
            audioTrackList = tracks?.map { t -> Pair(t.id, t.name ?: ("Track " + t.id)) } ?: emptyList()
            selectedAudioTrack = mediaPlayer.getAudioTrack()
        } catch (e: Exception) {
            audioTrackList = emptyList()
        }
    }

    // Add this phone (with its profile) to the room's people list.
    // It is removed automatically if the app closes.
    fun registerMember() {
        val p = myProfile
        val me = db.getReference("rooms").child(roomCode).child("members").child(deviceId)
        me.onDisconnect().removeValue()
        me.setValue(
            mapOf(
                "name" to (p?.name ?: ("Guest " + deviceId.take(4).uppercase())),
                "avatar" to (p?.avatar ?: "🙂"),
                "host" to isHost
            )
        )
    }

    fun leaveRoom() {
        val room = db.getReference("rooms").child(roomCode)
        room.child("members").child(deviceId).removeValue()
        room.child("requests").child(deviceId).removeValue()
        room.child("controllers").child(deviceId).removeValue()
        voice.stop()
        voiceOn = false
        micMuted = false
        // The host closing the room removes it, so nobody else can join it
        if (isHost) {
            room.removeValue()
        }
        reactions.clear()
        chatMessages.clear()
        chatUnread = 0
        showChat = false
        requestSent = false
        pendingRequests = emptyList()
        controllerIds = emptyList()
        memberProfiles = emptyMap()
        setPlaying(false)
        mediaPlayer.stop()
        flags.pfd?.close()
        flags.pfd = null
        selectedMediaUri = null
        remote.known = false
        sharedUrl = ""
        joined = false
        if (isLandscape) goPortrait()
    }

    // ---------- Effects ----------

    // Player events
    DisposableEffect(mediaPlayer) {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (flags.starting) {
                        // First start of a new file: show first frame and wait
                        flags.starting = false
                        flags.playWanted = false
                        mediaPlayer.pause()
                        if (!isHost) applyRemote()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    lastEvent = "player error"
                }
                MediaPlayer.Event.EndReached -> {
                    flags.playWanted = false
                    lastEvent = "ended"
                }
            }
        }
        onDispose { mediaPlayer.setEventListener(null) }
    }

    // Listen to the room (everyone)
    DisposableEffect(joined, roomCode) {
        if (!joined) return@DisposableEffect onDispose { }

        val room = db.getReference("rooms").child(roomCode)
        val offsetRef = db.getReference(".info/serverTimeOffset")

        val offsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                remote.offset = snapshot.getValue(Long::class.java) ?: 0L
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        val stateListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                remote.playing = snapshot.child("playing").getValue(Boolean::class.java) ?: false
                remote.positionMs = snapshot.child("positionMs").getValue(Long::class.java) ?: 0L
                remote.updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                remote.by = snapshot.child("by").getValue(String::class.java) ?: ""
                remote.seekId = snapshot.child("seekId").getValue(Long::class.java) ?: 0L
                remote.known = true
                applyRemote()
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        // Who the host has approved to control the video
        val controllersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                controllerIds = snapshot.children.mapNotNull { it.key }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        // Guests waiting for the host's answer
        val requestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ids = snapshot.children.mapNotNull { it.key }
                pendingRequests = ids
                // My request disappeared and I was not approved: the host said no
                if (!isHost && requestSent && !ids.contains(deviceId) &&
                    !controllerIds.contains(deviceId)
                ) {
                    requestSent = false
                    Toast.makeText(context, "The host declined your request", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        val urlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val url = snapshot.getValue(String::class.java) ?: ""
                if (!isHost && url.isNotBlank() && url != sharedUrl) {
                    sharedUrl = url
                    loadMedia(Uri.parse(url))
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        val reactionListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val emoji = snapshot.child("emoji").getValue(String::class.java) ?: return
                val at = snapshot.child("at").getValue(Long::class.java)
                if (at != null) {
                    // Ignore old reactions that were already in the room before we joined
                    val age = System.currentTimeMillis() + remote.offset - at
                    if (age > 5000) return
                }
                if (reactions.size >= 25) reactions.removeAt(0)
                reactions.add(
                    FloatingReaction(
                        System.nanoTime(), emoji,
                        Random.nextFloat(), Random.nextFloat() * 6.28f
                    )
                )
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        // Chat messages (the last 50 are loaded when you join)
        val chatQuery = room.child("chat").limitToLast(50)
        val chatListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val text = snapshot.child("text").getValue(String::class.java) ?: return
                val by = snapshot.child("by").getValue(String::class.java) ?: ""
                val name = snapshot.child("name").getValue(String::class.java) ?: "Guest"
                val avatar = snapshot.child("avatar").getValue(String::class.java) ?: "🙂"
                val hostFlag = snapshot.child("host").getValue(Boolean::class.java) ?: false
                val at = snapshot.child("at").getValue(Long::class.java)
                if (chatMessages.size >= 200) chatMessages.removeAt(0)
                chatMessages.add(
                    ChatMessage(
                        snapshot.key ?: System.nanoTime().toString(),
                        by, name, avatar, hostFlag, text
                    )
                )
                // Count it as unread only if it is new, from someone else, and chat is closed
                val fresh = at == null || (System.currentTimeMillis() + remote.offset - at) < 4000
                if (fresh && by != deviceId && !showChat) {
                    chatUnread = chatUnread + 1
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        // Everyone in the room with their name and picture
        val membersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                memberCount = snapshot.childrenCount.toInt()
                val map = HashMap<String, UserProfile>()
                for (c in snapshot.children) {
                    val id = c.key ?: continue
                    val n = c.child("name").getValue(String::class.java)
                    val a = c.child("avatar").getValue(String::class.java) ?: "🙂"
                    val h = c.child("host").getValue(Boolean::class.java) ?: false
                    if (n != null) map[id] = UserProfile(n, a, h)
                }
                memberProfiles = map
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        offsetRef.addValueEventListener(offsetListener)
        room.child("state").addValueEventListener(stateListener)
        room.child("controllers").addValueEventListener(controllersListener)
        room.child("requests").addValueEventListener(requestsListener)
        room.child("mediaUrl").addValueEventListener(urlListener)
        room.child("members").addValueEventListener(membersListener)
        room.child("reactions").addChildEventListener(reactionListener)
        chatQuery.addChildEventListener(chatListener)

        onDispose {
            offsetRef.removeEventListener(offsetListener)
            room.child("state").removeEventListener(stateListener)
            room.child("controllers").removeEventListener(controllersListener)
            room.child("requests").removeEventListener(requestsListener)
            room.child("mediaUrl").removeEventListener(urlListener)
            room.child("members").removeEventListener(membersListener)
            room.child("reactions").removeEventListener(reactionListener)
            chatQuery.removeEventListener(chatListener)
        }
    }

    // Host: heartbeat every 2 seconds while playing to correct small drift
    LaunchedEffect(joined, isHost, roomCode) {
        while (joined && isHost) {
            delay(2000)
            if (flags.playWanted && selectedMediaUri != null) {
                val now = System.currentTimeMillis()
                // Don't overwrite a seek a friend just made, or one we just applied
                val friendRecent = remote.known && remote.by != deviceId &&
                        (now + remote.offset - remote.updatedAt) < 4000
                val cooling = now - flags.lastSeekAt < 3000
                if (!friendRecent && !cooling) {
                    pushState(true, curTime())
                }
            }
        }
    }

    // Update the time display
    LaunchedEffect(joined) {
        while (joined) {
            val t = curTime()
            currentPos = if (seekHoldPos >= 0 && System.currentTimeMillis() < seekHoldUntil) {
                seekHoldPos
            } else {
                seekHoldPos = -1L
                t
            }
            totalDur = curLength()
            isPlayingUi = flags.playWanted
            voiceStatus = voice.statusText()
            debugText = "engine=VLC length=" + totalDur + " seekable=" + mediaPlayer.isSeekable
            delay(250)
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadMedia(it) }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voice.start(roomCode)
            voiceOn = true
            micMuted = false
            Toast.makeText(context, "Voice chat on. Use headphones to avoid echo.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Mic permission required for Voice Chat", Toast.LENGTH_SHORT).show()
        }
    }

    // Voice chat on/off and mute (used by the buttons in full screen)
    fun toggleVoice() {
        if (!voiceOn) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            voice.stop()
            voiceOn = false
            micMuted = false
            Toast.makeText(context, "Left voice chat", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleMute() {
        micMuted = !micMuted
        voice.setMuted(micMuted)
    }

    // ---------- Profile dialog (first launch, or when Edit is tapped) ----------

    if (myProfile == null || showProfile) {
        ProfileDialog(
            initial = myProfile,
            required = myProfile == null,
            onSave = { p ->
                ProfileStore.save(context, p)
                myProfile = p
                showProfile = false
                // If already in a room, let everyone see the new name and picture
                if (joined) {
                    db.getReference("rooms").child(roomCode).child("members").child(deviceId)
                        .updateChildren(mapOf("name" to p.name, "avatar" to p.avatar))
                }
            },
            onDismiss = { showProfile = false }
        )
    }

    // ---------- Host approval popup ----------

    if (joined && isHost && pendingRequests.isNotEmpty()) {
        val who = pendingRequests.first()
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Control request") },
            text = {
                Text(
                    nameOf(who) + " wants to play, pause and skip for everyone. Allow?"
                )
            },
            confirmButton = {
                TextButton(onClick = { answerRequest(who, true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { answerRequest(who, false) }) { Text("Deny") }
            }
        )
    }

    // ---------- Settings dialog ----------

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Room settings") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Page buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Room", "Playback", "Voice").forEachIndexed { index, label ->
                            if (settingsTab == index) {
                                Button(
                                    onClick = { settingsTab = index },
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) { Text(label) }
                            } else {
                                OutlinedButton(
                                    onClick = { settingsTab = index },
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) { Text(label) }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (settingsTab == 0) {
                        // ----- Room page -----

                        // My profile
                        val meP = myProfile
                        if (meP != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(meP.avatar, fontSize = 26.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    meP.name + (if (isHost) " (Host)" else ""),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { showProfile = true }) { Text("Edit") }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        DarkThemeRow(dark = darkTheme, onChange = onDarkThemeChange)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Who controls the video?", style = MaterialTheme.typography.titleSmall)
                        if (isHost) {
                            Text(
                                "Only you control the video. Guests must ask, and you allow each one.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (controllerIds.isEmpty()) {
                                Text(
                                    "No guest has control right now.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                controllerIds.forEach { id ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            nameOf(id) + " has control",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { revokeControl(id) }) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                if (hasControl) {
                                    "The host allowed you to control the video."
                                } else {
                                    "Only the host controls the video. You can ask for control."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!hasControl) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { requestControl() },
                                    enabled = !requestSent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (requestSent) "Request sent…" else "Request control")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // People in the room
                        Text(
                            "People in room (" + memberCount + ")",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        memberProfiles.entries
                            .sortedByDescending { it.value.isHost }
                            .forEach { (id, p) ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(p.avatar, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        p.name +
                                                (if (id == deviceId) " (you)" else "") +
                                                (if (p.isHost) " • Host"
                                                else if (controllerIds.contains(id)) " • can control"
                                                else ""),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isHost) {
                            Text("Share a video link", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "For friends who do not have the file. The link must start with https://",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = linkText,
                                onValueChange = { linkText = it },
                                label = { Text("Video link") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val url = linkText.trim()
                                    if (url.startsWith("https://")) {
                                        sharedUrl = url
                                        loadMedia(Uri.parse(url))
                                        db.getReference("rooms").child(roomCode)
                                            .child("mediaUrl").setValue(url)
                                        showSettings = false
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Link must start with https://",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Share link with everyone")
                            }
                        } else {
                            Text("Video", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Have the same file? Use Select file on the main screen. " +
                                        "No file? Wait for the host to share a link.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (settingsTab == 1) {
                        // ----- Playback page -----
                        Text("Video decoding", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hardware decoding", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = hwDecoding,
                                onCheckedChange = {
                                    hwDecoding = it
                                    // Reload the current file so the change takes effect
                                    selectedMediaUri?.let { u -> loadMedia(u) }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            "Turn this off if the video stutters on this phone. " +
                                    "The video restarts when you change it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Audio track", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (audioTrackList.isEmpty()) {
                            Text(
                                "No audio tracks found yet. Select a file and start playing it first.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            audioTrackList.forEach { (id, name) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            mediaPlayer.setAudioTrack(id)
                                            selectedAudioTrack = id
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = id == selectedAudioTrack,
                                        onClick = {
                                            mediaPlayer.setAudioTrack(id)
                                            selectedAudioTrack = id
                                        }
                                    )
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Sync adjust (this phone only)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "If this phone sounds late compared to the other, press +50 until it matches. " +
                                    "Bluetooth earphones are often 150 to 300 ms late.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (syncOffsetMs > -2000L) {
                                    syncOffsetMs -= 50L
                                    if (flags.playWanted) mediaPlayer.time = (curTime() - 50L).coerceAtLeast(0L)
                                }
                            }) {
                                Text("-50")
                            }
                            Text(
                                syncOffsetMs.toString() + " ms",
                                modifier = Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = {
                                if (syncOffsetMs < 2000L) {
                                    syncOffsetMs += 50L
                                    if (flags.playWanted) mediaPlayer.time = curTime() + 50L
                                }
                            }) {
                                Text("+50")
                            }
                            TextButton(onClick = {
                                if (flags.playWanted) {
                                    mediaPlayer.time = (curTime() - syncOffsetMs).coerceAtLeast(0L)
                                }
                                syncOffsetMs = 0L
                            }) {
                                Text("Reset")
                            }
                        }
                    } else {
                        // ----- Voice page -----
                        if (!voiceOn) {
                            Text(
                                "You are not in voice chat. Tap Join voice on the main screen first.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text("Friends' voice volume", style = MaterialTheme.typography.titleSmall)
                            Slider(
                                value = voiceVolume,
                                onValueChange = {
                                    voiceVolume = it
                                    voice.setRemoteVolume(it.toDouble())
                                },
                                valueRange = 0f..3f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Connection", style = MaterialTheme.typography.titleSmall)
                            Text(voiceStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Close") }
            }
        )
    }

    // Update check (silent on start, shows messages when the button is tapped)
    UpdateChecker(db, updateTrigger)

    // ---------- UI ----------

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showFull) {
                        Modifier.displayCutoutPadding()
                    } else {
                        Modifier
                            .systemBarsPadding()
                            // While chat is open the keyboard must not shrink the video
                            .then(if (showChat) Modifier else Modifier.imePadding())
                            .padding(16.dp)
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!showFull) {
                Text(
                    text = "Watch Together",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            if (!joined) {
                // ---------- Lobby ----------

                // My profile card
                val me = myProfile
                if (me != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(me.avatar, fontSize = 32.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(me.name, style = MaterialTheme.typography.titleMedium)
                            Text("Your profile", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { showProfile = true }) { Text("Edit") }
                    }
                }

                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { input ->
                        roomCode = input.filter { c -> c.isLetterOrDigit() }.uppercase().take(10)
                    },
                    label = { Text("Room code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isHost) "Role: Host" else "Role: Guest",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(checked = isHost, onCheckedChange = { isHost = it })
                }

                Spacer(modifier = Modifier.height(12.dp))

                DarkThemeRow(dark = darkTheme, onChange = onDarkThemeChange)

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (roomCode.length < 3) {
                            Toast.makeText(context, "Enter a room code (3+ characters)", Toast.LENGTH_SHORT).show()
                        } else if (isHost) {
                            // A running room cannot be taken over by someone else
                            db.getReference("rooms").child(roomCode).get()
                                .addOnSuccessListener { snapshot ->
                                    if (snapshot.child("members").exists()) {
                                        Toast.makeText(
                                            context,
                                            "Room code already in use. Choose another code.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        // Fresh room: clears old data
                                        db.getReference("rooms").child(roomCode)
                                            .setValue(mapOf("host" to deviceId))
                                        sharedUrl = ""
                                        requestSent = false
                                        joined = true
                                        registerMember()
                                    }
                                }
                                .addOnFailureListener {
                                    Toast.makeText(
                                        context,
                                        "Could not create the room. Check your internet.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        } else {
                            // Guest: the room must already exist and must not be full
                            db.getReference("rooms").child(roomCode).get()
                                .addOnSuccessListener { snapshot ->
                                    if (snapshot.exists() &&
                                        snapshot.child("members").childrenCount >= MAX_ROOM_SIZE
                                    ) {
                                        Toast.makeText(
                                            context,
                                            "This room is full (max $MAX_ROOM_SIZE people).",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else if (snapshot.exists()) {
                                        sharedUrl = ""
                                        requestSent = false
                                        joined = true
                                        registerMember()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Room not found. Ask the host to create it first.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                .addOnFailureListener {
                                    Toast.makeText(
                                        context,
                                        "Could not check the room. Check your internet.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isHost) "Create Room" else "Join Room")
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = { updateTrigger++ }) {
                    Text("Check for updates (v" + AppUpdater.installedVersionName(context) + ")")
                }
            } else {
                // ---------- In room ----------
                if (!showFull) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (isHost) "Host" else "Guest") + " • Room $roomCode • " +
                                    memberCount + "/" + MAX_ROOM_SIZE,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                showChat = !showChat
                                if (showChat) chatUnread = 0
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (chatUnread > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        ) {
                            Text(if (chatUnread > 0) "Chat ($chatUnread)" else "Chat")
                        }
                        TextButton(onClick = { leaveRoom() }) {
                            Text("Leave")
                        }
                    }
                }

                // Video area with swipe-to-seek
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VLCVideoLayout(ctx).also { layout ->
                                flags.layout = layout
                                mediaPlayer.attachViews(layout, null, false, false)
                                flags.viewsAttached = true
                            }
                        },
                        onRelease = {
                            try {
                                if (flags.viewsAttached) mediaPlayer.detachViews()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            flags.viewsAttached = false
                            flags.layout = null
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (selectedMediaUri == null) {
                        Text(
                            text = "No Media Selected",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    // Transparent layer that catches swipes
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showControls = !showControls })
                            }
                            .pointerInput(canControl) {
                                if (canControl) {
                                    var dragTotal = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragTotal = 0f },
                                        onDragEnd = {
                                            val deltaMs = (dragTotal * 60).toLong()
                                            swipeLabel = ""
                                            if (abs(deltaMs) > 500) seekBy(deltaMs)
                                        },
                                        onDragCancel = { swipeLabel = "" },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragTotal += dragAmount
                                            val secs = (dragTotal * 60 / 1000).toInt()
                                            swipeLabel = (if (secs >= 0) "+" else "") + secs + "s"
                                        }
                                    )
                                }
                            }
                    )

                    // Floating emoji reactions
                    ReactionOverlay(
                        reactions = reactions,
                        onDone = { reactions.remove(it) }
                    )

                    // Emoji buttons on the left side in full screen
                    if (showFull && showControls) {
                        ReactionBar(
                            onReact = { sendReaction(it) },
                            vertical = true,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(8.dp)
                        )
                    }

                    if (swipeLabel.isNotEmpty()) {
                        Text(
                            text = swipeLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineLarge
                        )
                    }

                    // Chat bubble in full screen (shows a red number when there are new messages)
                    if (showFull && !showChat && (showControls || chatUnread > 0)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color(0xCC000000), CircleShape)
                                    .clickable {
                                        showChat = true
                                        chatUnread = 0
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("💬", fontSize = 22.sp)
                            }
                            if (chatUnread > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(20.dp)
                                        .background(Color(0xFFE53935), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (chatUnread > 9) "9+" else chatUnread.toString(),
                                        color = Color.White,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // Small icons in the top-right corner of the video: settings and full screen
                    if (!showFull || showControls) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Voice chat buttons (full screen only)
                            if (showFull) {
                                if (voiceOn) {
                                    // Mute / unmute my mic (red when muted)
                                    IconBubble(
                                        onClick = { toggleMute() },
                                        color = if (micMuted) Color(0xFFE53935) else Color(0x99000000)
                                    ) {
                                        Text(if (micMuted) "🔇" else "🎤", fontSize = 16.sp)
                                    }
                                }
                                // Join / leave voice chat (green when on)
                                IconBubble(
                                    onClick = { toggleVoice() },
                                    color = if (voiceOn) Color(0xFF2E7D32) else Color(0x99000000)
                                ) {
                                    Text("🎧", fontSize = 16.sp)
                                }
                            }
                            IconBubble(onClick = {
                                loadAudioTracks()
                                showSettings = true
                            }) {
                                MoreIcon()
                            }
                            IconBubble(onClick = {
                                if (showFull) goPortrait() else goLandscape()
                            }) {
                                FullscreenIcon(exit = showFull)
                            }
                        }
                    }

                    // Controls shown on top of the video in full screen (tap video to hide/show)
                    if (showFull && showControls) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0x99000000))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatTime(
                                        if (sliderDragging) (sliderValue * totalDur).toLong() else currentPos
                                    ) + " / " + formatTime(totalDur),
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = if (sliderDragging) {
                                        sliderValue
                                    } else if (totalDur > 0) {
                                        (currentPos.toFloat() / totalDur).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    },
                                    onValueChange = {
                                        sliderDragging = true
                                        sliderValue = it
                                    },
                                    onValueChangeFinished = {
                                        seekToPosition((sliderValue * totalDur).toLong())
                                        sliderDragging = false
                                    },
                                    enabled = canControl && totalDur > 0,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { seekBy(-10000) },
                                    enabled = canControl,
                                    modifier = Modifier.weight(1f)
                                ) { Text("-10s") }
                                Button(
                                    onClick = { togglePlay() },
                                    enabled = canControl,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (isPlayingUi) "Pause" else "Play") }
                                Button(
                                    onClick = { seekBy(10000) },
                                    enabled = canControl,
                                    modifier = Modifier.weight(1f)
                                ) { Text("+10s") }
                            }
                        }
                    }
                }

                if (!showFull) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = formatTime(if (sliderDragging) (sliderValue * totalDur).toLong() else currentPos) +
                                " / " + formatTime(totalDur),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Draggable seek bar: drag to any point, release to jump there
                    Slider(
                        value = if (sliderDragging) {
                            sliderValue
                        } else if (totalDur > 0) {
                            (currentPos.toFloat() / totalDur).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onValueChange = {
                            sliderDragging = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            seekToPosition((sliderValue * totalDur).toLong())
                            sliderDragging = false
                        },
                        enabled = canControl && totalDur > 0,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Playback controls
                    if (canControl) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { seekBy(-10000) },
                                modifier = Modifier.weight(1f)
                            ) { Text("-10s") }
                            Button(
                                onClick = { togglePlay() },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isPlayingUi) "Pause" else "Play") }
                            Button(
                                onClick = { seekBy(10000) },
                                modifier = Modifier.weight(1f)
                            ) { Text("+10s") }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "The host controls playback",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = { requestControl() },
                                enabled = !requestSent
                            ) {
                                Text(if (requestSent) "Request sent…" else "Request control")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Emoji reactions
                    ReactionBar(onReact = { sendReaction(it) })

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { mediaPickerLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Select file")
                        }

                        Button(
                            onClick = {
                                if (!voiceOn) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    voice.stop()
                                    voiceOn = false
                                    micMuted = false
                                    Toast.makeText(context, "Left voice chat", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (voiceOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (voiceOn) "Leave voice" else "Join voice")
                        }

                        if (voiceOn) {
                            Button(
                                onClick = {
                                    micMuted = !micMuted
                                    voice.setMuted(micMuted)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (micMuted) "Unmute" else "Mute")
                            }
                        }
                    }
                } // end of portrait-only section
            }
        }

        // Chat panel: slides over the lower part of the screen, the video keeps playing
        if (joined && showChat && !showFull) {
            ChatPanel(
                messages = chatMessages,
                myId = deviceId,
                onSend = { sendChat(it) },
                onClose = { showChat = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            )
        }

        // Full screen chat: a side panel on the right, the video keeps playing
        if (joined && showChat && showFull) {
            ChatPanel(
                messages = chatMessages,
                myId = deviceId,
                onSend = { sendChat(it) },
                onClose = { showChat = false },
                sidePanel = true,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .displayCutoutPadding()
                    .imePadding()
                    .fillMaxHeight()
                    .width(320.dp)
            )
        }

    } // end of root Box
}