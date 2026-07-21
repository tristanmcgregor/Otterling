package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Forces a filtering DNS-over-TLS resolver via Device Owner's Private DNS control.
 * Covers both website category blocking and Safe Search enforcement in a single call --
 * no VPN service needed for the MVP. Device Owner Private DNS control needs API 29+.
 */
class PrivateDnsFilterManager(private val context: Context) {
    enum class FilterProfile(val host: String, val displayName: String) {
        FAMILY(
            host = "family-filter-dns.cleanbrowsing.org",
            displayName = "Family filter (blocks adult content, forces Safe Search)",
        ),
        SECURITY(
            host = "security-filter-dns.cleanbrowsing.org",
            displayName = "Security filter only (malware/phishing, no content filtering)",
        ),
    }

    sealed interface Result {
        data object Success : Result
        data object UnsupportedApiLevel : Result
        data class Failed(val message: String) : Result
    }

    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Null if unsupported, unavailable, or private DNS isn't in specified-host mode. */
    fun currentHost(): String? =
        if (isSupported) devicePolicyManager?.getGlobalPrivateDnsHost(adminComponent) else null

    /** Blocking call -- performs a connectivity check to the resolver. Call off the main thread. */
    fun enable(profile: FilterProfile): Result {
        if (!isSupported) return Result.UnsupportedApiLevel
        val dpm = devicePolicyManager ?: return Result.Failed("DevicePolicyManager unavailable")
        return try {
            when (dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, profile.host)) {
                DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> Result.Success
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                    Result.Failed("Resolver didn't respond to DNS-over-TLS (network may block port 853)")
                else -> Result.Failed("Failed to set private DNS")
            }
        } catch (error: SecurityException) {
            Result.Failed(error.message ?: "Not authorized (device owner required)")
        }
    }

    /** Falls back to opportunistic (best-effort) private DNS -- removes the forced filter. */
    fun disable(): Result {
        if (!isSupported) return Result.UnsupportedApiLevel
        val dpm = devicePolicyManager ?: return Result.Failed("DevicePolicyManager unavailable")
        return try {
            when (dpm.setGlobalPrivateDnsModeOpportunistic(adminComponent)) {
                DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> Result.Success
                else -> Result.Failed("Failed to clear private DNS")
            }
        } catch (error: SecurityException) {
            Result.Failed(error.message ?: "Not authorized (device owner required)")
        }
    }
}
