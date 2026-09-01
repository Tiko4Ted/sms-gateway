package com.europesa.smsgateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LocalQueuedSms(
    val connectionId: String,
    val backendJobId: String,
    val idempotencyKey: String
)

data class SmsSendOutcome(
    val success: Boolean,
    val error: String? = null
)

object SmsSendTracker {
    private data class PendingSend(
        val totalParts: Int,
        val results: MutableMap<Int, SmsSendOutcome> = mutableMapOf(),
        val deferred: CompletableDeferred<SmsSendOutcome> = CompletableDeferred()
    )

    private val pending = ConcurrentHashMap<String, PendingSend>()

    fun key(connectionId: String, backendJobId: String): String = "$connectionId::$backendJobId"

    fun register(connectionId: String, backendJobId: String, totalParts: Int): CompletableDeferred<SmsSendOutcome> {
        val send = PendingSend(totalParts = totalParts)
        pending[key(connectionId, backendJobId)] = send
        return send.deferred
    }

    fun recordPart(connectionId: String, backendJobId: String, partIndex: Int, outcome: SmsSendOutcome) {
        val key = key(connectionId, backendJobId)
        val send = pending[key] ?: return
        synchronized(send) {
            send.results[partIndex] = outcome
            if (!outcome.success && !send.deferred.isCompleted) {
                pending.remove(key)
                send.deferred.complete(outcome)
                return
            }
            if (send.results.size >= send.totalParts && !send.deferred.isCompleted) {
                pending.remove(key)
                send.deferred.complete(SmsSendOutcome(success = true))
            }
        }
    }

    fun fail(connectionId: String, backendJobId: String, error: String) {
        val key = key(connectionId, backendJobId)
        pending.remove(key)?.deferred?.complete(SmsSendOutcome(success = false, error = error))
    }
}

class SmsGatewayManager(private val context: Context) {
    private val configManager = ConfigManager(context)
    private val gson = com.google.gson.Gson()

    companion object {
        const val SMS_SENT_ACTION = "com.europesa.smsgateway.SMS_SENT_ACTION"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_BACKEND_JOB_ID = "backend_job_id"
        const val EXTRA_PART_INDEX = "part_index"
        private val requestCodeCounter = AtomicInteger(0)
        private val syncLocks = ConcurrentHashMap<String, Mutex>()
        const val DEFAULT_POLL_INTERVAL_SECONDS = 15
    }

    suspend fun syncPendingJobs(connectionId: String? = null): Int {
        val isActive = configManager.isActiveFlow.firstOrNull() ?: false
        if (!isActive) return DEFAULT_POLL_INTERVAL_SECONDS

        val connections = configManager.connectionsFlow.firstOrNull().orEmpty()
            .filter { it.enabled && it.isProvisioned() }
            .filter { connectionId == null || it.id == connectionId || it.displayName.equals(connectionId, ignoreCase = true) }

        if (connections.isEmpty()) return DEFAULT_POLL_INTERVAL_SECONDS

        var nextPollSeconds = DEFAULT_POLL_INTERVAL_SECONDS
        for (connection in connections) {
            val lock = syncLocks.getOrPut(connection.id) { Mutex() }
            val connectionPollSeconds = lock.withLock {
                syncConnection(connection)
            }
            nextPollSeconds = minOf(nextPollSeconds, connectionPollSeconds)
        }

        return nextPollSeconds.coerceIn(5, 900)
    }

    suspend fun registerFcmToken(token: String) {
        configManager.saveFcmToken(token)
        registerFcmTokenWithEnabledConnections(token)
    }

    suspend fun registerFcmTokenWithEnabledConnections(token: String?) {
        val resolvedToken = token ?: configManager.fcmTokenFlow.firstOrNull()
        if (resolvedToken.isNullOrBlank()) return
        val connections = configManager.connectionsFlow.firstOrNull().orEmpty()
            .filter { it.enabled && it.isProvisioned() }
        for (connection in connections) {
            registerToken(connection, resolvedToken)
        }
    }

