package au.com.tbmcgregor.bwparker.familyguard.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodically re-evaluates schedule rules. 15 minutes is WorkManager's minimum periodic
 * interval, so rule transitions can lag up to that long -- acceptable for bedtime/school-hours
 * style windows, not for second-precision enforcement.
 */
class ScheduleEnforcementWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        ScheduleEngine(applicationContext).applyNow()
        Result.success()
    } catch (error: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "schedule_enforcement"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleEnforcementWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
