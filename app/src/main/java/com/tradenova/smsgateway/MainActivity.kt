package com.tradenova.smsgateway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        // Request permissions
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            1
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConfigScreen(configManager)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ConfigScreen(configManager: ConfigManager) {
        val coroutineScope = rememberCoroutineScope()

        var backendUrl by remember { mutableStateOf("") }
        var deviceId by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        var deviceSecret by remember { mutableStateOf("") }
        var isActive by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            configManager.backendUrlFlow.collect { backendUrl = it ?: "" }
        }
        LaunchedEffect(Unit) {
            configManager.deviceIdFlow.collect { deviceId = it ?: "" }
        }
        LaunchedEffect(Unit) {
            configManager.apiKeyFlow.collect { apiKey = it ?: "" }
        }
        LaunchedEffect(Unit) {
            configManager.deviceSecretFlow.collect { deviceSecret = it ?: "" }
        }
        LaunchedEffect(Unit) {
            configManager.isActiveFlow.collect { isActive = it }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "SMS Gateway Configuration", style = MaterialTheme.typography.headlineMedium)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("Backend URL") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text("Device ID") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = deviceSecret,
                onValueChange = { deviceSecret = it },
                label = { Text("Device Secret (HMAC signing key)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = isActive, onCheckedChange = { isActive = it })
                Text("Gateway Active")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        configManager.saveConfig(backendUrl, deviceId, apiKey, deviceSecret, isActive)

                        if (isActive) {
                            val intent = Intent(this@MainActivity, GatewayService::class.java)
                            startForegroundService(intent)

                            // If FCM already handed us a token before this
                            // device was provisioned (e.g. first app launch,
                            // before the fields above were filled in), it
                            // never reached the backend. Retry it now that
                            // we have credentials.
                            val manager = SmsGatewayManager(this@MainActivity)
                            configManager.fcmTokenFlow.firstOrNull()?.let { token ->
                                manager.registerFcmToken(token)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Apply")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Disable Battery Optimization (REQUIRED)")
            }
        }
    }
}
