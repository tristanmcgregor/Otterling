package app.otterling.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.otterling.content.BlocklistRefreshWorker
import app.otterling.content.VpnFilterManager
import app.otterling.restrictions.RestrictionEnforcementWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ProtectionController(context).isEnabled()) return
        ProtectionEnforcementService.start(context)
        RestrictionEnforcementWorker.enqueuePeriodic(context)
        BlocklistRefreshWorker.enqueuePeriodic(context)
        VpnFilterManager(context).reapplyIfEnabled()
    }
}
