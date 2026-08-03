package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import au.com.tbmcgregor.bwparker.familyguard.restrictions.PackageBlockEnforcer

/**
 * Apps that are suspended by default and only opened by spending earned reward minutes (see
 * [RewardLedgerManager]) -- the "block YouTube until I've earned it" mechanic. Newly added apps
 * start suspended immediately.
 */
class RewardAppManager(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).rewardAppDao()

    suspend fun rewardApps(): List<RewardApp> = dao.getAll()

    suspend fun add(packageName: String) {
        dao.upsert(RewardApp(packageName))
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = true)
    }

    suspend fun remove(packageName: String) {
        dao.delete(packageName)
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = false)
    }

    /** Suspends or unsuspends every configured reward app -- used by [RewardLedgerManager]. */
    suspend fun setAllSuspended(suspended: Boolean) {
        dao.getAll().forEach { PackageBlockEnforcer.setBlocked(context, it.packageName, blocked = suspended) }
    }
}
