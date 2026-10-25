package com.project.bluetrail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val logger by lazy { Logger(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(event = "APP_LAUNCH", details = "MainActivity created")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoggerScreen(
                        logger = logger,
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
fun LoggerScreen(
    logger: Logger,
    onExportClick: () -> Unit
) {
    // State holding the text displayed on the screen
    var logContent by remember { mutableStateOf(logger.readLog()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "BlueTrail - Test Harness (CP1)",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    logger.log(event = "MANUAL_TEST", details = "User pressed test button")
                    logContent = logger.readLog() // Refresh UI
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

        // Scrollable view showing the raw file content
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