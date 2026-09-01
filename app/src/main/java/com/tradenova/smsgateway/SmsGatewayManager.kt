package com.tradenova.smsgateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

class SmsGatewayManager(private val context: Context) {
    private val configManager = ConfigManager(context)

    companion object {
        // Per-process counter for PendingIntent request codes. Combining a
        // truncated Long job id with a part hashCode (the previous approach)
        // is not guaranteed collision-free; a monotonically increasing
        // counter within this process is.
        private val requestCodeCounter = AtomicInteger(0)
    }

    private suspend fun getApi(): BackendApi? {
        val baseUrl = configManager.backendUrlFlow.firstOrNull() ?: return null
        if (baseUrl.isBlank()) return null

        val clientBuilder = OkHttpClient.Builder()
        if (BuildConfig.DEBUG) {
            // Logs full request/response bodies - includes the API key and
            // marketer phone numbers/amounts, so this must never ship in a
            // release build.
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }

    suspend fun syncPendingJobs() {
        val isActive = configManager.isActiveFlow.firstOrNull() ?: false
        if (!isActive) return

        val deviceId = configManager.deviceIdFlow.firstOrNull() ?: return
        val apiKey = configManager.apiKeyFlow.firstOrNull() ?: return
        val deviceSecret = configManager.deviceSecretFlow.firstOrNull() ?: return
        if (deviceId.isBlank() || apiKey.isBlank() || deviceSecret.isBlank()) return

        val api = getApi() ?: return

        try {
            val timestamp = System.currentTimeMillis()
            // GET has no body, but the timestamp is still bound into the
            // signature - otherwise a captured request/signature pair could
            // be replayed indefinitely with a fresh timestamp header.
            val signature = generateSignature(timestamp.toString(), deviceSecret)
            val authHeader = "Bearer $apiKey"

            val response = api.getPendingJobs(authHeader, signature, timestamp)
            if (response.isSuccessful) {
                val jobs = response.body()?.jobs ?: emptyList()
                for (job in jobs) {
                    sendSms(job)
                }
            } else {
                Log.e("SmsGateway", "Failed to fetch jobs: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SmsGateway", "Error syncing jobs", e)
        }
    }

    /** Registers (or re-registers) this device's FCM token with the backend. */
    suspend fun registerFcmToken(token: String) {
        configManager.saveFcmToken(token)

        val deviceId = configManager.deviceIdFlow.firstOrNull() ?: return
        val apiKey = configManager.apiKeyFlow.firstOrNull() ?: return
        val deviceSecret = configManager.deviceSecretFlow.firstOrNull() ?: return
        if (deviceId.isBlank() || apiKey.isBlank() || deviceSecret.isBlank()) {
            // Device isn't provisioned yet - the token is saved locally and
            // will be sent once saveConfig() runs and the caller retries.
            return
        }

        val api = getApi() ?: return
        try {
            val request = RegisterTokenRequest(device_id = deviceId, fcm_token = token)
            val bodyJson = com.google.gson.Gson().toJson(request)
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature("$bodyJson$timestamp", deviceSecret)
            val authHeader = "Bearer $apiKey"

            val response = api.registerToken(authHeader, signature, timestamp, request)
            if (!response.isSuccessful) {
                Log.e("SmsGateway", "Failed to register FCM token: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SmsGateway", "Error registering FCM token", e)
        }
    }

    private fun sendSms(job: SmsJob) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(job.message)

            val sentIntents = parts.map {
                val intent = Intent(context, SmsDeliveryReceiver::class.java).apply {
                    action = "com.tradenova.smsgateway.SMS_SENT_ACTION"
                    putExtra("job_id", job.id)
                }
                PendingIntent.getBroadcast(
                    context,
                    requestCodeCounter.incrementAndGet(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            smsManager.sendMultipartTextMessage(job.recipient, null, parts, ArrayList(sentIntents), null)
        } catch (e: Exception) {
            Log.e("SmsGateway", "Failed to send SMS for job ${job.id}", e)
            // This path never reaches SmsDeliveryReceiver (no broadcast fires
            // for a throw before the SMS is even handed to the radio), so
            // without reporting here the job would sit "claimed" until the
            // server's claim_expires_at reclaims it minutes later.
            reportLocalFailureFireAndForget(job.id, e.message ?: e.javaClass.simpleName)
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun reportLocalFailureFireAndForget(jobId: Long, error: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            reportStatus(jobId, isSuccess = false, error = error)
        }
    }

    suspend fun reportStatus(jobId: Long, isSuccess: Boolean, error: String?) {
        val deviceId = configManager.deviceIdFlow.firstOrNull() ?: return
        val apiKey = configManager.apiKeyFlow.firstOrNull() ?: return
        val deviceSecret = configManager.deviceSecretFlow.firstOrNull() ?: return
        val api = getApi() ?: return

        try {
            val batteryPct = getBatteryPercentage()
            val networkType = getNetworkType()
            val simPresent = isSimPresent()

            val request = StatusUpdateRequest(
                device_id = deviceId,
                results = listOf(
                    JobResult(
                        id = jobId,
                        status = if (isSuccess) "sent" else "failed",
                        error = error,
                        sent_at = if (isSuccess) java.time.Instant.now().toString() else null
                    )
                ),
                device_health = DeviceHealth(batteryPct, networkType, simPresent)
            )

            val bodyJson = com.google.gson.Gson().toJson(request)
            val timestamp = System.currentTimeMillis()
            // Timestamp is folded into the signed payload here too - see
            // syncPendingJobs() for why.
            val signature = generateSignature("$bodyJson$timestamp", deviceSecret)
            val authHeader = "Bearer $apiKey"

            api.reportStatus(authHeader, signature, timestamp, request)
        } catch (e: Exception) {
            Log.e("SmsGateway", "Error reporting status", e)
        }
    }

    private fun generateSignature(payload: String, secret: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")
        hmac.init(secretKey)
        val hash = hmac.doFinal(payload.toByteArray(UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
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
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun isSimPresent(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }
}
