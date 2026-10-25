package com.project.bluetrail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val logger by lazy { Logger(applicationContext) }
    private val btHelper by lazy { BluetoothHelper(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(event = "APP_LAUNCH", details = "MainActivity created")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        logger = logger,
                        btHelper = btHelper,
                        onExportClick = {
                            val intent = logger.getExportIntent()
                            startActivity(android.content.Intent.createChooser(intent, "Export BlueTrail Log"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    logger: Logger,
    btHelper: BluetoothHelper,
    onExportClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logContent by remember { mutableStateOf(logger.readLog()) }

    // Logic to query paired devices and append each into the logger
    fun fetchAndLogDevices() {
        if (!btHelper.isBluetoothEnabled()) {
            Toast.makeText(context, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val devices = btHelper.getPairedDevices()
        if (devices.isEmpty()) {
            logger.log(event = "PAIRED_QUERY", details = "No paired devices found")
        } else {
            devices.forEach { device ->
                val details = "MAC: ${device.address}; ClassCode: ${device.classCodeHex} (${device.rawClassInt})"
                logger.log(event = "PAIRED_DEVICE", device = device.name, details = details)
            }
        }
        logContent = logger.readLog()
    }

    // Permission launcher contract for runtime permission prompt
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            fetchAndLogDevices()
        } else {
            Toast.makeText(context, "Bluetooth permission required to list devices", Toast.LENGTH_LONG).show()
            logger.log(event = "PERMISSION_DENIED", details = "BLUETOOTH_CONNECT was rejected")
            logContent = logger.readLog()
        }
    }

    fun onScanPairedClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                fetchAndLogDevices()
            } else {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android 11 and lower do not require BLUETOOTH_CONNECT
            fetchAndLogDevices()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "BlueTrail - Test App",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        // New CP2 Button: Scan Paired Devices
        Button(
            onClick = { onScanPairedClick() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("List Paired Devices")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    logger.log(event = "MANUAL_TEST", details = "User pressed test button")
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Log Event")
            }

            Button(
                onClick = onExportClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Export CSV")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { logContent = logger.readLog() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Refresh")
            }

            OutlinedButton(
                onClick = {
                    logger.clearLog()
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Log Output (bluetrail_log.csv):",
            style = MaterialTheme.typography.labelLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = logContent,
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}