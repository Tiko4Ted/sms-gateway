package com.europesa.smsgateway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var gatewayManager: SmsGatewayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)
        gatewayManager = SmsGatewayManager(this)

        val permissions = buildList {
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            lifecycleScope.launch {
                gatewayManager.registerFcmToken(token)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GatewayScreen(configManager, gatewayManager)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GatewayScreen(configManager: ConfigManager, gatewayManager: SmsGatewayManager) {
        val coroutineScope = rememberCoroutineScope()
        val connections by configManager.connectionsFlow.collectAsState(initial = emptyList())
        val isActive by configManager.isActiveFlow.collectAsState(initial = false)
        val jobLog by configManager.jobLogFlow.collectAsState(initial = emptyList())
        val fcmToken by configManager.fcmTokenFlow.collectAsState(initial = null)

        var showForm by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<BackendConnection?>(null) }
        var displayName by remember { mutableStateOf("") }
        var baseUrl by remember { mutableStateOf("") }
        var deviceId by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        var deviceSecret by remember { mutableStateOf("") }
        var enabled by remember { mutableStateOf(true) }
        var subscriptionId by remember { mutableStateOf("") }
        var formError by remember { mutableStateOf<String?>(null) }

        fun editConnection(connection: BackendConnection?) {
            editing = connection
            displayName = connection?.displayName.orEmpty()
            baseUrl = connection?.baseUrl.orEmpty()
            deviceId = connection?.deviceId.orEmpty()
            apiKey = connection?.apiKey().orEmpty()
            deviceSecret = connection?.deviceSecret().orEmpty()
            enabled = connection?.enabled ?: true
            subscriptionId = connection?.selectedSubscriptionId?.toString().orEmpty()
            formError = null
            showForm = true
        }

        fun preset(name: String, url: String) {
            editing = null
            displayName = name
            baseUrl = url
            deviceId = ""
            apiKey = ""
            deviceSecret = ""
            enabled = true
            subscriptionId = ""
            formError = null
            showForm = true
        }

        LaunchedEffect(isActive) {
            if (isActive) {
                startForegroundService(Intent(this@MainActivity, GatewayService::class.java))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Euro Pesa SMS Gateway", style = MaterialTheme.typography.headlineMedium)
                Text("Private sender for any backend using the shared SMS gateway endpoints.")
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Gateway active", style = MaterialTheme.typography.titleMedium)
                                Text(if (isActive) "Foreground service is running" else "Gateway is paused")
                            }
                            Switch(
                                checked = isActive,
                                onCheckedChange = { active ->
                                    coroutineScope.launch {
                                        configManager.setActive(active)
                                        if (active) {
                                            startForegroundService(Intent(this@MainActivity, GatewayService::class.java))
                                            gatewayManager.registerFcmTokenWithEnabledConnections(fcmToken)
                                            gatewayManager.syncPendingJobs()
                                        } else {
                                            stopService(Intent(this@MainActivity, GatewayService::class.java))
                                        }
                                    }
                                }
                            )
                        }
                        Text("Health: ${gatewayManager.getDeviceHealth().battery_pct}% battery, ${gatewayManager.getDeviceHealth().network_type}, ${if (gatewayManager.getDeviceHealth().sim_present) "SIM ready" else "SIM not ready"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        gatewayManager.registerFcmTokenWithEnabledConnections(fcmToken)
                                        gatewayManager.syncPendingJobs()
                                    }
                                }
                            ) {
                                Text("Sync now")
                            }
                            OutlinedButton(
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    })
                                }
                            ) {
                                Text("Battery settings")
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editConnection(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add connection")
                    }
                    OutlinedButton(
                        onClick = { preset("Tradenova", "https://tradenovadigital.com/") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Tradenova")
                    }
                    OutlinedButton(
                        onClick = { preset("Nexamarket", "https://nexamarketdigital.com/") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Nexamarket")
                    }
                }
            }

            if (showForm) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (editing == null) "Add connection" else "Edit connection", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Display name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("Base URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = deviceId,
                                onValueChange = { deviceId = it },
                                label = { Text("Device ID") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = deviceSecret,
                                onValueChange = { deviceSecret = it },
                                label = { Text("Device Secret") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = subscriptionId,
                                onValueChange = { subscriptionId = it },
                                label = { Text("SIM subscription ID, optional") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                                Text("Enabled")
                            }
                            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val parsedSubscription = subscriptionId.toIntOrNull()
                                        formError = when {
                                            displayName.isBlank() -> "Display name is required."
                                            baseUrl.isBlank() -> "Base URL is required."
                                            configManager.validateBaseUrl(baseUrl) != null -> configManager.validateBaseUrl(baseUrl)
                                            deviceId.isBlank() -> "Device ID is required."
                                            apiKey.isBlank() -> "API key is required."
                                            deviceSecret.isBlank() -> "Device secret is required."
                                            subscriptionId.isNotBlank() && parsedSubscription == null -> "SIM subscription ID must be a number."
                                            else -> null
                                        }
                                        if (formError == null) {
                                            coroutineScope.launch {
                                                val saved = configManager.saveConnection(
                                                    ConnectionDraft(
                                                        id = editing?.id,
                                                        displayName = displayName,
                                                        baseUrl = baseUrl,
                                                        deviceId = deviceId,
                                                        apiKey = apiKey,
                                                        deviceSecret = deviceSecret,
                                                        enabled = enabled,
                                                        selectedSubscriptionId = parsedSubscription
                                                    )
                                                )
                                                gatewayManager.registerFcmTokenWithEnabledConnections(fcmToken)
                                                if (isActive && saved.enabled) gatewayManager.syncPendingJobs(saved.id)
                                                showForm = false
                                            }
                                        }
                                    }
                                ) {
                                    Text("Save")
                                }
                                OutlinedButton(onClick = { showForm = false }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }

            item {
                Text("Connections", style = MaterialTheme.typography.titleLarge)
            }

            if (connections.isEmpty()) {
                item { Text("No backend connections configured.") }
            } else {
                items(connections, key = { it.id }) { connection ->
                    ConnectionCard(
                        connection = connection,
                        simStatus = gatewayManager.simStatusText(connection),
                        onToggle = { checked ->
                            coroutineScope.launch {
                                configManager.updateConnectionEnabled(connection.id, checked)
                                if (checked) gatewayManager.registerFcmTokenWithEnabledConnections(fcmToken)
                            }
                        },
                        onSync = { coroutineScope.launch { gatewayManager.syncPendingJobs(connection.id) } },
                        onEdit = { editConnection(connection) },
                        onDelete = { coroutineScope.launch { configManager.deleteConnection(connection.id) } }
                    )
                }
            }

            item {
                Divider()
                Text("Recent local job log", style = MaterialTheme.typography.titleLarge)
            }

            if (jobLog.isEmpty()) {
                item { Text("No local jobs sent yet.") }
            } else {
                items(jobLog, key = { "${it.connectionId}-${it.backendJobId}-${it.timestamp}" }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${entry.platform} - ${entry.status}", style = MaterialTheme.typography.titleSmall)
                            Text("${entry.recipientMasked} - ${entry.timestamp}")
                            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun ConnectionCard(
        connection: BackendConnection,
        simStatus: String,
        onToggle: (Boolean) -> Unit,
        onSync: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(connection.baseUrl)
                    }
                    Switch(checked = connection.enabled, onCheckedChange = onToggle)
                }
                Text("Device: ${connection.deviceId}")
                Text("Last sync: ${connection.lastSyncTime ?: "never"}")
                Text("FCM: ${if (connection.fcmRegistered) "registered" else "not registered"}")
                Text("Pending: ${connection.pendingCount?.toString() ?: "unknown"}  Sent: ${connection.sentCount}  Failed: ${connection.failedCount}")
                Text("SIM: $simStatus")
                connection.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSync) { Text("Sync") }
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}