    private suspend fun syncConnection(connection: BackendConnection): Int {
        configManager.validateBaseUrl(connection.baseUrl)?.let { error ->
            configManager.updateSyncStatus(connection.id, Instant.now().toString(), error)
            return DEFAULT_POLL_INTERVAL_SECONDS
        }
        val api = createApi(connection) ?: return DEFAULT_POLL_INTERVAL_SECONDS
        try {
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature(timestamp.toString(), connection.deviceSecret())
            refreshHealth(connection, api)
            val response = api.getPendingJobs(authHeader(connection), signature, timestamp)
            val now = Instant.now().toString()

            if (!response.isSuccessful) {
                configManager.updateSyncStatus(
                    connection.id,
                    lastSyncTime = now,
                    lastError = "Pending fetch failed: HTTP ${response.code()}"
                )
                return DEFAULT_POLL_INTERVAL_SECONDS
            }

            val body = response.body()
            val jobs = body?.jobs.orEmpty()
            configManager.updateSyncStatus(
                connection.id,
                lastSyncTime = now,
                lastError = null,
                pendingCount = body?.pending_count ?: jobs.size
            )

            for (job in jobs) {
                sendAndReport(connection, job)
            }

            return (body?.poll_interval_hint_seconds ?: DEFAULT_POLL_INTERVAL_SECONDS).coerceIn(5, 900)
        } catch (e: Exception) {
            configManager.updateSyncStatus(
                connection.id,
                lastSyncTime = Instant.now().toString(),
                lastError = sanitizedError(e)
            )
            return DEFAULT_POLL_INTERVAL_SECONDS
        }
    }

    private suspend fun sendAndReport(connection: BackendConnection, job: SmsJob) {
        val queued = LocalQueuedSms(
            connectionId = connection.id,
            backendJobId = job.id,
            idempotencyKey = job.idempotency_key
        )
        val outcome = sendSmsSequentially(connection, job, queued)
        val status = if (outcome.success) "sent" else "failed"
        reportStatus(connection, job.id, status, outcome.error)
        configManager.recordJobResult(connection, job, status, outcome.error)
    }

