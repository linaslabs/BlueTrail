package com.project.bluetrail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlin.jvm.java

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "bluetrail_tracking_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_QUICK_MARK = "com.project.bluetrail.ACTION_QUICK_MARK"
        const val ACTION_TAG_MARK = "com.project.bluetrail.ACTION_TAG_MARK"
        const val ACTION_STOP_SERVICE = "com.project.bluetrail.ACTION_STOP_SERVICE"

        const val KEY_TEXT_REPLY = "extra_tag_text"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BlueTrail Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors Bluetooth connectivity events in the background"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(statusText: String): Notification {
        // Tapping the notification body opens MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Quick mark button on the notification itself
        val quickMarkIntent = Intent(context, TrackingService::class.java).apply {
            action = ACTION_QUICK_MARK
        }
        val quickMarkPendingIntent = PendingIntent.getService(
            context,
            1,
            quickMarkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Inline text tagging via RemoteInput (e.g. type "test-3 closed" from lockscreen)
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Tag (e.g. T03 case-closed)")
            .build()

        val tagIntent = Intent(context, TrackingService::class.java).apply {
            action = ACTION_TAG_MARK
        }
        val tagPendingIntent = PendingIntent.getService(
            context,
            2,
            tagIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tagAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "Tag",
            tagPendingIntent
        ).addRemoteInput(remoteInput).build()

        // Stop service button
        val stopIntent = Intent(context, TrackingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("BlueTrail Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "Quick Mark", quickMarkPendingIntent)
            .addAction(tagAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    fun update(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}