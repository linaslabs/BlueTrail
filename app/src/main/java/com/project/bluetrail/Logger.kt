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
    private val logFileName = "bluetrail_log.csv"
    private val logFile: File = File(context.filesDir, logFileName)

    init {
        // Create file if it doesn't exist
        if (!logFile.exists()){
            writeHeader()
        }
    }

    /**
     * Appends the header to the CSV logger file
     */
    private fun writeHeader() {
        val header = "timestamp_iso,event,device,details,screen_state,battery_pct,charge_counter_uah\n"
        logFile.writeText(header)
    }

    /**
     * Appends a single row to the CSV file with event information
     */
    @Synchronized
    fun log(event: String, device: String = "N/A", details: String = "") {
        val timestamp = getIsoTimestamp()
        val screenState = getScreenState()
        val batteryPct = getBatteryPercentage()
        val chargeCounter = getChargeCounterUah()

        // Ensure commas and new lines don't disrupt CSV format
        val sanitisedDevice = device.replace(",", ";").replace("\n", " ")
        val sanitisedDetails = details.replace(",", ";").replace("\n", " ")

        val logEntry = "$timestamp,$event,$sanitisedDevice,$sanitisedDetails,$screenState,$batteryPct,$chargeCounter\n"

        FileWriter(logFile, true).use { writer ->
            writer.append(logEntry)
        }
    }

    /**
     * Reads the entire log file as text so we can display it on the screen
     */
    fun readLog(): String {
        return if (logFile.exists()) {
            logFile.readText()
        } else {
            "Log file is empty."
        }
    }

    /**
     * Deletes the log file and resets header
     */
    fun clearLog() {
        if (logFile.exists()) {
            logFile.delete()
        }
        writeHeader()
    }

    /**
     * Creates an Intent to export the CSV file to another place
     */
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

    // Private helpers for system diagnostics

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
        // Ask for the last battery intent broadcasted by the OS
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