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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.RemoteInput

data class EpisodeInfo(
    val episodeId: String,
    val deviceName: String,
    val deviceAddress: String,
    val startRealtimeMs: Long
)

class TrackingService : Service() {

    private val logger by lazy { Logger(applicationContext) }
    private val btHelper by lazy { BluetoothHelper(applicationContext) }
    private val notifHelper by lazy { NotificationHelper(applicationContext) }
    private val locationHelper by lazy { LocationHelper(applicationContext) }

    private var markCounter = 0
    private var trailFixCounter = 0
    private var episodeCounter = 0
    private var isForegroundStarted = false

    // Active episodes map (MAC -> EpisodeInfo)
    private val openEpisodes = mutableMapOf<String, EpisodeInfo>()
    private var isManualTestTrailActive = false

    // 10 second Debounce state for handling rapid disconnections and reconnections
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val pendingCloses = mutableMapOf<String, Runnable>()

    // 15-Minute Battery Ticker
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastChargeTickRealtimeMs: Long = 0L

    companion object {
        const val ACTION_START_SERVICE = "com.project.bluetrail.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = NotificationHelper.ACTION_STOP_SERVICE
        const val ACTION_QUICK_MARK = NotificationHelper.ACTION_QUICK_MARK
        const val ACTION_TAG_MARK = NotificationHelper.ACTION_TAG_MARK
        const val EXTRA_MANUAL_LABEL = "com.project.bluetrail.EXTRA_MANUAL_LABEL"

        const val ACTION_START_POLICY_TRAIL = "com.project.bluetrail.ACTION_START_POLICY_TRAIL"
        const val ACTION_STOP_POLICY_TRAIL = "com.project.bluetrail.ACTION_STOP_POLICY_TRAIL"
        const val ACTION_APPLY_POLICY_CHANGE = "com.project.bluetrail.ACTION_APPLY_POLICY_CHANGE"
        const val ACTION_TRIGGER_CHARGE_TICK = "com.project.bluetrail.ACTION_TRIGGER_CHARGE_TICK"

        private const val CHARGE_TICK_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val DEBOUNCE_DELAY_MS = 10_000L // 10 seconds
    }

