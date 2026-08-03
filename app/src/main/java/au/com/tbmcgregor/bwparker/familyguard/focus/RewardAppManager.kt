package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import au.com.tbmcgregor.bwparker.familyguard.restrictions.ActiveAdminRemover
import au.com.tbmcgregor.bwparker.familyguard.restrictions.BounceBlockStore

/**
 * Apps that are suspended by default and only opened by spending earned reward minutes (see
 * [RewardLedgerManager]) -- the "block YouTube until I've earned it" mechanic. Newly added apps
 * start suspended immediately.
 */
class RewardAppManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
    private val dao = AppDatabase.getInstance(context).rewardAppDao()

    suspend fun rewardApps(): List<RewardApp> = dao.getAll()

    suspend fun add(packageName: String) {
        dao.upsert(RewardApp(packageName))
        applySuspended(packageName, suspended = true)
    }

    suspend fun remove(packageName: String) {
        dao.delete(packageName)
        applySuspended(packageName, suspended = false)
    }

    /** Suspends or unsuspends every configured reward app -- used by [RewardLedgerManager]. */
    suspend fun setAllSuspended(suspended: Boolean) {
        dao.getAll().forEach { applySuspended(it.packageName, suspended) }
    }

    private fun applySuspended(packageName: String, suspended: Boolean) {
        val dpm = devicePolicyManager ?: return
        val bounce = BounceBlockStore(context)
        try {
            if (suspended) {
                val failed = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
                if (failed.isEmpty()) {
                    bounce.setBlocked(packageName, blocked = false)
                    return
                }
                if (ActiveAdminRemover.suspendEvenIfAdmin(context, packageName)) {
                    bounce.setBlocked(packageName, blocked = false)
                } else {
                    bounce.setBlocked(packageName, blocked = true)
                }
            } else {
                bounce.setBlocked(packageName, blocked = false)
                dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false)
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to suspend $packageName", error)
            if (suspended) bounce.setBlocked(packageName, blocked = true)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot suspend $packageName (not installed?)", error)
        }
    }

    private companion object {
        const val TAG = "RewardAppManager"
    }
}
