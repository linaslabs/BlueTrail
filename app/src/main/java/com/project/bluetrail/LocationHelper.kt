package com.project.bluetrail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Defines swappable location policies for testing purposes
 */
enum class SamplingPolicy(
    val id: String,
    val displayName: String,
    val intervalMs: Long,
    val priority: Int
) {
    IDLE_LISTENING_ONLY(
        id = "LISTENING_ONLY",
        displayName = "Idle (Listening Only)",
        intervalMs = 0L,
        priority = Priority.PRIORITY_PASSIVE
    ),
    POLICY_A_HIGH_30S(
        id = "POLICY_A_HIGH_30S",
        displayName = "Policy A (High / 30s)",
        intervalMs = 30_000L,
        priority = Priority.PRIORITY_HIGH_ACCURACY
    ),
    POLICY_B_BALANCED_60S(
        id = "POLICY_B_BAL_60S",
        displayName = "Policy B (Bal / 60s)",
        intervalMs = 60_000L,
        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
    ),
    POLICY_C_PREROLL_180S(
        id = "POLICY_C_BAL_180S",
        displayName = "Policy C (Pre-Roll / 180s)",
        intervalMs = 180_000L,
        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
    );

    fun next(): SamplingPolicy {
        val values = entries.toTypedArray()
        val nextIndex = (this.ordinal + 1) % values.size
        return values[nextIndex]
    }

    companion object {
        fun fromId(id: String?): SamplingPolicy {
            return entries.find { it.id == id } ?: POLICY_A_HIGH_30S
        }
    }
}

data class LocationFixResult(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val fixLatencyMs: Long?,
    val lastKnownAgeSeconds: Long?,
    val modeUsed: String,
    val failureReason: String? = null
)

