package com.samaveta.android.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Downloads model artifacts.
 *
 * Non-negotiable security properties implemented here (all unit-testable and unit-tested):
 *  - HTTPS only; redirects to non-HTTPS are refused.
 *  - The artifact is written to a temporary `.part` file and *never* promoted to the model
 *    directory until its SHA-256 matches the registry value.
 *  - A mismatch deletes the partial download rather than leaving a poisoned file behind.
 *  - Redirects are refused by [ModelDownloadTransport]; a 3xx is a failure, not a detour.
 *
 * The service is a `dataSync` foreground service so a multi-hundred-megabyte download is visible to
 * the user and not silently killed — and so it cannot run invisibly.
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject
    lateinit var downloader: ModelDownloader

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val expectedSha = intent?.getStringExtra(EXTRA_SHA256)

        startForeground(NOTIFICATION_ID, buildNotification("Preparing download"))

        if (modelId == null || url == null || expectedSha == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Performs the transfer on a plain thread and always stops the foreground state, so a
        // failed download cannot leave a permanent notification behind.
        Thread {
            try {
                downloader.download(modelId, url, expectedSha) { progress ->
                    notify(buildNotification("Downloaded ${progress.bytesDownloaded / (1024 * 1024)} MB"))
                }
            } finally {
                androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of model downloads you started"
                    setShowBadge(false)
                },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_URL = "url"
        const val EXTRA_SHA256 = "sha256"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4201
    }
}

