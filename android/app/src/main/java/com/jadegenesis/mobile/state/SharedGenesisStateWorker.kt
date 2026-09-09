package com.jadegenesis.mobile.state

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SharedGenesisStateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        SharedGenesisStateBootstrapper(applicationContext).syncCurrentState()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = {
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    )

    companion object {
        private const val INITIAL_WORK = "jade-shared-state-initial-v1"
        private const val PERIODIC_WORK = "jade-shared-state-periodic-v1"
        private const val MAX_RETRY_ATTEMPTS = 6

        fun schedule(context: Context) {
            val appContext = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val initial = OneTimeWorkRequestBuilder<SharedGenesisStateWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()
            val periodic = PeriodicWorkRequestBuilder<SharedGenesisStateWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                INITIAL_WORK,
                ExistingWorkPolicy.KEEP,
                initial
            )
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }
    }
}
