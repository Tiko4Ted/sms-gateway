package com.tradenova.smsgateway

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

data class PendingJobsResponse(
    val jobs: List<SmsJob>,
    val poll_interval_hint_seconds: Int
)

data class SmsJob(
    val id: Long,
    val recipient: String,
    val message: String,
    val idempotency_key: String
)

data class StatusUpdateRequest(
    val device_id: String,
    val results: List<JobResult>,
    val device_health: DeviceHealth
)

data class JobResult(
    val id: Long,
    val status: String, // "sent" or "failed"
    val error: String? = null,
    val sent_at: String? = null // ISO8601 string
)

data class DeviceHealth(
    val battery_pct: Int,
    val network_type: String,
    val sim_present: Boolean
)

data class RegisterTokenRequest(
    val device_id: String,
    val fcm_token: String
)

interface BackendApi {
    @GET("/api/sms-gateway/pending")
    suspend fun getPendingJobs(
        @Header("Authorization") auth: String,
        @Header("X-Signature") signature: String,
        @Header("X-Timestamp") timestamp: Long
    ): Response<PendingJobsResponse>

    @POST("/api/sms-gateway/status")
    suspend fun reportStatus(
        @Header("Authorization") auth: String,
        @Header("X-Signature") signature: String,
        @Header("X-Timestamp") timestamp: Long,
        @Body request: StatusUpdateRequest
    ): Response<Unit>

    // Registers this device's current FCM token with the backend so the
    // server can address a push at this specific device rather than relying
    // solely on the periodic fallback poll.
    @POST("/api/sms-gateway/register-token")
    suspend fun registerToken(
        @Header("Authorization") auth: String,
        @Header("X-Signature") signature: String,
        @Header("X-Timestamp") timestamp: Long,
        @Body request: RegisterTokenRequest
    ): Response<Unit>
}
