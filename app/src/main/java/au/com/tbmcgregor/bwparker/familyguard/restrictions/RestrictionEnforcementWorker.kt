package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionController
import au.com.tbmcgregor.bwparker.familyguard.tamper.AccessibilityGuardActivity
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import java.util.concurrent.TimeUnit

/**
 * Backup to [au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionEnforcementService].
 * The foreground service re-asserts protections every 5 minutes while its process is alive, but
 * Samsung's aggressive background process/battery management can kill or freeze that process
 * while the app isn't open -- if a restriction gets cleared while that happens, nothing catches
 * it until the app is next opened. WorkManager jobs are scheduled by the OS independently of our
 * process, so this re-asserts everything on a fixed schedule (every 15 minutes, WorkManager's
 * minimum periodic interval) even if the foreground service was killed.
 */
class RestrictionEnforcementWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!ProtectionController(applicationContext).isEnabled()) return Result.success()
        val restrictionsManager = DeviceRestrictionsManager(applicationContext)
        val tamperLogger = TamperEventLogger(applicationContext)
        runCatching { restrictionsManager.detectDriftAndReapply(tamperLogger) }
            .onFailure { Log.w(TAG, "Restriction drift check failed", it) }
        runCatching { AppSuspensionManager(applicationContext).reapplyAll() }
            .onFailure { Log.w(TAG, "Blocked-app reapply failed", it) }
        runCatching { AppUninstallGuard(applicationContext).reapplyAll() }
            .onFailure { Log.w(TAG, "Uninstall-protection reapply failed", it) }
        runCatching {
            AccessibilityGuard.reapplyAllowlist(applicationContext)
            if (!AccessibilityGuard.isEnabled(applicationContext)) {
                tamperLogger.log(
                    type = "ACCESSIBILITY_DISABLED",
                    details = "Accessibility service found off during background check; showing lock screen",
                )
                AccessibilityGuardActivity.launch(applicationContext)
            }
        }.onFailure { Log.w(TAG, "Accessibility guard check failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "RestrictionEnforcementWorker"
        private const val UNIQUE_WORK_NAME = "restriction_enforcement"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RestrictionEnforcementWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
