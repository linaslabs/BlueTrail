package com.project.bluetrail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val logger = Logger(context.applicationContext)

            // Check if BLUETOOTH_CONNECT has already been granted by the user
            val hasBtPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            if (hasBtPermission) {
                logger.log(
                    event = "SYSTEM_REBOOT",
                    device = "System",
                    details = "Device finished booting; resuming TrackingService"
                )
                val serviceIntent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_START_SERVICE
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                logger.log(
                    event = "SYSTEM_REBOOT",
                    device = "System",
                    details = "Reboot detected, but BLUETOOTH_CONNECT not granted yet; skipping service start"
                )
            }
        }
    }
}