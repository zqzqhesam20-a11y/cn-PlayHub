package com.kafianet.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Visible notification for a user-started transfer or hotspot library session. */
class TransferForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "kafianet_transfer"
        private const val NOTIFICATION_ID = 39555
        private const val ACTION_START = "com.kafianet.control.TRANSFER_START"
        private const val ACTION_CANCEL = "com.kafianet.control.TRANSFER_CANCEL"
        private const val EXTRA_TEXT = "notification_text"

        @Volatile private var notificationActive = false
        @Volatile private var cancellationRequested = false

        fun isCancellationRequested(): Boolean = cancellationRequested

        fun markCancellationRequested() {
            cancellationRequested = true
            runCatching { HotspotTransfer.cancel() }
            ShizukuServiceConnection.cancelCopy()
        }

        fun start(context: Context, text: String = "يجري نقل الملفات") {
            cancellationRequested = false
            notificationActive = true
            ensureChannel(context)
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun update(context: Context, text: String) {
            if (!notificationActive || cancellationRequested) return
            ensureChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context, text))
        }

        fun stop(context: Context) {
            notificationActive = false
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            context.stopService(Intent(context, TransferForegroundService::class.java))
            cancellationRequested = false
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "نقل الملفات",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعار يوضح أن نقل الملفات أو مشاركة المكتبة قيد التنفيذ"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context, text: String): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val cancelTransfer = PendingIntent.getService(
                context,
                1,
                Intent(context, TransferForegroundService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("كافية نت")
                .setContentText(text.take(140))
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء النقل", cancelTransfer)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            markCancellationRequested()
            notificationActive = false
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notificationActive = true
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "يجري نقل الملفات"
        val notification = buildNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
