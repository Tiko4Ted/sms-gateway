package com.europesa.smsgateway

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.net.URI
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class BackendConnection(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val baseUrl: String = "",
    val deviceId: String = "",
    val apiKeyEncrypted: String = "",
    val deviceSecretEncrypted: String = "",
    val enabled: Boolean = true,
    val selectedSubscriptionId: Int? = null,
    val lastSyncTime: String? = null,
    val lastError: String? = null,
    val fcmRegistered: Boolean = false,
    val pendingCount: Int? = null,
    val sentCount: Int = 0,
    val failedCount: Int = 0
) {
    fun apiKey(): String = CryptoUtil.decrypt(apiKeyEncrypted)
    fun deviceSecret(): String = CryptoUtil.decrypt(deviceSecretEncrypted)
    fun isProvisioned(): Boolean =
        displayName.isNotBlank() &&
            baseUrl.isNotBlank() &&
            deviceId.isNotBlank() &&
            apiKey().isNotBlank() &&
            deviceSecret().isNotBlank()
}

data class ConnectionDraft(
    val id: String? = null,
    val displayName: String,
    val baseUrl: String,
    val deviceId: String,
    val apiKey: String,
    val deviceSecret: String,
    val enabled: Boolean,
    val selectedSubscriptionId: Int?
)

data class JobLogEntry(
    val connectionId: String,
    val platform: String,
    val backendJobId: String,
    val recipientMasked: String,
    val status: String,
    val timestamp: String,
    val error: String? = null
)

class ConfigManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        private val CONNECTIONS_JSON = stringPreferencesKey("connections_json")
        private val FCM_TOKEN = stringPreferencesKey("fcm_token")
        private val IS_ACTIVE = booleanPreferencesKey("is_active")
        private val JOB_LOG_JSON = stringPreferencesKey("job_log_json")
        private const val MAX_JOB_LOG_ENTRIES = 50
    }

    val connectionsFlow: Flow<List<BackendConnection>> = context.dataStore.data.map { prefs ->
        decodeConnections(prefs[CONNECTIONS_JSON])
    }
    val fcmTokenFlow: Flow<String?> = context.dataStore.data.map { it[FCM_TOKEN] }
    val isActiveFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_ACTIVE] ?: false }
    val jobLogFlow: Flow<List<JobLogEntry>> = context.dataStore.data.map { prefs ->
        decodeJobLog(prefs[JOB_LOG_JSON])
    }

    suspend fun setActive(isActive: Boolean) {
        context.dataStore.edit { prefs -> prefs[IS_ACTIVE] = isActive }
    }

    suspend fun saveConnection(draft: ConnectionDraft): BackendConnection {
        val normalizedBaseUrl = normalizeBaseUrl(draft.baseUrl)
        val savedConnection = context.dataStore.editAndReturnConnection(draft, normalizedBaseUrl)
        return savedConnection
    }

    suspend fun deleteConnection(connectionId: String) {
        context.dataStore.edit { prefs ->
            val updated = decodeConnections(prefs[CONNECTIONS_JSON])
                .filterNot { it.id == connectionId }
            prefs[CONNECTIONS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun updateConnectionEnabled(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(enabled = enabled) }
    }

    suspend fun updateSyncStatus(
        connectionId: String,
        lastSyncTime: String?,
        lastError: String?,
        pendingCount: Int? = null
    ) {
        updateConnection(connectionId) {
            it.copy(
                lastSyncTime = lastSyncTime ?: it.lastSyncTime,
                lastError = lastError,
                pendingCount = pendingCount ?: it.pendingCount
            )
        }
    }

    suspend fun updateFcmStatus(connectionId: String, registered: Boolean, error: String? = null) {
        updateConnection(connectionId) {
            it.copy(fcmRegistered = registered, lastError = error)
        }
    }

    suspend fun recordJobResult(connection: BackendConnection, job: SmsJob, status: String, error: String?) {
        val now = java.time.Instant.now().toString()
        context.dataStore.edit { prefs ->
            val connections = decodeConnections(prefs[CONNECTIONS_JSON])
            val updatedConnections = connections.map {
                if (it.id == connection.id) {
                    it.copy(
                        sentCount = it.sentCount + if (status == "sent") 1 else 0,
                        failedCount = it.failedCount + if (status == "failed") 1 else 0,
                        lastError = if (status == "failed") error else it.lastError
                    )
                } else {
                    it
                }
            }
            val existingLog = decodeJobLog(prefs[JOB_LOG_JSON])
            val entry = JobLogEntry(
                connectionId = connection.id,
                platform = connection.displayName,
                backendJobId = job.id,
                recipientMasked = maskPhone(job.recipient),
                status = status,
                timestamp = now,
                error = error
            )
            prefs[CONNECTIONS_JSON] = gson.toJson(updatedConnections)
            prefs[JOB_LOG_JSON] = gson.toJson((listOf(entry) + existingLog).take(MAX_JOB_LOG_ENTRIES))
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.dataStore.edit { prefs -> prefs[FCM_TOKEN] = token }
    }

    suspend fun getConnection(connectionId: String): BackendConnection? =
        connectionsFlow.firstOrNull()?.firstOrNull { it.id == connectionId }

    fun validateBaseUrl(url: String, allowHttp: Boolean = BuildConfig.DEBUG): String? {
        val normalized = normalizeBaseUrl(url)
        return try {
            val uri = URI(normalized)
            when {
                uri.host.isNullOrBlank() -> "Base URL must include a host."
                uri.scheme == "https" -> null
                uri.scheme == "http" && allowHttp -> null
                uri.scheme == "http" -> "HTTP is only allowed in debug builds."
                else -> "Base URL must use HTTPS."
            }
        } catch (e: Exception) {
            "Base URL is invalid."
        }
    }

    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private suspend fun DataStore<Preferences>.editAndReturnConnection(
        draft: ConnectionDraft,
        normalizedBaseUrl: String
    ): BackendConnection {
        var saved: BackendConnection? = null
        edit { prefs ->
            val connections = decodeConnections(prefs[CONNECTIONS_JSON])
            val existing = connections.firstOrNull { it.id == draft.id }
            val connection = BackendConnection(
                id = existing?.id ?: draft.id ?: UUID.randomUUID().toString(),
                displayName = draft.displayName.trim(),
                baseUrl = normalizedBaseUrl,
                deviceId = draft.deviceId.trim(),
                apiKeyEncrypted = CryptoUtil.encrypt(draft.apiKey),
                deviceSecretEncrypted = CryptoUtil.encrypt(draft.deviceSecret),
                enabled = draft.enabled,
                selectedSubscriptionId = draft.selectedSubscriptionId,
                lastSyncTime = existing?.lastSyncTime,
                lastError = existing?.lastError,
                fcmRegistered = existing?.fcmRegistered ?: false,
                pendingCount = existing?.pendingCount,
                sentCount = existing?.sentCount ?: 0,
                failedCount = existing?.failedCount ?: 0
            )
            saved = connection
            prefs[CONNECTIONS_JSON] = gson.toJson(
                connections.filterNot { it.id == connection.id } + connection
            )
        }
        return saved!!
    }

    private suspend fun updateConnection(connectionId: String, transform: (BackendConnection) -> BackendConnection) {
        context.dataStore.edit { prefs ->
            val updated = decodeConnections(prefs[CONNECTIONS_JSON]).map {
                if (it.id == connectionId) transform(it) else it
            }
            prefs[CONNECTIONS_JSON] = gson.toJson(updated)
        }
    }

    private fun decodeConnections(json: String?): List<BackendConnection> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<BackendConnection>>() {}.type
            gson.fromJson<List<BackendConnection>>(json, type).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeJobLog(json: String?): List<JobLogEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<JobLogEntry>>() {}.type
            gson.fromJson<List<JobLogEntry>>(json, type).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun maskPhone(phone: String): String {
        val clean = phone.trim()
        if (clean.length <= 6) return "***"
        return "${clean.take(4)}***${clean.takeLast(3)}"
    }
}
