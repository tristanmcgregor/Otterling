package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetManager
import au.com.tbmcgregor.bwparker.familyguard.focus.BudgetEnforcer
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitShareSyncManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedgerManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AccessibilityGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AppUninstallGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.CompanionAppGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.tamper.AccessibilityGuardActivity
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that periodically re-asserts Phase 3 restrictions, the
 * Phase 4 blocked-app list, and the protected-from-uninstall app list in case anything cleared
 * them (e.g. a factory OEM reset of user restrictions). Declared as
 * `foregroundServiceType="specialUse"` per Android 14+ requirements.
 */
class ProtectionEnforcementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var habitShareSyncJob: Job? = null
    private var vpnWatchdogJob: Job? = null
    private var accessibilityObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerAccessibilityObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ProtectionController(applicationContext).isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob == null) {
            val restrictionsManager = DeviceRestrictionsManager(applicationContext)
            val tamperLogger = TamperEventLogger(applicationContext)
            val suspensionManager = AppSuspensionManager(applicationContext)
            val uninstallGuard = AppUninstallGuard(applicationContext)
            val budgetEnforcer = BudgetEnforcer(applicationContext)
            val budgetManager = AppTimeBudgetManager(applicationContext)
            val rewardLedgerManager = RewardLedgerManager(applicationContext)
            val habitRuleManager = HabitRuleManager(applicationContext)
            loopJob = scope.launch {
                runCatching { CompanionAppGuard.reapplyAll(applicationContext) }
                    .onFailure { Log.w(TAG, "Companion protection reapply failed", it) }
                runCatching { suspensionManager.reapplyAll() }
                    .onFailure { Log.w(TAG, "Blocked-app reapply failed", it) }
                runCatching { uninstallGuard.reapplyAll() }
                    .onFailure { Log.w(TAG, "Uninstall-protection reapply failed", it) }
                while (isActive) {
                    if (!ProtectionController(applicationContext).isEnabled()) {
                        stopSelf()
                        break
                    }
                    runCatching { CompanionAppGuard.reapplyAll(applicationContext) }
                        .onFailure { Log.w(TAG, "Companion protection reapply failed", it) }
                    runCatching { restrictionsManager.detectDriftAndReapply(tamperLogger) }
                        .onFailure { Log.w(TAG, "Restriction drift check failed", it) }
                    runCatching { checkAccessibilityGuard(tamperLogger) }
                        .onFailure { Log.w(TAG, "Accessibility guard check failed", it) }
                    runCatching { budgetEnforcer.reapplyAll() }
                        .onFailure { Log.w(TAG, "Time budget reapply failed", it) }
                    runCatching { rewardLedgerManager.reapply() }
                        .onFailure { Log.w(TAG, "Reward ledger reapply failed", it) }
                    runCatching { habitRuleManager.reapplyAll() }
                        .onFailure { Log.w(TAG, "Habit rule reapply failed", it) }
                    runCatching { budgetManager.pruneOldCounters() }
                        .onFailure { Log.w(TAG, "Usage counter prune failed", it) }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        if (habitShareSyncJob == null) {
            val habitShareSyncManager = HabitShareSyncManager(applicationContext)
            habitShareSyncJob = scope.launch {
                while (isActive) {
                    if (!ProtectionController(applicationContext).isEnabled()) break
                    runCatching { habitShareSyncManager.syncIfConnected() }
                        .onFailure { Log.w(TAG, "HabitShare sync failed", it) }
                    delay(HABITSHARE_SYNC_INTERVAL_MS)
                }
            }
        }
        if (vpnWatchdogJob == null) {
            vpnWatchdogJob = scope.launch {
                while (isActive) {
                    if (!ProtectionController(applicationContext).isEnabled()) break
                    runCatching { VpnFilterManager(applicationContext).ensureActive() }
                        .onFailure { Log.w(TAG, "VPN watchdog failed", it) }
                    delay(VPN_WATCHDOG_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    /** Near-instant reaction to the accessibility toggle, on top of the ~5-minute poll above --
     * fires within a fraction of a second of the user flipping it in Settings. */
    private fun registerAccessibilityObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch {
                    runCatching { checkAccessibilityGuard(TamperEventLogger(applicationContext)) }
                        .onFailure { Log.w(TAG, "Accessibility guard check failed", it) }
                }
            }
        }
        accessibilityObserver = observer
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
    }

    private fun checkAccessibilityGuard(tamperLogger: TamperEventLogger) {
        if (!ProtectionController(applicationContext).isEnabled()) return
        AccessibilityGuard.reapplyAllowlist(applicationContext)
        if (!AccessibilityGuard.isEnabled(applicationContext)) {
            scope.launch {
                tamperLogger.log(
                    type = "ACCESSIBILITY_DISABLED",
                    details = "Accessibility service was turned off; showing lock screen until it's re-enabled",
                )
            }
            AccessibilityGuardActivity.launch(applicationContext)
        }
    }

    override fun onDestroy() {
        accessibilityObserver?.let { contentResolver.unregisterContentObserver(it) }
        accessibilityObserver = null
        loopJob?.cancel()
        loopJob = null
        habitShareSyncJob?.cancel()
        habitShareSyncJob = null
        vpnWatchdogJob?.cancel()
        vpnWatchdogJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Device protection active", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Device Guard")
            .setContentText("Protections are active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ProtectionEnforcementService"
        private const val CHANNEL_ID = "protection_enforcement"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
        private const val HABITSHARE_SYNC_INTERVAL_MS = 30 * 1000L
        private const val VPN_WATCHDOG_INTERVAL_MS = 60 * 1000L

        fun start(context: Context) {
            if (!ProtectionController(context).isEnabled()) return
            context.startForegroundService(Intent(context, ProtectionEnforcementService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionEnforcementService::class.java))
        }
    }
}
