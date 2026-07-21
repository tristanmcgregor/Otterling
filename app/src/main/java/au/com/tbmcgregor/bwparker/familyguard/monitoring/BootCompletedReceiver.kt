package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import au.com.tbmcgregor.bwparker.familyguard.reporting.DailySummaryWorker
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleEnforcementWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        UsageTrackingService.start(context)
        ScheduleEnforcementWorker.enqueuePeriodic(context)
        DailySummaryWorker.enqueuePeriodic(context)
    }
}
