package com.europesa.smsgateway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        SmsGatewayManager(applicationContext).syncPendingJobs()
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<SmsWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "EuroPesaSmsGatewayFallbackSync",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
