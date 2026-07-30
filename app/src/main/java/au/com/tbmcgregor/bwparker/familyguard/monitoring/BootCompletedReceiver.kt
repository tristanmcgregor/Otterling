package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import au.com.tbmcgregor.bwparker.familyguard.content.BlocklistRefreshWorker
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.RestrictionEnforcementWorker

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
