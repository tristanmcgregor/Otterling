package app.otterling.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.otterling.R
import app.otterling.monitoring.ProtectionEnforcementService
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.alerts.FcmTokenRegistrar
import app.otterling.alerts.MacTamperPollWorker
import app.otterling.restrictions.RestrictionEnforcementWorker
import app.otterling.tamper.TamperEventLogger
import app.otterling.updates.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DeviceAdminReceiverImpl : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        DeviceRestrictionsManager(context).applyDefaults()
        AccessibilityGuard.reapplyAllowlist(context)
        app.otterling.alerts.SmsPermissionGranter.grantSendSms(context)
        ProtectionEnforcementService.start(context)
        RestrictionEnforcementWorker.enqueuePeriodic(context)
        UpdateCheckWorker.enqueuePeriodic(context)
        MacTamperPollWorker.enqueuePeriodic(context)
        FcmTokenRegistrar.registerCurrentToken(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
        // Best-effort, last-chance signal -- by the time this fires, Device Owner status (and
        // whatever enforcement it backed) is already gone, so delivery isn't guaranteed, but it's
        // strictly better than the previous total silence. Same goAsync() pattern as
        // onDisableRequested, needed for the same reason: this callback's process can be killed
        // the moment it returns unless the broadcast's lifetime is explicitly extended.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TamperEventLogger(context).log(
                    type = "ADMIN_DISABLED",
                    details = "Device Admin was disabled",
                )
            } finally {
                pendingResult.finish()
            }
        }
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
