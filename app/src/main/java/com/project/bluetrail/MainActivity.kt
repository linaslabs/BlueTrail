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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val logger by lazy { Logger(applicationContext) }
    private val btHelper by lazy { BluetoothHelper(applicationContext) }
    private val locationHelper by lazy { LocationHelper(applicationContext) }

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
                        locationHelper = locationHelper,
                        onStartService = { sendServiceAction(TrackingService.ACTION_START_SERVICE, isForegroundStart = true) },
                        onStopService = { sendServiceAction(TrackingService.ACTION_STOP_SERVICE) },
                        onStartPolicyTrail = { sendServiceAction(TrackingService.ACTION_START_POLICY_TRAIL, isForegroundStart = true) },
                        onStopPolicyTrail = { sendServiceAction(TrackingService.ACTION_STOP_POLICY_TRAIL) },
                        onPolicyChanged = { sendServiceAction(TrackingService.ACTION_APPLY_POLICY_CHANGE) },
                        onLogChargeTick = { sendServiceAction(TrackingService.ACTION_TRIGGER_CHARGE_TICK) },
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

    private fun sendServiceAction(actionStr: String, isForegroundStart: Boolean = false) {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = actionStr
        }
        if (isForegroundStart) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
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
    locationHelper: LocationHelper,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onStartPolicyTrail: () -> Unit,
    onStopPolicyTrail: () -> Unit,
    onPolicyChanged: () -> Unit,
    onLogChargeTick: () -> Unit,
    onMarkWithLabel: (String) -> Unit,
    onExportClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logContent by remember { mutableStateOf(logger.readLog()) }
    var testTagInput by remember { mutableStateOf("T24-PolicyA-Start") }
    var currentPolicy by remember { mutableStateOf(locationHelper.currentPolicy) }

    // Dialog state for selecting tracked devices
    var showDeviceDialog by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<PairedDeviceInfo>>(emptyList()) }
    var trackedCount by remember { mutableIntStateOf(btHelper.getTrackedAddresses().size) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val btGranted = perms[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val fineLocationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (btGranted && notifGranted && fineLocationGranted) {
            onStartService()
            Toast.makeText(context, "Monitoring started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "All permissions are required for background testing", Toast.LENGTH_LONG).show()
        }
    }

    fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isEmpty()) {
            onStartService()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("Select Tracked Devices") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (pairedDevices.isEmpty()) {
                        Text("No paired devices found. Turn Bluetooth ON and grant permissions.")
                    } else {
                        pairedDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val nowTracked = btHelper.toggleDeviceTracked(device.address)
                                        pairedDevices = btHelper.getPairedDevices()
                                        trackedCount = btHelper.getTrackedAddresses().size
                                        logger.log(
                                            event = "TRACK_TOGGLE",
                                            device = device.name,
                                            details = "MAC: ${device.address}; Tracked: $nowTracked"
                                        )
                                        logContent = logger.readLog()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = device.isTracked,
                                    onCheckedChange = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${device.address} (${device.classCodeHex})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "BlueTrail - Increment 3 (CP5)",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Service Master Controls
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

        Spacer(modifier = Modifier.height(6.dp))

        // Tracked Device Dialog Trigger (Leave at 0 selected during T24 battery runs!)
        Button(
            onClick = {
                if (!btHelper.isBluetoothEnabled()) {
                    Toast.makeText(context, "Turn on Bluetooth first", Toast.LENGTH_SHORT).show()
                } else {
                    pairedDevices = btHelper.getPairedDevices()
                    showDeviceDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Tracked Devices ($trackedCount selected)")
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Policy Switcher and Trail Controls (Stage 1 T23/T24)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val next = currentPolicy.next()
                    currentPolicy = next
                    locationHelper.currentPolicy = next
                    onPolicyChanged()
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(1.3f)
            ) {
                Text(currentPolicy.displayName, fontSize = 11.sp)
            }

            Button(
                onClick = {
                    onStartPolicyTrail()
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(0.85f)
            ) {
                Text("Start Trail", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = {
                    onStopPolicyTrail()
                    logContent = logger.readLog()
                },
                modifier = Modifier.weight(0.85f)
            ) {
                Text("Stop Trail", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // On-demand Fuel Gauge Checkpoint Button for T24
        Button(
            onClick = {
                onLogChargeTick()
                Toast.makeText(context, "Fuel gauge checkpoint logged", Toast.LENGTH_SHORT).show()
                logContent = logger.readLog()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Log Charge Checkpoint (µAh)")
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = testTagInput,
            onValueChange = { testTagInput = it },
            label = { Text("Scenario Tag (e.g. T24-PolicyA-Start)") },
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
                Text("Mark ($testTagInput)", fontSize = 11.sp)
            }

            Button(
                onClick = onExportClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Export CSV", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Log Output (bluetrail_log.csv):",
            style = MaterialTheme.typography.labelLarge
        )

        Spacer(modifier = Modifier.height(4.dp))

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
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}