class LocationHelper(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val prefs = context.getSharedPreferences("bluetrail_prefs", Context.MODE_PRIVATE)

    // A property to allow toggling between high accuracy (GPS) and balanced power (Wi-Fi/Cell)
    var useHighAccuracy: Boolean
        get() = prefs.getBoolean("use_high_accuracy", true)
        set(value) = prefs.edit().putBoolean("use_high_accuracy", value).apply()

    var currentPolicy: SamplingPolicy
        get() = SamplingPolicy.fromId(prefs.getString("sampling_policy_id", SamplingPolicy.POLICY_A_HIGH_30S.id))
        set(value) = prefs.edit().putString("sampling_policy_id", value.id).apply()
    private var activeLocationCallback: LocationCallback? = null
    private var lastFixRealtimeMs: Long = 0L

    val isPeriodicLoggingActive: Boolean
        get() = activeLocationCallback != null

    /**
     * Checks if the fine or coarse location permissions are granted
     */
    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    /**
     * Starts continuous periodic location logging
     */
    @SuppressLint("MissingPermission")
    fun startPeriodicLogging(
        policy: SamplingPolicy = currentPolicy,
        onFixReceived: (LocationFixResult, Long) -> Unit
    ): Boolean {
        stopPeriodicLogging()

        if (policy == SamplingPolicy.IDLE_LISTENING_ONLY || !hasLocationPermission()) {
            return false
        }

        val request = LocationRequest.Builder(policy.priority, policy.intervalMs)
            .setMinUpdateIntervalMillis(policy.intervalMs / 2) // Other apps can't pass location updates to us faster than every 15 seconds
            .setWaitForAccurateLocation(false)
            .build()

        lastFixRealtimeMs = SystemClock.elapsedRealtime()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val nowMs = SystemClock.elapsedRealtime()
                val gapSinceLastFixMs = if (lastFixRealtimeMs > 0L) nowMs - lastFixRealtimeMs else 0L
                lastFixRealtimeMs = nowMs

                val ageSec = calculateLocationAgeInSeconds(location)
                val fixResult = LocationFixResult(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    fixLatencyMs = gapSinceLastFixMs, // Records interval gap between fixes
                    lastKnownAgeSeconds = ageSec,
                    modeUsed = policy.id
                )
                onFixReceived(fixResult, gapSinceLastFixMs)
            }
        }

        activeLocationCallback = callback
        fusedLocationClient.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
        )
        return true
    }

    fun stopPeriodicLogging() {
        activeLocationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
        activeLocationCallback = null
        lastFixRealtimeMs = 0L
    }

    /**
     * Ensures that both manual fixes and event-driven fixes are captured
     * 1. Inspects system's cached last-known location and calculates age in seconds.
     * 2. Measures stopwatch latency (ms) to compute a fresh location fix.
     * 3. Delivers clean metrics via callback.
     */
    @SuppressLint("MissingPermission")
    fun captureFreshFix(onResult: (LocationFixResult) -> Unit) {
        val activePriority = when {
            currentPolicy != SamplingPolicy.IDLE_LISTENING_ONLY -> currentPolicy.priority
            useHighAccuracy -> Priority.PRIORITY_HIGH_ACCURACY
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val modeLabel = if (activePriority == Priority.PRIORITY_HIGH_ACCURACY) {
            "HIGH_ACCURACY"
        } else {
            "BALANCED_POWER"
        }

        if (!hasLocationPermission()) {
            onResult(
                LocationFixResult(
                    latitude = null,
                    longitude = null,
                    accuracyMeters = null,
                    fixLatencyMs = null,
                    lastKnownAgeSeconds = null,
                    modeUsed = modeLabel,
                    failureReason = "Permission not granted"
                )
            )
            return
        }

        // Query the system's cached last-known location to evaluate how fresh it is
        fusedLocationClient.lastLocation.addOnCompleteListener { lastTask ->
            var lastKnownAgeSec: Long? = null
            if (lastTask.isSuccessful && lastTask.result != null) {
                lastKnownAgeSec = calculateLocationAgeInSeconds(lastTask.result)
            }

            val startTimeMs = SystemClock.elapsedRealtime()
            val cancellationTokenSource = CancellationTokenSource()

            // Request fresh location fix and measure latency
            fusedLocationClient.getCurrentLocation(activePriority, cancellationTokenSource.token)
                .addOnSuccessListener { freshLocation: Location? ->
                    val latencyMs = SystemClock.elapsedRealtime() - startTimeMs
                    if (freshLocation != null) {
                        onResult(
                            LocationFixResult(
                                latitude = freshLocation.latitude,
                                longitude = freshLocation.longitude,
                                accuracyMeters = freshLocation.accuracy,
                                fixLatencyMs = latencyMs,
                                lastKnownAgeSeconds = lastKnownAgeSec,
                                modeUsed = modeLabel
                            )
                        )
                    } else {
                        onResult(
                            LocationFixResult(
                                latitude = null,
                                longitude = null,
                                accuracyMeters = null,
                                fixLatencyMs = latencyMs,
                                lastKnownAgeSeconds = lastKnownAgeSec,
                                modeUsed = modeLabel,
                                failureReason = "Location provider returned null"
                            )
                        )
                    }
                }
                .addOnFailureListener { exception ->
                    val latencyMs = SystemClock.elapsedRealtime() - startTimeMs
                    onResult(
                        LocationFixResult(
                            latitude = null,
                            longitude = null,
                            accuracyMeters = null,
                            fixLatencyMs = latencyMs,
                            lastKnownAgeSeconds = lastKnownAgeSec,
                            modeUsed = modeLabel,
                            failureReason = exception.message ?: "Fix request failed"
                        )
                    )
                }
        }
    }

    /**
     * Calculates the age of a location in seconds by subtracting the hardware timestamp from the current system time
     * This calculates the exact age of the cached location fix in seconds
     */
    private fun calculateLocationAgeInSeconds(location: Location): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            (ageNanos / 1_000_000_000L).coerceAtLeast(0L)
        } else {
            val ageMillis = System.currentTimeMillis() - location.time
            (ageMillis / 1000L).coerceAtLeast(0L)
        }
    }
}