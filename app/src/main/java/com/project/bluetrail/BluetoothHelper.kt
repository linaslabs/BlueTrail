package com.project.bluetrail

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

data class PairedDeviceInfo(
    val name: String,
    val address: String,
    val classCodeHex: String,
    val rawClassInt: Int
)

class BluetoothHelper(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Queries all paired (bonded) devices on the phone
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

    /**
     * Checks which devices are actively connected right now across A2DP and Headset profiles
     */
    @SuppressLint("MissingPermission")
    fun getCurrentlyConnectedDevices(): List<BluetoothDevice> {
        if (!isBluetoothEnabled()) return emptyList()

        val connectedA2dp = bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP)
        val connectedHeadset = bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET)

        // Merge both profile lists and eliminate duplicates by MAC address
        return (connectedA2dp + connectedHeadset).distinctBy { it.address }
    }

    /**
     * Checks which devices are currently connected right now
     * Covers all bluetooth devices (A2DP, HEADSET, and GATT (BLE))
     * GATT can be done by using the bluetooth manager and ".getConnectedDevices"
     * A2DP and HEADSET have to be done by using the profile proxies
     */
    @SuppressLint("MissingPermission")
    fun checkAndLogConnectedDevices(logger: Logger, trigger: String = "STARTUP_CHECK") {
        if (!isBluetoothEnabled()) {
            logger.log(event = trigger, device = "System", details = "Bluetooth is currently OFF")
            return
        }

        val loggedAddresses = mutableSetOf<String>()

        // GATT Checks
        try {
            val gattDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            gattDevices.forEach { device ->
                if (loggedAddresses.add(device.address)) {
                    logger.log(
                        event = "CONNECTED_NOW",
                        device = device.name ?: "Unknown",
                        details = "Trigger: $trigger; MAC: ${device.address}"
                    )
                }
            }
        } catch (_: Exception) {
            // Safe fallback if phone restricts GATT
        }

        // Helper function to query Bluetooth profiles
        fun queryProfile(profileType: Int) {
            bluetoothAdapter?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        proxy.connectedDevices.forEach { device ->
                            if (loggedAddresses.add(device.address)) {
                                logger.log(
                                    event = "CONNECTED_NOW",
                                    device = device.name ?: "Unknown",
                                    details = "Trigger: $trigger; MAC: ${device.address}"
                                )
                            }
                        }

                        bluetoothAdapter.closeProfileProxy(profile, proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                profileType
            )
        }

        // A2DP checks (catches devices connected to music/media playback)
        queryProfile(BluetoothProfile.A2DP)

        // HEADSET checks (catches devices connected to calls, microphone)
        queryProfile(BluetoothProfile.HEADSET)
    }
}