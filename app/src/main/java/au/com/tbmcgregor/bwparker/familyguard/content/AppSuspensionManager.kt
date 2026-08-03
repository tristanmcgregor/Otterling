package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import au.com.tbmcgregor.bwparker.familyguard.data.BlockedApp
import au.com.tbmcgregor.bwparker.familyguard.restrictions.ActiveAdminRemover
import au.com.tbmcgregor.bwparker.familyguard.restrictions.BounceBlockStore

/**
 * Blocks apps via [DevicePolicyManager.setPackagesSuspended] and persists the chosen list so it
 * can be re-applied after a reboot. Requires Device Owner. If suspend is refused because the
 * target is an active device admin, tries to strip that admin, then falls back to accessibility
 * bounce-block (see [BounceBlockStore]).
 */
class AppSuspensionManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    private val dao = AppDatabase.getInstance(context).blockedAppDao()

    suspend fun blockedApps(): List<BlockedApp> = dao.getAll()

    /** Persists the choice and applies it immediately. Returns true if the system accepted it. */
    suspend fun setBlocked(packageName: String, blocked: Boolean): Boolean {
        dao.upsert(BlockedApp(packageName, blocked))
        return applyToSystem(packageName, blocked)
    }

    suspend fun remove(packageName: String) {
        dao.delete(packageName)
        applyToSystem(packageName, blocked = false)
    }

    /** Call on boot / Device Owner enable so the persisted list survives a reboot. */
    suspend fun reapplyAll() {
        dao.getAll().forEach { applyToSystem(it.packageName, it.blocked) }
    }

    suspend fun releaseAll() {
        dao.getAll().forEach { applyToSystem(it.packageName, blocked = false) }
    }

    private fun applyToSystem(packageName: String, blocked: Boolean): Boolean {
        val dpm = devicePolicyManager ?: return false
        val bounce = BounceBlockStore(context)
        return try {
            if (blocked) {
                val failed = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
                if (failed.isEmpty()) {
                    bounce.setBlocked(packageName, blocked = false)
                    return true
                }
                Log.w(TAG, "System refused to suspend $packageName -- trying admin strip then bounce")
                if (ActiveAdminRemover.suspendEvenIfAdmin(context, packageName)) {
                    bounce.setBlocked(packageName, blocked = false)
                    return true
                }
                bounce.setBlocked(packageName, blocked = true)
                true // bounce-block is an effective block
            } else {
                bounce.setBlocked(packageName, blocked = false)
                val failed = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false)
                val succeeded = failed.isEmpty()
                if (!succeeded) {
                    Log.w(TAG, "System refused to unsuspend $packageName")
                }
                succeeded
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to suspend $packageName (device owner not active?)", error)
            if (blocked) {
                bounce.setBlocked(packageName, blocked = true)
                true
            } else {
                false
            }
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot suspend $packageName (package not installed?)", error)
            false
        }
    }

    private companion object {
        const val TAG = "AppSuspensionManager"
    }
}
