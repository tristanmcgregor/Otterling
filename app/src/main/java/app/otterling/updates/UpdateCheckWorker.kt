package app.otterling.updates

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background scheduling wrapper around [ApprovedUpdateManager] -- reuses its full trust chain
 * (checkForUpdate -> downloadVerifyAndInstall) exactly as-is; this class only decides *when* that
 * runs, never an alternate way to get an update onto the device. Used for both the hourly periodic
 * check and the manual "Check for update" tap, so a user-initiated check behaves identically to
 * the automatic one (same verification, same silent-unless-installed notification behavior).
 *
 * Deliberately quiet on the common outcomes (up to date / rejected / transient error) -- only
 * [UpdateInstallResultReceiver] announces anything, and only on an actual completed install.
 * Without this, an hourly background job would otherwise nag with a notification every hour
 * it finds nothing to do.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = ApprovedUpdateManager(applicationContext)
        return when (val checkResult = manager.checkForUpdate()) {
            is UpdateCheckResult.UpdateAvailable -> {
                Log.i(TAG, "Update available: ${checkResult.manifest.versionName} -- downloading and verifying")
                when (val installResult = manager.downloadVerifyAndInstall(checkResult.manifest)) {
                    is InstallResult.Started -> Result.success()
                    is InstallResult.Rejected -> {
                        // Not transient -- a SHA-256/signing-cert mismatch won't fix itself by
                        // retrying the same manifest, so don't spin on it until the next scheduled
                        // check might see a corrected one.
                        Log.w(TAG, "Update rejected: ${installResult.reason}")
                        Result.failure()
                    }
                }
            }
            is UpdateCheckResult.UpToDate -> {
                Log.i(TAG, "Already up to date")
                Result.success()
            }
            is UpdateCheckResult.Error -> {
                Log.w(TAG, "Update check failed: ${checkResult.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val PERIODIC_WORK_NAME = "update_check_periodic"
        private const val ONE_SHOT_WORK_NAME = "update_check_one_shot"
        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(NETWORK_CONSTRAINTS)
                .build()
            // UPDATE (not KEEP) so an interval change like daily→hourly replaces any already-
            // scheduled unique work instead of leaving the old period stuck forever.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Manual "Check for update" entry point (Settings and the dashboard overflow dialog both
         * call this): runs the whole check/verify/install off the UI thread and off-screen, so the
         * caller can show a quick "checking in the background" message and move on instead of
         * blocking on the full download. [ExistingWorkPolicy.REPLACE] so mashing the button doesn't
         * queue up redundant duplicate downloads.
         */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(NETWORK_CONSTRAINTS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
