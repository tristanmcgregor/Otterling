package app.otterling.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.otterling.alerts.MacTamperPollWorker
import app.otterling.content.BlocklistRefreshWorker
import app.otterling.content.VpnFilterManager
import app.otterling.restrictions.RestrictionEnforcementWorker
import app.otterling.updates.UpdateCheckWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ProtectionController(context).isEnabled()) return
        ProtectionEnforcementService.start(context)
        RestrictionEnforcementWorker.enqueuePeriodic(context)
        BlocklistRefreshWorker.enqueuePeriodic(context)
        UpdateCheckWorker.enqueuePeriodic(context)
        MacTamperPollWorker.enqueuePeriodic(context)
        VpnFilterManager(context).reapplyIfEnabled()
    }
}
