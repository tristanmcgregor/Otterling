package app.otterling.restrictions

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.otterling.content.AppSuspensionManager
import app.otterling.content.VpnFilterManager
import app.otterling.monitoring.ProtectionController
import app.otterling.tamper.AccessibilityGuardActivity
import app.otterling.tamper.TamperEventLogger
import java.util.concurrent.TimeUnit

/**
 * Backup to [app.otterling.monitoring.ProtectionEnforcementService].
 * The foreground service re-asserts protections (including the content-filter VPN's always-on
 * registration, via [app.otterling.content.VpnFilterManager.ensureActive]) every 5/60 seconds
 * while its process is alive, but Samsung's aggressive background process/battery management can
 * kill or freeze that process while the app isn't open -- if a restriction (or the VPN
 * registration) gets cleared while that happens, nothing catches it until the app is next opened.
 * WorkManager jobs are scheduled by the OS independently of our process, so this re-asserts
 * everything on a fixed schedule (every 15 minutes, WorkManager's minimum periodic interval) even
 * if the foreground service was killed.
 */
class RestrictionEnforcementWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!ProtectionController(applicationContext).isEnabled()) return Result.success()
        val restrictionsManager = DeviceRestrictionsManager(applicationContext)
        val tamperLogger = TamperEventLogger(applicationContext)
        // Same backup rationale as everything below: VpnFilterManager.ensureActive() otherwise only
        // runs from ProtectionEnforcementService's 60s in-process loop, which Samsung's background
        // management can kill/freeze just like the restriction/suspension/uninstall-guard passes
        // below -- see this class's own doc comment. ensureActive() is independently idempotent
        // (re-registers always-on VPN, reinstalls the CA cert, restarts the service, only if any of
        // those actually drifted), so running it from both schedulers is harmless overlap, same as
        // every other manager here.
        runCatching { EnforcementCoordinator.runExclusive { VpnFilterManager(applicationContext).ensureActive() } }
            .onFailure { Log.w(TAG, "VPN watchdog reapply failed", it) }
        runCatching { EnforcementCoordinator.runExclusive { restrictionsManager.detectDriftAndReapply(tamperLogger) } }
            .onFailure { Log.w(TAG, "Restriction drift check failed", it) }
        runCatching { EnforcementCoordinator.runExclusive { AppSuspensionManager(applicationContext).reapplyAll() } }
            .onFailure { Log.w(TAG, "Blocked-app reapply failed", it) }
        runCatching { EnforcementCoordinator.runExclusive { AppUninstallGuard(applicationContext).reapplyAll() } }
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
