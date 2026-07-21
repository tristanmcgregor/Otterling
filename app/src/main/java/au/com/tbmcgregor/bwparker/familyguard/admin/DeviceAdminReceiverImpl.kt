package au.com.tbmcgregor.bwparker.familyguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.R
import au.com.tbmcgregor.bwparker.familyguard.monitoring.UsageTrackingService
import au.com.tbmcgregor.bwparker.familyguard.reporting.DailySummaryWorker
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleEnforcementWorker

class DeviceAdminReceiverImpl : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        DeviceRestrictionsManager(context).applyDefaults()
        ScheduleEnforcementWorker.enqueuePeriodic(context)
        DailySummaryWorker.enqueuePeriodic(context)
        UsageTrackingService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable requested")
        return context.getString(R.string.device_admin_disable_warning)
    }

    private companion object {
        const val TAG = "DeviceAdminReceiver"
    }
}
