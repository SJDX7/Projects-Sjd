package com.example.watchtogetherbysjd

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.delay
import java.io.File

class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val force: Boolean
)

class DownloadState(val status: Int, val downloaded: Long, val total: Long)

object AppUpdater {
    private const val APK_NAME = "update.apk"

    fun installedVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            0L
        }
    }

    fun installedVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun check(
        context: Context,
        db: FirebaseDatabase,
        onResult: (UpdateInfo?) -> Unit,
        onError: () -> Unit
    ) {
        db.getReference("appUpdate").get()
            .addOnSuccessListener { s ->
                val code = s.child("versionCode").getValue(Long::class.java) ?: 0L
                val url = s.child("apkUrl").getValue(String::class.java) ?: ""
                if (code > installedVersionCode(context) && url.startsWith("https://")) {
                    onResult(
                        UpdateInfo(
                            versionCode = code,
                            versionName = s.child("versionName").getValue(String::class.java) ?: code.toString(),
                            apkUrl = url,
                            notes = s.child("notes").getValue(String::class.java) ?: "",
                            force = s.child("force").getValue(Boolean::class.java) ?: false
                        )
                    )
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { onError() }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    private fun apkFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, APK_NAME)
    }

    fun startDownload(context: Context, info: UpdateInfo): Long {
        val file = apkFile(context)
        if (file.exists()) file.delete()
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Watch Together " + info.versionName)
            .setDescription("Downloading update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun queryDownload(context: Context, id: Long): DownloadState {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (it != null && it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return DownloadState(status, got, total)
            }
        }
        return DownloadState(DownloadManager.STATUS_FAILED, 0L, 0L)
    }

    fun install(context: Context) {
        val file = apkFile(context)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

@Composable
fun UpdateChecker(db: FirebaseDatabase, trigger: Int) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadId by remember { mutableStateOf(-1L) }
    var percent by remember { mutableStateOf(0) }

    val downloading = downloadId >= 0

    LaunchedEffect(trigger) {
        val manual = trigger > 0
        AppUpdater.check(
            context, db,
            onResult = { found ->
                if (found != null) {
                    info = found
                } else if (manual) {
                    Toast.makeText(context, "You are on the latest version", Toast.LENGTH_SHORT).show()
                }
            },
            onError = {
                if (manual) {
                    Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    LaunchedEffect(downloadId) {
        if (downloadId < 0) return@LaunchedEffect
        while (true) {
            val st = AppUpdater.queryDownload(context, downloadId)
            if (st.status == DownloadManager.STATUS_SUCCESSFUL) {
                downloadId = -1L
                AppUpdater.install(context)
                break
            } else if (st.status == DownloadManager.STATUS_FAILED) {
                downloadId = -1L
                Toast.makeText(context, "Download failed. Try again.", Toast.LENGTH_LONG).show()
                break
            } else {
                percent = if (st.total > 0) ((st.downloaded * 100) / st.total).toInt() else 0
            }
            delay(500)
        }
    }

    val current = info
    if (current != null) {
        AlertDialog(
            onDismissRequest = { if (!current.force && !downloading) info = null },
            title = { Text("Update available") },
            text = {
                Column {
                    Text(
                        "Version " + current.versionName +
                                " is ready (you have " + AppUpdater.installedVersionName(context) + ").",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (current.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(current.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    if (downloading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Downloading… $percent%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        if (!AppUpdater.canInstall(context)) {
                            Toast.makeText(
                                context,
                                "Allow installs from this app, then come back and tap Update again",
                                Toast.LENGTH_LONG
                            ).show()
                            AppUpdater.openInstallPermissionSettings(context)
                        } else {
                            percent = 0
                            downloadId = AppUpdater.startDownload(context, current)
                        }
                    }
                ) { Text("Update") }
            },
            dismissButton = {
                if (!current.force && !downloading) {
                    TextButton(onClick = { info = null }) { Text("Later") }
                }
            }
        )
    }
}