    /**
     * Checks every 60 seconds if there has been 15 mins since last log
     */
    private val chargeTickRunnable = object : Runnable {
        override fun run() {
            checkAndLogChargeTick(trigger = "TIMER_15M", force = false)
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    /**
     * If 15 minutes has passed since last fix or if the fix is forced (manual fix) then log a charge tick
     */
    private fun checkAndLogChargeTick(trigger: String, force: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (force || lastChargeTickRealtimeMs == 0L || (nowMs - lastChargeTickRealtimeMs) >= CHARGE_TICK_INTERVAL_MS) {
            lastChargeTickRealtimeMs = nowMs
            val activePolicy = locationHelper.currentPolicy.id
            val trailActive = locationHelper.isPeriodicLoggingActive
            val epIds =
                if (openEpisodes.isEmpty()) "NONE" else openEpisodes.values.joinToString("+") { it.episodeId }
            logger.log(
                event = "CHARGE_TICK",
                device = "FuelGauge",
                details = "Trigger: $trigger; Policy: $activePolicy; TrailActive: $trailActive; OpenEpisodes: $epIds; Fixes: $trailFixCounter"
            )
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            checkAndLogChargeTick(trigger = "SYS_EVENT_CHECK", force = false)

            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            val deviceName = device?.name ?: "Unknown"
            val deviceAddress = device?.address ?: "No_MAC"

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val isTracked = btHelper.isDeviceTracked(deviceAddress)
                    logger.log(
                        event = "BT_ACL_CONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress; Tracked: $isTracked"
                    )

                    if (isTracked && openEpisodes.containsKey(deviceAddress)) {
                        handleTrackedReconnectWithDebounce(deviceAddress, deviceName)
                    } else {
                        notifHelper.update("Connected: $deviceName")
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val isTracked = btHelper.isDeviceTracked(deviceAddress)
                    logger.log(
                        event = "BT_ACL_DISCONNECTED",
                        device = deviceName,
                        details = "MAC: $deviceAddress; Tracked: $isTracked"
                    )

                    if (isTracked) {
                        handleTrackedDisconnect(deviceAddress, deviceName)
                    } else {
                        notifHelper.update("Disconnected (Untracked): $deviceName")
                        triggerSingleLocationFix(
                            triggerSource = "BT_DISCONNECT_UNTRACKED",
                            targetDevice = deviceName
                        )
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state =
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val prevState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_PREVIOUS_STATE,
                        BluetoothAdapter.ERROR
                    )

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

    /**
     * If a tracked device disconnects whilst an episode is pending a closure (an element within "pendingClose"), then we cancel the pending closure and continue the episode.
     * Otherwise, we create a new episode.
     */
    private fun handleTrackedDisconnect(macAddress: String, deviceName: String) {
        // If a there was an episode scheduled to be closed, cancel its scheduled closure and keep the original episode open
        pendingCloses[macAddress]?.let { runnable ->
            debounceHandler.removeCallbacks(runnable)
            pendingCloses.remove(macAddress)
            val epId = openEpisodes[macAddress]?.episodeId ?: "Unknown"
            logger.log(
                event = "DEBOUNCE_CANCEL",
                device = deviceName,
                details = "Re-disconnected within 10s debounce window; continuing Episode $epId"
            )
            notifHelper.update("Episode $epId Active (Debounce kept open)")
            return
        }

        if (openEpisodes.containsKey(macAddress)) return

        episodeCounter++
        val epId = "EP-$episodeCounter"
        openEpisodes[macAddress] =
            EpisodeInfo(epId, deviceName, macAddress, SystemClock.elapsedRealtime())

        logger.log(
            event = "EPISODE_OPEN",
            device = deviceName,
            details = "Episode: $epId; MAC: $macAddress; ActiveCount: ${openEpisodes.size}; Policy: ${locationHelper.currentPolicy.id}"
        )

        // Capture immediate disconnect pin
        triggerSingleLocationFix(triggerSource = "EPISODE_OPEN ($epId)", targetDevice = deviceName)

        // Start continuous periodic trail if not already running
        if (!locationHelper.isPeriodicLoggingActive) {
            startPolicyTrailLogging(trigger = "EPISODE_OPEN ($epId)")
        } else {
            notifHelper.update("Episode $epId Active (${openEpisodes.size} open)")
        }
    }

    /**
     * When we detect a reconnect, only confirm the reconnection and close the episode after a 10 second timer.
     * We add the timer to the "pendingCloses" map. We are pending the closure of an episode.
     */
    private fun handleTrackedReconnectWithDebounce(macAddress: String, deviceName: String) {
        val epInfo = openEpisodes[macAddress] ?: return
        // Cancel any older timer for this MAC just in case
        pendingCloses.remove(macAddress)?.let { debounceHandler.removeCallbacks(it) }

        logger.log(
            event = "DEBOUNCE_START",
            device = deviceName,
            details = "Episode: ${epInfo.episodeId}; Waiting 10s of stable link before closing"
        )
        notifHelper.update("Reconnected: $deviceName (Verifying 10s link...)")

        val closeRunnable = Runnable {
            pendingCloses.remove(macAddress)
            closeEpisode(macAddress, deviceName, epInfo)
        }
        pendingCloses[macAddress] = closeRunnable
        debounceHandler.postDelayed(closeRunnable, DEBOUNCE_DELAY_MS)
    }

    private fun closeEpisode(macAddress: String, deviceName: String, epInfo: EpisodeInfo) {
        openEpisodes.remove(macAddress)
        val durationSec = (SystemClock.elapsedRealtime() - epInfo.startRealtimeMs) / 1000L

        logger.log(
            event = "EPISODE_CLOSE",
            device = deviceName,
            details = "Episode: ${epInfo.episodeId}; DurationSec: ${durationSec}s; RemainingOpen: ${openEpisodes.size}"
        )

        triggerSingleLocationFix(
            triggerSource = "EPISODE_CLOSE (${epInfo.episodeId})",
            targetDevice = deviceName
        )

        // Master trail stops ONLY if no other episode is open AND manual test mode isn't active
        if (openEpisodes.isEmpty() && !isManualTestTrailActive) {
            stopPolicyTrailLogging(trigger = "ALL_EPISODES_CLOSED (Last: ${epInfo.episodeId})")
            notifHelper.update("Connected: $deviceName (${epInfo.episodeId} closed)")
        } else {
            notifHelper.update("${epInfo.episodeId} closed (${openEpisodes.size} still open)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val policy = locationHelper.currentPolicy
                ensureInForeground("Active (${policy.displayName})")
                logger.log(
                    event = "SERVICE_START",
                    details = "TrackingService started; Policy=${policy.id}; TrackedDevices=${btHelper.getTrackedAddresses().size}"
                )
                btHelper.checkAndLogConnectedDevices(logger, trigger = "SERVICE_STARTUP")

                mainHandler.removeCallbacks(chargeTickRunnable)
                checkAndLogChargeTick(trigger = "SERVICE_START", force = true)
                mainHandler.postDelayed(chargeTickRunnable, 60_000L)
            }

            ACTION_START_POLICY_TRAIL -> {
                // Ensure FGS is active even if the user tapped "Start Trail" without tapping "Start Monitor" first
                ensureInForeground("Starting Trail (${locationHelper.currentPolicy.displayName})")
                isManualTestTrailActive = true
                startPolicyTrailLogging(trigger = "MANUAL_TEST_START")
            }

            ACTION_STOP_POLICY_TRAIL -> {
                isManualTestTrailActive = false
                if (openEpisodes.isEmpty()) {
                    stopPolicyTrailLogging(trigger = "MANUAL_TEST_STOP")
                } else {
                    logger.log(
                        event = "MANUAL_TRAIL_STOP_DEFERRED",
                        details = "${openEpisodes.size} episode(s) still open; keeping trail active"
                    )
                }
            }

            ACTION_APPLY_POLICY_CHANGE -> {
                val newPolicy = locationHelper.currentPolicy
                logger.log(
                    event = "POLICY_CHANGED",
                    device = "UserSetting",
                    details = "Switched to ${newPolicy.id} (interval=${newPolicy.intervalMs}ms)"
                )
                if (locationHelper.isPeriodicLoggingActive || isManualTestTrailActive || openEpisodes.isNotEmpty()) {
                    startPolicyTrailLogging(trigger = "POLICY_HOT_SWAP")
                } else {
                    notifHelper.update("Active (${newPolicy.displayName})")
                }
            }

            ACTION_TRIGGER_CHARGE_TICK -> {
                checkAndLogChargeTick(trigger = "MANUAL_CHECKPOINT", force = true)
            }

            ACTION_QUICK_MARK -> {
                markCounter++
                logger.log(
                    event = "USER_MARK",
                    device = "QuickMark",
                    details = "Mark #$markCounter (In-Pocket Tap)"
                )
                notifHelper.update("Monitoring active (Marks: $markCounter)")
                triggerSingleLocationFix(
                    triggerSource = "QUICK_MARK #$markCounter",
                    targetDevice = "QuickMark"
                )
            }

            ACTION_TAG_MARK -> {
                markCounter++
                val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
                val typedTag =
                    remoteInputResults?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)
                        ?.toString()
                        ?: intent.getStringExtra(EXTRA_MANUAL_LABEL)
                        ?: "CustomTag"

                logger.log(
                    event = "USER_MARK",
                    device = "TaggedMark",
                    details = "Mark #$markCounter: $typedTag"
                )
                notifHelper.update("Marked: $typedTag (Total: $markCounter)")
                triggerSingleLocationFix(
                    triggerSource = "TAG_MARK #$markCounter ($typedTag)",
                    targetDevice = "TaggedMark"
                )
            }

            ACTION_STOP_SERVICE -> {
                debounceHandler.removeCallbacksAndMessages(null)
                pendingCloses.clear()
                isManualTestTrailActive = false
                openEpisodes.clear()
                stopPolicyTrailLogging(trigger = "SERVICE_STOP")
                checkAndLogChargeTick(trigger = "SERVICE_STOP", force = true)
                mainHandler.removeCallbacks(chargeTickRunnable)
                logger.log(event = "SERVICE_STOP", details = "TrackingService stopped by user")
                isForegroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun startPolicyTrailLogging(trigger: String) {
        val policy = locationHelper.currentPolicy
        trailFixCounter = 0
        checkAndLogChargeTick(trigger = "TRAIL_START_$trigger", force = true)

        if (policy == SamplingPolicy.IDLE_LISTENING_ONLY) {
            locationHelper.stopPeriodicLogging()
            logger.log(
                event = "TRAIL_START_IDLE",
                device = "PolicyRunner",
                details = "Trigger: $trigger; Policy is LISTENING_ONLY (0 location updates requested)"
            )
            notifHelper.update("Test Run: Idle (Listening Only)")
            return
        }

        val started = locationHelper.startPeriodicLogging(policy) { result, gapMs ->
            trailFixCounter++
            checkAndLogChargeTick(trigger = "TRAIL_FIX_CHECK", force = false)

            val epIds =
                if (openEpisodes.isEmpty()) "MANUAL_TEST" else openEpisodes.values.joinToString("+") { it.episodeId }
            logger.log(
                event = "TRAIL_FIX",
                device = epIds,
                details = "Fix #$trailFixCounter; Policy: ${result.modeUsed}; GapMs: ${gapMs}ms; Acc: ${result.accuracyMeters}m; Age: ${result.lastKnownAgeSeconds}s",
                latitude = result.latitude,
                longitude = result.longitude,
                accuracyM = result.accuracyMeters,
                fixLatencyMs = gapMs,
                lastKnownAgeS = result.lastKnownAgeSeconds
            )
            notifHelper.update("Trail ($epIds | ${policy.displayName}) Fixes: $trailFixCounter")
        }

        if (started) {
            logger.log(
                event = "TRAIL_STARTED",
                device = "PolicyRunner",
                details = "Trigger: $trigger; Policy: ${policy.id}; IntervalMs: ${policy.intervalMs}"
            )
            notifHelper.update("Trail Active: ${policy.displayName}")
        } else {
            logger.log(
                event = "TRAIL_START_FAILED",
                device = "PolicyRunner",
                details = "Trigger: $trigger; Policy: ${policy.id}; Check location permissions"
            )
        }
    }

    private fun stopPolicyTrailLogging(trigger: String) {
        if (locationHelper.isPeriodicLoggingActive || trailFixCounter > 0) {
            locationHelper.stopPeriodicLogging()
            checkAndLogChargeTick(trigger = "TRAIL_STOP_$trigger", force = true)
            logger.log(
                event = "TRAIL_STOPPED",
                device = "PolicyRunner",
                details = "Trigger: $trigger; TotalFixes: $trailFixCounter"
            )
            notifHelper.update("Trail Stopped (Fixes: $trailFixCounter)")
        }
    }

    private fun triggerSingleLocationFix(triggerSource: String, targetDevice: String) {
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

    private fun ensureInForeground(initialText: String) {
        val notification = notifHelper.buildNotification(initialText)

        var fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (locationHelper.hasLocationPermission()) {
                fgsTypes = fgsTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }

        try {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, fgsTypes)
            isForegroundStarted = true
        } catch (e: Exception) {
            logger.log(
                event = "FGS_FALLBACK",
                details = "Location FGS restricted (${e.javaClass.simpleName}); fallback to connectedDevice"
            )
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            isForegroundStarted = true
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
        debounceHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(chargeTickRunnable)
        locationHelper.stopPeriodicLogging()
        unregisterReceiver(bluetoothReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}