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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        SmsWorker.enqueue(this)
        serviceScope.launch {
            SmsGatewayManager(applicationContext).syncPendingJobs()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
