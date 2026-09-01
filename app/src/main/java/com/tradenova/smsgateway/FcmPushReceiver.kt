package com.tradenova.smsgateway

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmPushReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("SmsGateway", "FCM Message Received")
        
        // When FCM wakes the device, we sync pending jobs
        CoroutineScope(Dispatchers.IO).launch {
            val manager = SmsGatewayManager(applicationContext)
            manager.syncPendingJobs()
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("SmsGateway", "FCM Token refreshed, registering with backend")

        // Without this call the backend has no way to address this specific
        // device with a push, so it silently falls back to the slow
        // periodic poll - this used to be a no-op TODO.
        CoroutineScope(Dispatchers.IO).launch {
            val manager = SmsGatewayManager(applicationContext)
            manager.registerFcmToken(token)
        }
    }
}
