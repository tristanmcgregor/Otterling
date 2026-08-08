package app.otterling.monitoring

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.content.AppSuspensionManager
import app.otterling.content.CloudFilterSettings
import app.otterling.content.CustomBlocklistManager
import app.otterling.content.DomainBlocklistManager
import app.otterling.content.MitmExemptManager
import app.otterling.content.VpnFilterManager
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.restrictions.ActiveAdminRemover
import app.otterling.restrictions.PackageBlockEnforcer
import app.otterling.restrictions.PackageDisableStore
import kotlinx.coroutines.runBlocking

/**
 * DEBUG-ONLY receiver: clears live blocks OR tries to strip another app's device admin and
 * disable it when suspend fails.
 *
 * Clear all suspensions:
 *   adb shell am broadcast -a app.otterling.DEBUG_UNSUSPEND \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver
 *
 * Strip admin + suspend / hide:
 *   adb shell am broadcast -a app.otterling.DEBUG_STRIP_ADMIN \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.example.target
 *
 * Force hide/disable (skip suspend):
 *   adb shell am broadcast -a app.otterling.DEBUG_DISABLE \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.example.target
 *
 * Re-apply accessibility allowlist:
 *   adb shell am broadcast -a app.otterling.DEBUG_PERMIT_A11Y \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver
 *
 * Clear Otterling uninstall-block / unhide for packages (so they can be uninstalled):
 *   adb shell am broadcast -a app.otterling.DEBUG_ALLOW_UNINSTALL \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.accountable2you.ap1.googleplay
 *
 * Enable always-on VPN + cloud filter + MITM proxy (for emulator smoke tests):
 *   adb shell am broadcast -a app.otterling.DEBUG_ENABLE_FILTER \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --es proxy_password '…' [--ez proxy_enabled true] [--ez lockdown false]
 *
 * Refresh downloaded domain blocklist:
 *   adb shell am broadcast -a app.otterling.DEBUG_REFRESH_BLOCKLIST \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver
 *
 * Seed a custom domain or path rule:
 *   adb shell am broadcast -a app.otterling.DEBUG_SEED_CUSTOM_BLOCK \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --es rule 'blockme.otterling.test'   # or youtube.com/shorts
 *
 * Suspend/block a package (Device Owner):
 *   adb shell am broadcast -a app.otterling.DEBUG_SEED_BLOCK_APP \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --es package test.blocker.victim [--ez blocked true]
 *
 * Probe whether a hostname would be DNS-blocked (local + custom lists):
 *   adb shell am broadcast -a app.otterling.DEBUG_PROBE_DNS \
 *     -n app.otterling/.monitoring.DebugUnsuspendReceiver \
 *     --es host blockme.otterling.test
 */
class DebugUnsuspendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val action = intent?.action.orEmpty()
        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)
        val pending = goAsync()
        Thread {
            try {
                when {
                    action.endsWith("DEBUG_ENABLE_FILTER") -> {
                        val cloud = CloudFilterSettings(context)
                        val host = intent?.getStringExtra("host").orEmpty().ifBlank { cloud.host() }
                        val proxyPassword = intent?.getStringExtra("proxy_password").orEmpty()
                        val proxyEnabled = intent?.getBooleanExtra("proxy_enabled", true) ?: true
                        cloud.setHost(host)
                        cloud.setEnabled(true)
                        cloud.setProxyEnabled(proxyEnabled)
                        if (proxyPassword.isNotEmpty()) cloud.setProxyPassword(proxyPassword)
                        // Ensure YouTube (and other defaults) are seeded before the tunnel starts.
                        MitmExemptManager(context).exemptPackages()
                        val lockdown = intent?.getBooleanExtra("lockdown", true) ?: true
                        val alwaysOn = intent?.getBooleanExtra("always_on", true) ?: true
                        val vpnOk = VpnFilterManager(context).enable(
                            lockdownEnabled = lockdown,
                            registerAlwaysOn = alwaysOn,
                        )
                        // Reachability probes can hang under lockdown; keep them short and best-effort.
                        val dnsOk = runCatching { cloud.testReachable(timeoutMs = 2_000) }.getOrDefault(false)
                        val proxyOk = if (proxyEnabled) {
                            runCatching { cloud.testProxyReachable(timeoutMs = 2_000) }.getOrDefault(false)
                        } else {
                            true
                        }
                        Log.i(
                            TAG,
                            "DEBUG_ENABLE_FILTER vpnOk=$vpnOk dnsOk=$dnsOk proxyOk=$proxyOk " +
                                "proxyEnabled=$proxyEnabled lockdown=$lockdown alwaysOn=$alwaysOn host=$host " +
                                "passwordSet=${proxyPassword.isNotEmpty()} " +
                                "exempt=${MitmExemptManager(context).exemptPackages()}",
                        )
                    }
                    action.endsWith("DEBUG_REFRESH_BLOCKLIST") -> {
                        val manager = DomainBlocklistManager(context)
                        val result = manager.refresh()
                        val count = result.getOrElse { -1 }
                        val err = result.exceptionOrNull()?.message.orEmpty()
                        Log.i(
                            TAG,
                            "DEBUG_REFRESH_BLOCKLIST ok=${result.isSuccess} count=$count " +
                                "cached=${manager.domainCount()} err=$err",
                        )
                    }
                    action.endsWith("DEBUG_SEED_CUSTOM_BLOCK") -> {
                        val rule = intent?.getStringExtra("rule")
                            ?: intent?.getStringExtra("domain")
                            ?: intent?.getStringExtra("path")
                            ?: ""
                        val added = CustomBlocklistManager(context).add(rule)
                        Log.i(
                            TAG,
                            "DEBUG_SEED_CUSTOM_BLOCK ok=${added.isSuccess} rule=${added.getOrNull()} " +
                                "err=${added.exceptionOrNull()?.message.orEmpty()}",
                        )
                    }
                    action.endsWith("DEBUG_SEED_BLOCK_APP") -> {
                        val pkg = intent?.getStringExtra("package").orEmpty()
                        val blocked = intent?.getBooleanExtra("blocked", true) ?: true
                        if (pkg.isEmpty()) {
                            Log.w(TAG, "DEBUG_SEED_BLOCK_APP missing package=")
                        } else {
                            runBlocking {
                                AppSuspensionManager(context).setBlocked(pkg, blocked)
                            }
                            // Also apply enforcer immediately in case Room path races.
                            PackageBlockEnforcer.setBlocked(context, pkg, blocked)
                            val suspended = if (android.os.Build.VERSION.SDK_INT >= 29) {
                                runCatching {
                                    context.packageManager.isPackageSuspended(pkg)
                                }.getOrDefault(false)
                            } else {
                                false
                            }
                            Log.i(
                                TAG,
                                "DEBUG_SEED_BLOCK_APP package=$pkg blocked=$blocked suspended=$suspended",
                            )
                        }
                    }
                    action.endsWith("DEBUG_PROBE_DNS") -> {
                        val host = intent?.getStringExtra("host").orEmpty().trim().lowercase()
                        if (host.isEmpty()) {
                            Log.w(TAG, "DEBUG_PROBE_DNS missing host=")
                        } else {
                            // Otterling itself is excluded from the VPN tunnel, so we report the
                            // same decision VpnFilterService would make for a captured DNS query
                            // (downloaded list + domain-only custom rules).
                            val blocked = DomainBlocklistManager(context).isBlocked(host)
                            Log.i(TAG, "PROBE_DNS host=$host blocked=$blocked")
                        }
                    }
                    action.endsWith("DEBUG_ALLOW_UNINSTALL") -> {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@Thread
                        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
                        val dao = app.otterling.data.AppDatabase
                            .getInstance(context).protectedAppDao()
                        val fromArray = packages?.toList().orEmpty()
                        val fromString = intent?.getStringExtra("package")
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                        val targets = (fromArray + fromString).ifEmpty {
                            listOf(
                                "com.accountable2you.ap1.googleplay",
                                "com.accountable2you.reportsapp",
                            )
                        }
                        Log.i(TAG, "DEBUG_ALLOW_UNINSTALL targets=$targets")
                        for (pkg in targets) {
                            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
                                .onSuccess { Log.i(TAG, "setUninstallBlocked(false) ok $pkg") }
                                .onFailure { Log.w(TAG, "setUninstallBlocked(false) failed for $pkg", it) }
                            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                                .onSuccess { Log.i(TAG, "setApplicationHidden(false) ok $pkg") }
                                .onFailure { Log.w(TAG, "setApplicationHidden(false) failed for $pkg", it) }
                            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
                            runBlocking { runCatching { dao.delete(pkg) } }
                        }
                        AccessibilityGuard.reapplyAllowlist(context)
                    }
                    action.endsWith("DEBUG_PERMIT_A11Y") -> {
                        AccessibilityGuard.reapplyAllowlist(context)
                        Log.i(TAG, "Reapplied accessibility allowlist")
                    }
                    action.endsWith("DEBUG_DISABLE") -> {
                        val disableStore = PackageDisableStore(context)
                        for (pkg in packages?.toList().orEmpty()) {
                            val ok = disableStore.disable(pkg)
                            Log.i(TAG, "force-disable $pkg -> $ok")
                        }
                    }
                    action.endsWith("DEBUG_STRIP_ADMIN") -> {
                        val disableStore = PackageDisableStore(context)
                        for (pkg in packages?.toList().orEmpty()) {
                            val ok = ActiveAdminRemover.suspendEvenIfAdmin(context, pkg)
                            if (ok) {
                                disableStore.markBlocked(pkg)
                                Log.i(TAG, "strip+suspend $pkg -> suspendOk=true")
                            } else {
                                val disabled = disableStore.disable(pkg)
                                Log.i(TAG, "strip+suspend $pkg -> suspendOk=false disableOk=$disabled")
                            }
                        }
                    }
                    packages.isNullOrEmpty() -> clearAll(context)
                    else -> {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@Thread
                        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
                        val failed = dpm.setPackagesSuspended(admin, packages, false)
                        Log.i(TAG, "Unsuspended ${packages.toList()}; failed=${failed.toList()}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "DebugUnsuspend failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun clearAll(context: Context) {
        PackageDisableStore(context).clearAll()
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val self = context.packageName
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrElse { pm.getInstalledApplications(0) }
        val suspended = mutableListOf<String>()
        for (app in apps) {
            val pkg = app.packageName
            if (pkg == self) continue
            if (runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)) suspended += pkg
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            val state = runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull() ?: continue
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                runCatching {
                    pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
                    Log.i(TAG, "Re-enabled $pkg")
                }
            }
        }
        if (suspended.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
            Log.i(TAG, "Cleared ${suspended.size} suspensions")
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"
        private const val TAG = "DebugUnsuspend"
    }
}
