package com.droidcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.R

class CastService : Service() {

    companion object {
        const val ACTION_START_CAST = "com.droidcast.action.START_CAST"
        const val ACTION_STOP_CAST = "com.droidcast.action.STOP_CAST"
        const val EXTRA_RECEIVER_NAME = "extra_receiver_name"

        private const val CHANNEL_ID = "droidcast_casting_channel"
        private const val NOTIFICATION_ID = 1042
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAST -> {
                DroidLog.i(DroidLog.TAG_SESSION, "Stop Casting tapped from foreground notification")
                CastSessionCoordinator.stopCasting("Stopped from notification")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_CAST -> {
                val receiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME) ?: "My Laptop"
                val notification = buildNotification(receiverName)
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
                DroidLog.i(DroidLog.TAG_SESSION, "CastService promoted to foreground (mediaProjection)")
                CastSessionCoordinator.onForegroundServiceStarted()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DroidLog.i(DroidLog.TAG_SESSION, "CastService destroyed")
        if (CastSessionCoordinator.uiState.value.castStatus !is CastStatus.Idle) {
            CastSessionCoordinator.stopCasting("Android service stopped")
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(receiverName: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastService::class.java).apply {
                action = ACTION_STOP_CAST
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_casting_to, receiverName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_casting),
                stopIntent
            )
            .build()
    }
}
