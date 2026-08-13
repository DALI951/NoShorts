package com.dali951.noshorts

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Background APK download + install (mirrors task-tracker's update flow).
 * Uses the system DownloadManager so the download survives app close,
 * shows in the notification bar and resumes over bad connections.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"

    var downloadId: Long = -1
        private set
    var downloadingVersion: String? = null
        private set

    fun start(context: Context, url: String, version: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("NoShorts v$version")
            .setDescription("Downloading update…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "NoShorts-v$version.apk"
            )
        downloadId = dm.enqueue(request)
        downloadingVersion = version
        Log.i(TAG, "Download started: $url")
    }

    /** Called when the app's download receiver fires. */
    fun onDownloadComplete(context: Context, id: Long) {
        if (id != downloadId) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        val version = downloadingVersion ?: "latest"

        if (cursor != null && cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            cursor.close()
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val file = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "NoShorts-v$version.apk"
                )
                if (file.exists()) {
                    install(context, file)
                }
            }
        }
        downloadId = -1
        downloadingVersion = null
    }

    private fun install(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launching installer for ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.message}")
            Toast.makeText(context, "Could not open installer", Toast.LENGTH_SHORT).show()
        }
    }

    /** Current download progress in percent, or -1 when nothing is downloading. */
    fun progress(context: Context): Float {
        if (downloadId < 0) return -1f
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return -1f
        }
        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        cursor.close()
        return if (total <= 0) 0f else (bytes * 100f / total).coerceIn(0f, 100f)
    }

    fun isDownloading(): Boolean = downloadId >= 0
}
