package com.europesa.smsgateway

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmPushReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val requestedConnection = data["connection_id"]
            ?: data["platform"]
            ?: data["display_name"]

        CoroutineScope(Dispatchers.IO).launch {
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
