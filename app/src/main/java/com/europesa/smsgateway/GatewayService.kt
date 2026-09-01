package com.europesa.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncLoopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        SmsWorker.enqueue(this)
        if (syncLoopJob?.isActive != true) {
            syncLoopJob = serviceScope.launch {
                runActiveSyncLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runActiveSyncLoop() {
        val configManager = ConfigManager(applicationContext)
        val manager = SmsGatewayManager(applicationContext)

        while (serviceScope.isActive) {
            val isGatewayActive = configManager.isActiveFlow.firstOrNull() ?: false
            if (!isGatewayActive) {
                stopSelf()
                return
            }

            val nextPollSeconds = manager.syncPendingJobs()
            delay(nextPollSeconds.coerceIn(5, 900) * 1000L)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, "gateway_channel")
            .setContentTitle("Euro Pesa SMS Gateway active")
            .setContentText("Listening for Tradenova, Nexamarket, and enabled backends")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            "gateway_channel",
            "Gateway service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
    }
}
