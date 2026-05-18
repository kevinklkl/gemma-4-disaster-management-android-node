package com.akbay.node

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    companion object {
        const val CHANNEL_ID = "akbay_downloads"
        const val NOTIFICATION_ID = 100
        @Volatile var progress = 0
        @Volatile var isRunning = false
        @Volatile var downloadedMb = 0
        @Volatile var totalMb = 0
        @Volatile var error: String? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val path = intent.getStringExtra("path") ?: return START_NOT_STICKY

        if (isRunning) return START_STICKY

        isRunning = true
        error = null
        startForeground(NOTIFICATION_ID, buildNotification("Starting download...", 0))

        scope.launch {
            var retries = 0
            val maxRetries = 10
            
            while (retries < maxRetries && isRunning) {
                try {
                    downloadWithResume(url, path)
                    // If we get here, download finished successfully
                    isRunning = false
                    break
                } catch (e: Exception) {
                    retries++
                    error = "Retry $retries/$maxRetries: ${e.message}"
                    updateNotification("Error: ${e.message}. Retrying...", progress)
                    delay(3000 * retries.toLong()) // Exponential backoff
                    
                    if (retries >= maxRetries) {
                        isRunning = false
                        notifyError("Download failed after $maxRetries attempts: ${e.message}")
                    }
                }
            }
            if (!isRunning) stopSelf()
        }

        return START_STICKY
    }

    private fun downloadWithResume(urlStr: String, path: String) {
        val dest = File(path)
        val tmp = File(dest.parent, "${dest.name}.tmp")
        
        val existingBytes = if (tmp.exists()) tmp.length() else 0L
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=$existingBytes-")
        }
        
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            throw IOException("Server returned HTTP $responseCode")
        }

        val serverLength = conn.contentLengthLong
        val totalBytes = if (responseCode == 206) serverLength + existingBytes else serverLength
        totalMb = (totalBytes / 1_000_000).toInt()

        conn.inputStream.use { input ->
            // Use append mode if we are resuming
            FileOutputStream(tmp, responseCode == 206).use { output ->
                val buffer = ByteArray(128 * 1024)
                var bytesRead: Int
                var totalRead = existingBytes
                var lastUpdate = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    downloadedMb = (totalRead / 1_000_000).toInt()
                    progress = if (totalBytes > 0) (totalRead * 100 / totalBytes).toInt() else 0

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 1000) {
                        updateNotification("Downloading: $downloadedMb / $totalMb MB", progress)
                        lastUpdate = now
                    }
                }
            }
        }
        
        // Finalize
        if (tmp.length() >= totalBytes - 1024) { 
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) {
                isRunning = false
                updateNotification("Download complete!", 100)
            } else {
                throw IOException("Failed to rename .tmp to final model file")
            }
        }
    }

    private fun buildNotification(text: String, prog: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Akbay Model Download")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, prog, prog == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, prog: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, prog))
    }

    private fun notifyError(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notif)
    }

    override fun onDestroy() {
        job.cancel()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
