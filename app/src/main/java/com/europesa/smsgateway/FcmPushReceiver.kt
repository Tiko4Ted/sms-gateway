package com.europesa.smsgateway

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class FcmPushReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        if (data["event"] != "sms_pending") return

        val requestedConnection = data["connection_id"]
            ?: data["platform"]
            ?: data["display_name"]

        SmsWorker.enqueueImmediate(applicationContext, requestedConnection)
        CoroutineScope(Dispatchers.IO).launch {
            val isActive = ConfigManager(applicationContext).isActiveFlow.firstOrNull() ?: false
            if (isActive) {
                runCatching {
                    startForegroundService(Intent(applicationContext, GatewayService::class.java))
                }
            }
            SmsGatewayManager(applicationContext).syncPendingJobs(requestedConnection)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            SmsGatewayManager(applicationContext).registerFcmToken(token)
        }
    }
}
