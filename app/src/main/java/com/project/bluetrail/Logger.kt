package com.project.bluetrail

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Logger(private val context: Context) {

    companion object {
        // Shared static mutex across all instances in MainActivity and TrackingService
        // Important so that only one thread can write to the file at a time
        private val fileLock = Any()
    }

    private val logFileName = "bluetrail_log.csv"
    private val logFile: File = File(context.filesDir, logFileName)

    init {
        synchronized(fileLock) {
            if (!logFile.exists()) {
                writeHeader()
            }
        }
    }

    private fun writeHeader() {
        val header = "timestamp_iso,event,device,details,screen_state,battery_pct,charge_counter_uah,latitude,longitude,accuracy_m,fix_latency_ms,last_known_age_s\n"
        logFile.writeText(header)
    }

    fun log(
        event: String,
        device: String = "N/A",
        details: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyM: Float? = null,
        fixLatencyMs: Long? = null,
        lastKnownAgeS: Long? = null
    ) {
        val timestamp = getIsoTimestamp()
        val screenState = getScreenState()
        val batteryPct = getBatteryPercentage()
        val chargeCounter = getChargeCounterUah()

        val sanitisedDevice = device.replace(",", ";").replace("\n", " ")
        val sanitisedDetails = details.replace(",", ";").replace("\n", " ")

        val latStr = latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "N/A"
        val lngStr = longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "N/A"
        val accStr = accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
        val latMsStr = fixLatencyMs?.toString() ?: "N/A"
        val ageStr = lastKnownAgeS?.toString() ?: "N/A"

        val logEntry = "$timestamp,$event,$sanitisedDevice,$sanitisedDetails,$screenState,$batteryPct,$chargeCounter,$latStr,$lngStr,$accStr,$latMsStr,$ageStr\n"

        synchronized(fileLock) {
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
        }
    }

    fun readLog(): String {
        synchronized(fileLock) {
            return if (logFile.exists()) {
                logFile.readText()
            } else {
                "Log file is empty."
            }
        }
    }

    fun clearLog() {
        synchronized(fileLock) {
            if (logFile.exists()) {
                logFile.delete()
            }
            writeHeader()
        }
    }

    fun getExportIntent(): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BlueTrail Log Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun getScreenState(): String {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (powerManager?.isInteractive == true) "SCREEN_ON" else "SCREEN_OFF"
    }

    private fun getBatteryPercentage(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun getChargeCounterUah(): Long {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1L
    }
}