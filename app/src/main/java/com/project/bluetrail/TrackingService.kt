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

class TrackingService : Service() {

    private val logger by lazy { Logger(applicationContext) }
    private val btHelper by lazy { BluetoothHelper(applicationContext) }
    private val notifHelper by lazy { NotificationHelper(applicationContext) }
    private val locationHelper by lazy { LocationHelper(applicationContext) }

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
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    logger.log(
                        event = "BT_ACL_CONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress"
                    )
                    notifHelper.update("Connected: $deviceName")
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    logger.log(
                        event = "BT_ACL_DISCONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress"
                    )
                    notifHelper.update("Disconnected: $deviceName (Locating...)")

                    triggerLocationFix(triggerSource = "BT_DISCONNECT", targetDevice = deviceName)
                }

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

                    if (state == BluetoothAdapter.STATE_ON) {
                        btHelper.checkAndLogConnectedDevices(logger, trigger = "BT_TURNED_ON")
                    }
                }

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
                startInForeground("Monitoring active")
                logger.log(event = "SERVICE_START", details = "TrackingService started in foreground")
                btHelper.checkAndLogConnectedDevices(logger, trigger = "SERVICE_STARTUP")
            }

            ACTION_QUICK_MARK -> {
                markCounter++
                logger.log(
                    event = "USER_MARK",
                    device = "QuickMark",
                    details = "Mark #$markCounter (In-Pocket Tap)"
                )
                notifHelper.update("Monitoring active (Marks: $markCounter)")

                // Take location fix on every quick mark
                triggerLocationFix(triggerSource = "QUICK_MARK #$markCounter", targetDevice = "QuickMark")
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
                notifHelper.update("Marked: $typedTag (Total: $markCounter)")
                // CP4: Take location fix on every tagged mark
                triggerLocationFix(triggerSource = "TAG_MARK #$markCounter ($typedTag)", targetDevice = "TaggedMark")
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

    private fun triggerLocationFix(triggerSource: String, targetDevice: String) {
        locationHelper.captureFreshFix { result ->
            if (result.latitude != null && result.longitude != null) {
                logger.log(
                    event = "LOCATION_FIX",
                    device = targetDevice,
                    details = "Trigger: $triggerSource; Mode: ${result.modeUsed}; Latency: ${result.fixLatencyMs}ms; Acc: ${result.accuracyMeters}m; LastKnownAge: ${result.lastKnownAgeSeconds}s",
                    latitude = result.latitude,
                    longitude = result.longitude,
                    accuracyM = result.accuracyMeters,
                    fixLatencyMs = result.fixLatencyMs,
                    lastKnownAgeS = result.lastKnownAgeSeconds
                )
            } else {
                logger.log(
                    event = "LOCATION_FAILED",
                    device = targetDevice,
                    details = "Trigger: $triggerSource; Mode: ${result.modeUsed}; Reason: ${result.failureReason ?: "Unknown error"}; Latency: ${result.fixLatencyMs}ms; LastKnownAge: ${result.lastKnownAgeSeconds}s",
                    fixLatencyMs = result.fixLatencyMs,
                    lastKnownAgeS = result.lastKnownAgeSeconds
                )
            }
        }
    }

    private fun startInForeground(initialText: String) {
        val notification = notifHelper.buildNotification(initialText)

        // Only attach location if permission is granted
        var fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (locationHelper.hasLocationPermission()) {
                fgsTypes = fgsTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }

        try {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, fgsTypes)
        } catch (e: Exception) {
            // Safe fallback to default if Android blocks location foreground service (e.g. restricted on boot)
            logger.log(event = "FGS_FALLBACK", details = "Location FGS restricted (${e.javaClass.simpleName}); fallback to connectedDevice")
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
    }

    private fun registerBluetoothReceiver() {
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