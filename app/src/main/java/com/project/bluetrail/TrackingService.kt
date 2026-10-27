package com.project.bluetrail

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.RemoteInput
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class TrackingService : Service() {

    private val logger by lazy { Logger(applicationContext) }
    private val btHelper by lazy { BluetoothHelper(applicationContext) }
    private val notiHelper by lazy { NotificationHelper(applicationContext) }

    private var markCounter = 0

    companion object {
        const val ACTION_START_SERVICE = "com.project.bluetrail.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = NotificationHelper.ACTION_STOP_SERVICE
        const val ACTION_QUICK_MARK = NotificationHelper.ACTION_QUICK_MARK
        const val ACTION_TAG_MARK = NotificationHelper.ACTION_TAG_MARK
        const val EXTRA_MANUAL_LABEL = "com.project.bluetrail.EXTRA_MANUAL_LABEL"
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val deviceName = device?.name ?: "Unknown"
            val deviceAddress = device?.address ?: "No_MAC"

            when (action) {
                // Handles Bluetooth device connecting
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    logger.log(
                        event = "BT_ACL_CONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress"
                    )
                    notiHelper.update("Connected: $deviceName")
                }

                // Handles Bluetooth device disconnecting
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    logger.log(
                        event = "BT_ACL_DISCONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress"
                    )
                    notiHelper.update("Disconnected: $deviceName")
                }

                // Handling Bluetooth being turned on or off (bluetooth adapter state has changed)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val prevState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)

                    val stateStr = when (state) {
                        BluetoothAdapter.STATE_ON -> "STATE_ON"
                        BluetoothAdapter.STATE_OFF -> "STATE_OFF"
                        BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
                        BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
                        else -> "UNKNOWN ($state)"
                    }
                    val prevStr = when (prevState) {
                        BluetoothAdapter.STATE_ON -> "STATE_ON"
                        BluetoothAdapter.STATE_OFF -> "STATE_OFF"
                        BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
                        BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
                        else -> "UNKNOWN ($prevState)"
                    }

                    logger.log(
                        event = "BT_ADAPTER_STATE",
                        device = "SystemAdapter",
                        details = "State: $prevStr -> $stateStr"
                    )

                    // Re-check connected devices when Bluetooth turns back on
                    if (state == BluetoothAdapter.STATE_ON) {
                        btHelper.checkAndLogConnectedDevices(logger, trigger = "BT_TURNED_ON")
                    }
                }

                // Handles Bluetooth device starting or stopping audio
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1)
                    val stateStr = when (state) {
                        BluetoothA2dp.STATE_PLAYING -> "A2DP_PLAYING"
                        BluetoothA2dp.STATE_NOT_PLAYING -> "A2DP_NOT_PLAYING"
                        else -> "STATE_$state"
                    }
                    logger.log(
                        event = "A2DP_STATE",
                        device = deviceName,
                        details = "Audio streaming: $stateStr; MAC: $deviceAddress"
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                // Android 14+ will crash if startForeground is called without BLUETOOTH_CONNECT granted
                if (!hasBluetoothConnectPermission()) {
                    logger.log(
                        event = "SERVICE_ERROR",
                        details = "Cannot start connectedDevice FGS: BLUETOOTH_CONNECT not granted"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                startInForeground("Monitoring active")
                logger.log(event = "SERVICE_START", details = "TrackingService started in foreground")
                // Perform startup check for connected devices across A2DP, HEADSET, and GATT
                btHelper.checkAndLogConnectedDevices(logger, trigger = "SERVICE_STARTUP")
            }

            ACTION_QUICK_MARK -> {
                markCounter++
                logger.log(
                    event = "USER_MARK",
                    device = "QuickMark",
                    details = "Mark #$markCounter"
                )
                notiHelper.update("Monitoring active (Marks: $markCounter)")
            }

            ACTION_TAG_MARK -> {
                markCounter++
                val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
                val typedTag = remoteInputResults?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_MANUAL_LABEL)
                    ?: "CustomTag"

                logger.log(
                    event = "USER_MARK",
                    device = "TaggedMark",
                    details = "Mark #$markCounter: $typedTag"
                )
                notiHelper.update("Marked: $typedTag (Total: $markCounter)")
            }

            ACTION_STOP_SERVICE -> {
                logger.log(event = "SERVICE_STOP", details = "TrackingService stopped by user")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun startInForeground(initialText: String) {
        val notification = notiHelper.buildNotification(initialText)
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: SecurityException) {
            logger.log(event = "SECURITY_EXCEPTION", details = e.message ?: "startForeground failed")
            stopSelf()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerBluetoothReceiver() {
        // Indicated which broadcasts shoudl be routed to this receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}