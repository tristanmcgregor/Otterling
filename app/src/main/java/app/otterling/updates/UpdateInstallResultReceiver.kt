package app.otterling.updates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
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
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed successfully")
                // The one notification this whole background pipeline is allowed to be loud
                // about -- everything else (hourly periodic check, up-to-date, rejected/failed)
                // stays silent so this doesn't turn into a once-an-hour nag.
                notifyInstalled(context)
            }
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

    /**
     * Unlike [app.otterling.monitoring.ProtectionEnforcementService]/[app.otterling.content.VpnFilterService]'s
     * always-on foreground notifications (deliberately minimized -- see those classes), this one
     * is meant to be noticed: it's the only signal a background update ever happened at all, and
     * only ever fires once per completed install, not on a schedule.
     */
    private fun notifyInstalled(context: Context) {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Update installed", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Otterling updated")
            .setContentText(if (versionName != null) "Now running v$versionName" else "Update installed successfully")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
        const val CHANNEL_ID = "update_installed"
        const val NOTIFICATION_ID = 2001
    }
}
