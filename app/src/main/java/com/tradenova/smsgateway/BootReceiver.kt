package com.tradenova.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val configManager = ConfigManager(context)
            // Need runBlocking or GlobalScope for this small check
            val isActive = runBlocking { configManager.isActiveFlow.firstOrNull() ?: false }
            
            if (isActive) {
                val serviceIntent = Intent(context, GatewayService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
