package com.europesa.smsgateway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class SmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        SmsGatewayManager(applicationContext).syncPendingJobs(inputData.getString(KEY_CONNECTION_ID))
        return Result.success()
    }

    companion object {
        private const val KEY_CONNECTION_ID = "connection_id"

        fun enqueue(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<SmsWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "EuroPesaSmsGatewayFallbackSync",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun enqueueImmediate(context: Context, connectionId: String? = null) {
            val inputData = if (connectionId.isNullOrBlank()) {
                Data.EMPTY
            } else {
                workDataOf(KEY_CONNECTION_ID to connectionId)
            }
            val workRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val workName = "EuroPesaSmsGatewayImmediateSync:${connectionId ?: "all"}"
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
