/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.thimmaiah.ebors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class DownloadService : Service(), DownloadRepository.Listener {

    @Volatile
    private var lastNotificationAt: Long = 0L

    @Volatile
    private var lastNotificationSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        DownloadRepository.initialize(applicationContext)
        createNotificationChannel()
        DownloadRepository.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadRepository.resumePendingDownloads()
        val active = DownloadRepository.getActiveDownloads()

        // Service contract: we were started via startForegroundService(...)
        // so we MUST call startForeground within ~5 s or Android will
        // crash the process with ForegroundServiceDidNotStartInTimeException.
        // We post the notification, then if there's no actual work to do
        // we immediately tear it down. On every device I've tested this
        // is fast enough that the notification never renders (B15 fix).
        startForeground(
            NOTIFICATION_ID,
            buildNotification(active),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DownloadRepository.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDownloadsChanged(downloads: List<DownloadItem>) {
        val active = downloads.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        if (active.isEmpty()) {
            maybeStopIfIdle()
            return
        }

        // Throttle foreground notification rebuilds. Each rebuild is a Binder
        // call into system_server; doing it every persist tick (~2 Hz) for
        // many concurrent downloads steals throughput from the download thread.
        // We rebuild when the *signature* changes (count, file, percent bucket)
        // OR at most every 750 ms.
        val signature = active.joinToString("|") {
            val pct = if (it.totalBytes > 0) {
                ((it.downloadedBytes * 20L) / it.totalBytes).coerceIn(0L, 20L)
            } else {
                -1L
            }
            "${it.id}:${it.status}:$pct"
        }
        // Monotonic: throttle must survive wall-clock changes (NITZ,
        // user setting). Using System.currentTimeMillis would let a
        // backward jump pin the gate "in the future" → notifications
        // silently freeze for the rest of the session.
        val now = SystemClock.uptimeMillis()
        if (signature == lastNotificationSignature && (now - lastNotificationAt) < NOTIFICATION_MIN_INTERVAL_MS) {
            return
        }
        lastNotificationSignature = signature
        lastNotificationAt = now

        startForeground(
            NOTIFICATION_ID,
            buildNotification(active),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun maybeStopIfIdle() {
        if (DownloadRepository.getActiveDownloads().isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(activeDownloads: List<DownloadItem>): Notification {
        val notificationIntent = Intent(this, DownloadsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (activeDownloads.size <= 1) {
            getString(R.string.download_notification_title_single)
        } else {
            getString(R.string.download_notification_title_multiple, activeDownloads.size)
        }

        val firstActive = activeDownloads.firstOrNull()
        val body = firstActive?.let { item ->
            when {
                item.totalBytes > 0L -> getString(
                    R.string.download_notification_with_progress,
                    item.fileName,
                    ((item.downloadedBytes * 100L) / item.totalBytes).coerceIn(0L, 100L),
                )

                else -> getString(R.string.download_notification_indeterminate, item.fileName)
            }
        } ?: getString(R.string.download_notification_idle)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (firstActive != null && firstActive.totalBytes > 0L) {
            val percent = ((firstActive.downloadedBytes * 100L) / firstActive.totalBytes)
                .coerceIn(0L, 100L)
                .toInt()
            builder.setProgress(100, percent, false)
        } else if (firstActive != null) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 6001
        private const val NOTIFICATION_MIN_INTERVAL_MS = 750L

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
