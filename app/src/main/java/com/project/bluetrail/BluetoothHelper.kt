package com.project.bluetrail

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

data class PairedDeviceInfo(
    val name: String,
    val address: String,
    val classCodeHex: String,
    val rawClassInt: Int
)

class BluetoothHelper(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    /**
     * Checks if Bluetooth hardware is available and turned on
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Queries the OS list of paired (aka bonded) Bluetooth devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<PairedDeviceInfo> {
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()

        return bonded.map { device ->
            val deviceClass = device.bluetoothClass
            val classCodeInt = deviceClass?.deviceClass ?: 0
            val classCodeHex = "0x" + Integer.toHexString(classCodeInt).uppercase()

            PairedDeviceInfo(
                name = device.name ?: "Unknown Device",
                address = device.address ?: "00:00:00:00:00:00",
                classCodeHex = classCodeHex,
                rawClassInt = classCodeInt
            )
        }
    }
}