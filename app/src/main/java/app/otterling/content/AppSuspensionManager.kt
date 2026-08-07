package app.otterling.content

import android.content.Context
import app.otterling.data.AppDatabase
import app.otterling.data.BlockedApp
import app.otterling.restrictions.PackageBlockEnforcer

/**
 * Blocks apps via package suspend (or disable-user fallback for device-admin apps) and persists
 * the chosen list so it can be re-applied after a reboot. Requires Device Owner.
 */
class AppSuspensionManager(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).blockedAppDao()

    suspend fun blockedApps(): List<BlockedApp> = dao.getAll()

    /** Persists the choice and applies it immediately. Returns true if the system accepted it. */
    suspend fun setBlocked(packageName: String, blocked: Boolean): Boolean {
        dao.upsert(BlockedApp(packageName, blocked))
        PackageBlockEnforcer.setBlocked(context, packageName, blocked)
        return true
    }

    suspend fun remove(packageName: String) {
        dao.delete(packageName)
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = false)
    }

    /** Call on boot / Device Owner enable so the persisted list survives a reboot. */
    suspend fun reapplyAll() {
        dao.getAll().forEach { PackageBlockEnforcer.setBlocked(context, it.packageName, it.blocked) }
    }

    suspend fun releaseAll() {
        dao.getAll().forEach { PackageBlockEnforcer.setBlocked(context, it.packageName, blocked = false) }
    }
}
