package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import au.com.tbmcgregor.bwparker.familyguard.data.ProtectedApp

/**
 * Blocks uninstall of arbitrary third-party apps via [DevicePolicyManager.setUninstallBlocked]
 * (which works for any package, not just this app's own) and persists the chosen list so it can
 * be re-applied after a reboot. Requires Device Owner. This is separate from
 * [DeviceRestrictionsManager.setUninstallBlocked], which protects this app itself.
 */
class AppUninstallGuard(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    private val dao = AppDatabase.getInstance(context).protectedAppDao()

    suspend fun protectedApps(): List<ProtectedApp> = dao.getAll()

    /** Persists the choice and applies it immediately. Returns true if the system accepted it. */
    suspend fun protect(packageName: String): Boolean {
        dao.upsert(ProtectedApp(packageName))
        return applyToSystem(packageName, blocked = true)
    }

    suspend fun unprotect(packageName: String) {
        if (CompanionAppGuard.isCompanion(packageName)) {
            Log.w(TAG, "Refusing to unprotect companion app $packageName")
            return
        }
        dao.delete(packageName)
        applyToSystem(packageName, blocked = false)
    }

    /** Call on boot / Device Owner enable so the persisted list survives a reboot. */
    suspend fun reapplyAll() {
        dao.getAll().forEach { applyToSystem(it.packageName, blocked = true) }
    }

    suspend fun releaseAll() {
        dao.getAll().forEach { applyToSystem(it.packageName, blocked = false) }
    }

    private fun applyToSystem(packageName: String, blocked: Boolean): Boolean {
        val dpm = devicePolicyManager ?: return false
        return try {
            dpm.setUninstallBlocked(adminComponent, packageName, blocked)
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to protect $packageName (device owner not active?)", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot protect $packageName (package not installed?)", error)
            false
        }
    }

    private companion object {
        const val TAG = "AppUninstallGuard"
    }
}
