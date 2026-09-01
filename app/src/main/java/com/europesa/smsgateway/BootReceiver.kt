package com.europesa.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val isActive = runBlocking {
            ConfigManager(context).isActiveFlow.firstOrNull() ?: false
        }

        if (isActive) {
            context.startForegroundService(Intent(context, GatewayService::class.java))
        }
    }
}
