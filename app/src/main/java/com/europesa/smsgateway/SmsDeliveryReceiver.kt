package com.europesa.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class SmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsGatewayManager.SMS_SENT_ACTION) return

        val connectionId = intent.getStringExtra(SmsGatewayManager.EXTRA_CONNECTION_ID) ?: return
        val backendJobId = intent.getStringExtra(SmsGatewayManager.EXTRA_BACKEND_JOB_ID) ?: return
        val partIndex = intent.getIntExtra(SmsGatewayManager.EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return

        val outcome = if (resultCode == Activity.RESULT_OK) {
            SmsSendOutcome(success = true)
        } else {
            SmsSendOutcome(success = false, error = smsError(resultCode))
        }

        SmsSendTracker.recordPart(connectionId, backendJobId, partIndex, outcome)
    }

    private fun smsError(resultCode: Int): String =
        when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
            else -> "UNKNOWN_RESULT_CODE_$resultCode"
        }
}
