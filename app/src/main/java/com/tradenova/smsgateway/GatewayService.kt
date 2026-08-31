package com.tradenova.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GatewayService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, "gateway_channel")
            .setContentTitle("SMS Gateway Active")
            .setContentText("Listening for pending messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .build()

        startForeground(1, notification)
        
        // Also enqueue the WorkManager polling job just in case
        SmsWorker.enqueue(this)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            "gateway_channel",
            "Gateway Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(serviceChannel)
    }
}
