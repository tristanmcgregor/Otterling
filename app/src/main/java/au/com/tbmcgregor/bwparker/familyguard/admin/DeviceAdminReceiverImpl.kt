package au.com.tbmcgregor.bwparker.familyguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.R
import au.com.tbmcgregor.bwparker.familyguard.monitoring.UsageTrackingService
import au.com.tbmcgregor.bwparker.familyguard.reporting.DailySummaryWorker
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DeviceAdminReceiverImpl : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        DeviceRestrictionsManager(context).applyDefaults()
        DailySummaryWorker.enqueuePeriodic(context)
        UsageTrackingService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable requested")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TamperEventLogger(context).log(
                    type = "ADMIN_DISABLE_REQUESTED",
                    details = "A user attempted to disable Device Admin",
                )
            } finally {
                pendingResult.finish()
            }
        }
        return context.getString(R.string.device_admin_disable_warning)
    }

    private companion object {
        const val TAG = "DeviceAdminReceiver"
    }
}
