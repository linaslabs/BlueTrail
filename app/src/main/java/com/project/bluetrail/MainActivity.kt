package com.project.bluetrail

import android.Manifest
import android.content.Intent
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
                        onStartService = { startTrackingService() },
                        onStopService = { stopTrackingService() },
                        onMarkWithLabel = { tag -> sendTaggedMarkIntent(tag) },
                        onExportClick = {
                            val intent = logger.getExportIntent()
                            startActivity(Intent.createChooser(intent, "Export BlueTrail Log"))
                        }
                    )
                }
            }
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_SERVICE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP_SERVICE
        }
        startService(intent)
    }

    private fun sendTaggedMarkIntent(label: String) {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_TAG_MARK
            putExtra(TrackingService.EXTRA_MANUAL_LABEL, label)
        }
        startService(intent)
    }
}

@Composable
fun MainScreen(
    logger: Logger,
    btHelper: BluetoothHelper,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onMarkWithLabel: (String) -> Unit,
    onExportClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logContent by remember { mutableStateOf(logger.readLog()) }
    var testTagInput by remember { mutableStateOf("T03 case-closed") }

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

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val btGranted = perms[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || perms[Manifest.permission.POST_NOTIFICATIONS] ?: false

        if (btGranted && notifGranted) {
            onStartService()
            Toast.makeText(context, "Monitoring started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Bluetooth and Notification permissions required", Toast.LENGTH_LONG).show()
        }
    }

    fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) {
            onStartService()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "BlueTrail - Test App (CP3)",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Service Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { requestPermissionsAndStart() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Monitor")
            }

            Button(
                onClick = {
                    onStopService()
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop Monitor")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { fetchAndLogDevices() },
                modifier = Modifier.weight(1f)
            ) {
                Text("List Paired")
            }

            Button(
                onClick = {
                    btHelper.checkAndLogConnectedDevices(logger, trigger = "MANUAL_CHECK")
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Check Connected")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input field for specific scenarios
        OutlinedTextField(
            value = testTagInput,
            onValueChange = { testTagInput = it },
            label = { Text("Scenario Tag (e.g. T03 case-closed)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onMarkWithLabel(testTagInput)
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Mark ($testTagInput)")
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

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Log Output (bluetrail_log.csv):",
            style = MaterialTheme.typography.labelLarge
        )

        Spacer(modifier = Modifier.height(6.dp))

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
                lineHeight = 14.sp
            )
        }
    }
}