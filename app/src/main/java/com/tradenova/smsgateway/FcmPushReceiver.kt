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
        Log.d("SmsGateway", "FCM Token: $token")
        // We could send this token to the backend, but the spec says FCM data message
        // triggers all gateways (topic) or specific gateway. Since device provisioning 
        // implies the server needs the token to address this device specifically, 
        // you would normally send it via an API call here.
    }
}
