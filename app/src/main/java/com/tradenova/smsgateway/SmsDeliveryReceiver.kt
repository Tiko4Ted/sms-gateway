package com.tradenova.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.tradenova.smsgateway.SMS_SENT_ACTION") {
            val jobId = intent.getLongExtra("job_id", -1)
            if (jobId == -1L) return

            val isSuccess = resultCode == android.app.Activity.RESULT_OK
            val errorStr = if (!isSuccess) {
                when (resultCode) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
                    else -> "UNKNOWN_ERROR_$resultCode"
                }
            } else null

            // Use goAsync() for Coroutine in BroadcastReceiver or just launch in GlobalScope
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val manager = SmsGatewayManager(context)
                    manager.reportStatus(jobId, isSuccess, errorStr)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