    private suspend fun sendSmsSequentially(
        connection: BackendConnection,
        job: SmsJob,
        queued: LocalQueuedSms
    ): SmsSendOutcome = withContext(Dispatchers.Main) {
        try {
            val smsManager = connection.selectedSubscriptionId?.let {
                SmsManager.getSmsManagerForSubscriptionId(it)
            } ?: context.getSystemService(SmsManager::class.java)

            val parts = smsManager.divideMessage(job.message)
            if (parts.isEmpty()) {
                return@withContext SmsSendOutcome(success = false, error = "EMPTY_MESSAGE")
            }

            val result = SmsSendTracker.register(
                queued.connectionId,
                queued.backendJobId,
                parts.size
            )
            val sentIntents = parts.mapIndexed { index, _ ->
                val intent = Intent(context, SmsDeliveryReceiver::class.java).apply {
                    action = SMS_SENT_ACTION
                    putExtra(EXTRA_CONNECTION_ID, queued.connectionId)
                    putExtra(EXTRA_BACKEND_JOB_ID, queued.backendJobId)
                    putExtra(EXTRA_PART_INDEX, index)
                }
                PendingIntent.getBroadcast(
                    context,
                    requestCodeCounter.incrementAndGet(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            smsManager.sendMultipartTextMessage(
                job.recipient,
                null,
                parts,
                ArrayList(sentIntents),
                null
            )
            withTimeoutOrNull(120_000) { result.await() } ?: run {
                SmsSendTracker.fail(queued.connectionId, queued.backendJobId, "SMS_SENT_CALLBACK_TIMEOUT")
                SmsSendOutcome(success = false, error = "SMS_SENT_CALLBACK_TIMEOUT")
            }
        } catch (e: Exception) {
            SmsSendTracker.fail(queued.connectionId, queued.backendJobId, sanitizedError(e))
            SmsSendOutcome(success = false, error = sanitizedError(e))
        }
    }

    private suspend fun reportStatus(
        connection: BackendConnection,
        backendJobId: String,
        status: String,
        error: String?
    ) {
        val api = createApi(connection) ?: return
        try {
            val request = StatusUpdateRequest(
                device_id = connection.deviceId,
                results = listOf(
                    JobResult(
                        id = backendJobId,
                        status = status,
                        error = error,
                        sent_at = if (status == "sent") Instant.now().toString() else null
                    )
                ),
                device_health = getDeviceHealth()
            )
            val bodyJson = gson.toJson(request)
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature("$bodyJson$timestamp", connection.deviceSecret())
            api.reportStatus(authHeader(connection), signature, timestamp, request)
        } catch (e: Exception) {
            configManager.updateSyncStatus(connection.id, null, "Status report failed: ${sanitizedError(e)}")
        }
    }

    private suspend fun refreshHealth(connection: BackendConnection, api: BackendApi) {
        try {
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature(timestamp.toString(), connection.deviceSecret())
            val response = api.health(authHeader(connection), signature, timestamp)
            val pendingCount = response.body()?.pending_count
            if (response.isSuccessful && pendingCount != null) {
                configManager.updateSyncStatus(connection.id, null, null, pendingCount)
            }
        } catch (e: Exception) {
            // Health is useful for the UI, but /pending is the authoritative
            // delivery path and must still run if health is unavailable.
        }
    }

    private suspend fun registerToken(connection: BackendConnection, token: String) {
        configManager.validateBaseUrl(connection.baseUrl)?.let { error ->
            configManager.updateFcmStatus(connection.id, registered = false, error = error)
            return
        }
        val api = createApi(connection) ?: return
        try {
            val request = RegisterTokenRequest(connection.deviceId, token)
            val bodyJson = gson.toJson(request)
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature("$bodyJson$timestamp", connection.deviceSecret())
            val response = api.registerToken(authHeader(connection), signature, timestamp, request)
            if (response.isSuccessful) {
                configManager.updateFcmStatus(connection.id, registered = true, error = null)
            } else {
                configManager.updateFcmStatus(connection.id, registered = false, error = "FCM register failed: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            configManager.updateFcmStatus(connection.id, registered = false, error = "FCM register failed: ${sanitizedError(e)}")
        }
    }

    private fun createApi(connection: BackendConnection): BackendApi? {
        val error = configManager.validateBaseUrl(connection.baseUrl)
        if (error != null) return null

        val clientBuilder = OkHttpClient.Builder()
        if (BuildConfig.DEBUG) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }

        return Retrofit.Builder()
            .baseUrl(configManager.normalizeBaseUrl(connection.baseUrl))
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }

    private fun authHeader(connection: BackendConnection): String = "Bearer ${connection.apiKey()}"

    private fun generateSignature(payload: String, secret: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")
        hmac.init(secretKey)
        val hash = hmac.doFinal(payload.toByteArray(UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getDeviceHealth(): DeviceHealth {
        return DeviceHealth(
            battery_pct = getBatteryPercentage(),
            network_type = getNetworkType(),
            sim_present = isSimPresent()
        )
    }

    fun simStatusText(connection: BackendConnection? = null): String {
        val selected = connection?.selectedSubscriptionId?.let { "Selected SIM subscription: $it" } ?: "Default SIM"
        val present = if (isSimPresent()) "SIM ready" else "SIM not ready"
        return "$selected; $present; ${getNetworkType()}"
    }

    private fun getBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "NONE"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "NONE"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "OTHER"
            else -> "OTHER"
        }
    }

    private fun isSimPresent(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun sanitizedError(error: Throwable): String =
        error.message?.take(160) ?: error.javaClass.simpleName
}
