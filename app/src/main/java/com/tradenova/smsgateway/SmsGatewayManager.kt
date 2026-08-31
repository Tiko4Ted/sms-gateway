package com.tradenova.smsgateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

class SmsGatewayManager(private val context: Context) {
    private val configManager = ConfigManager(context)
    
    private suspend fun getApi(): BackendApi? {
        val baseUrl = configManager.backendUrlFlow.firstOrNull() ?: return null
        if (baseUrl.isBlank()) return null

        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }

    suspend fun syncPendingJobs() {
        val isActive = configManager.isActiveFlow.firstOrNull() ?: false
        if (!isActive) return

        val deviceId = configManager.deviceIdFlow.firstOrNull() ?: return
        val apiKey = configManager.apiKeyFlow.firstOrNull() ?: return
        if (deviceId.isBlank() || apiKey.isBlank()) return

        val api = getApi() ?: return

        try {
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature("", apiKey) // For GET, body is empty
            val authHeader = "Bearer $apiKey" // Using API key as both bearer and secret for simplicity, as per spec "Authorization: Bearer <device_api_key>"

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

    private fun sendSms(job: SmsJob) {
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(job.message)
            
            // We use requestCode as the Job ID cast to int, but it might truncate.
            // Better to pass job_id in intent extras.
            val sentIntents = parts.map {
                val intent = Intent(context, SmsDeliveryReceiver::class.java).apply {
                    action = "com.tradenova.smsgateway.SMS_SENT_ACTION"
                    putExtra("job_id", job.id)
                }
                PendingIntent.getBroadcast(
                    context, 
                    job.id.toInt() + it.hashCode(), 
                    intent, 
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            
            smsManager.sendMultipartTextMessage(job.recipient, null, parts, ArrayList(sentIntents), null)
        } catch (e: Exception) {
            Log.e("SmsGateway", "Failed to send SMS", e)
            // Ideally we report failure back immediately here
        }
    }

    suspend fun reportStatus(jobId: Long, isSuccess: Boolean, error: String?) {
        val deviceId = configManager.deviceIdFlow.firstOrNull() ?: return
        val apiKey = configManager.apiKeyFlow.firstOrNull() ?: return
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

            // Simplistic JSON body for signature (in production, use the exact raw bytes of the body)
            val bodyJson = com.google.gson.Gson().toJson(request)
            val timestamp = System.currentTimeMillis()
            val signature = generateSignature(bodyJson, apiKey)
            val authHeader = "Bearer $apiKey"

            api.reportStatus(authHeader, signature, timestamp, request)
        } catch (e: Exception) {
            Log.e("SmsGateway", "Error reporting status", e)
        }
    }

    private fun generateSignature(body: String, secret: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")
        hmac.init(secretKey)
        val hash = hmac.doFinal(body.toByteArray(UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getNetworkType(): String {
        // Simplified for this example
        return "UNKNOWN"
    }

    private fun isSimPresent(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return tm.simState == TelephonyManager.SIM_STATE_READY
    }
}
