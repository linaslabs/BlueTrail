package com.project.bluetrail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

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

    // Checking if the fine or coarse location permissions are granted
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
     * 1. Inspects system's cached last-known location and calculates age in seconds.
     * 2. Measures stopwatch latency (ms) to compute a fresh location fix.
     * 3. Delivers clean metrics via callback.
     */
    @SuppressLint("MissingPermission")
    fun captureFreshFix(onResult: (LocationFixResult) -> Unit) {
        val modeLabel = if (useHighAccuracy) "HIGH_ACCURACY" else "BALANCED_POWER"
        val priority = if (useHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
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

            // Request fresh location fix and measure latency
            val startTimeMs = SystemClock.elapsedRealtime()
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(priority, cancellationTokenSource.token)
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