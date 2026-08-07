package au.com.tbmcgregor.bwparker.familyguard.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertReporter
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the async result of the [PackageInstaller] session [ApprovedUpdateManager.installApk]
 * commits. Internal-only (`exported=false` in the manifest) -- the PendingIntent that targets
 * this is only ever handed to `PackageInstaller.Session.commit`, never advertised anywhere else.
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed successfully")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen once self-delegation (see ensureInstallDelegation) succeeded,
                // but if it somehow does, forward the confirmation screen rather than letting the
                // update silently vanish with no way to complete it.
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(it) }
                }
            }
            else -> Log.e(TAG, "Update install failed: status=$status message=$message")
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AlertReporter(context).report(
                    type = "APP_UPDATE",
                    details = if (status == PackageInstaller.STATUS_SUCCESS) {
                        "Update installed successfully"
                    } else {
                        "Update install did not complete: ${message ?: "status=$status"}"
                    },
                    severity = if (status == PackageInstaller.STATUS_SUCCESS) AlertSeverity.INFO else AlertSeverity.WARNING,
                )
            }.onFailure { Log.w(TAG, "Update-result alert failed", it) }
            pendingResult.finish()
        }
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}
