package com.jadegenesis.mobile.screen

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object ScreenCaptureRetention {
    private const val UNIQUE_WORK_NAME = "jade-screen-capture-expiry"
    const val CAPTURE_TTL_MS = 24L * 60L * 60L * 1_000L

    fun schedule(
        context: Context,
        delayMs: Long = CAPTURE_TTL_MS
    ) {
        val request = OneTimeWorkRequestBuilder<ScreenCaptureCleanupWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}

class ScreenCaptureCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        ScreenObserverRepository(applicationContext).purgeExpiredCapture()
        return Result.success()
    }
}
