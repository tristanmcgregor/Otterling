package app.otterling.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.otterling.alerts.MacTamperPollWorker
import app.otterling.content.BlocklistRefreshWorker
import app.otterling.content.VpnFilterManager
import app.otterling.restrictions.RestrictionEnforcementWorker
import app.otterling.updates.UpdateCheckWorker

// Also handles ACTION_MY_PACKAGE_REPLACED: Android tears down the VPN and any running
// services when the APK is updated, and nothing else restarts them, so without this the
// user has to manually reopen the app after every update for protection to resume.
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ProtectionController(context).isEnabled()) return
        ProtectionEnforcementService.start(context)
        RestrictionEnforcementWorker.enqueuePeriodic(context)
        BlocklistRefreshWorker.enqueuePeriodic(context)
        UpdateCheckWorker.enqueuePeriodic(context)
        MacTamperPollWorker.enqueuePeriodic(context)
        VpnFilterManager(context).reapplyIfEnabled()
    }
}
