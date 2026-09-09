package com.jadegenesis.mobile.night

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class NightCycleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val run = runCatching {
            NightCycleEngine(applicationContext).runOnce()
        }.getOrElse {
            return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

        return when (run.status) {
            NightCycleStatus.FAILED -> {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            NightCycleStatus.SKIPPED,
            NightCycleStatus.SUCCESS,
            NightCycleStatus.PARTIAL -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC_WORK = "jade-night-cycle-periodic-v1"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val TARGET_HOUR = 2
        private const val TARGET_MINUTE = 30

        fun schedule(context: Context) {
            val appContext = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()
            val periodic = PeriodicWorkRequestBuilder<NightCycleWorker>(
                24,
                TimeUnit.HOURS
            )
                .setInitialDelay(initialDelayMs())
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }

        internal fun initialDelayMs(now: ZonedDateTime = ZonedDateTime.now()): Long {
            var target = now
                .withHour(TARGET_HOUR)
                .withMinute(TARGET_MINUTE)
                .withSecond(0)
                .withNano(0)
            if (!target.isAfter(now)) {
                target = target.plusDays(1)
            }
            return Duration.between(now, target).toMillis().coerceAtLeast(0L)
        }
    }
}
