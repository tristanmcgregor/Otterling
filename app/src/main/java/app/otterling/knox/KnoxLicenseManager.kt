package app.otterling.knox

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

class KnoxLicenseManager(private val context: Context) {
    data class Availability(
        val licenseManager: Boolean,
        val restrictionPolicy: Boolean,
    ) {
        val isKnoxSdkAvailable: Boolean
            get() = licenseManager && restrictionPolicy
    }

    sealed interface ActivationResult {
        data object Requested : ActivationResult
        data object MissingKey : ActivationResult
        data object KnoxUnavailable : ActivationResult
        data class Failed(val message: String) : ActivationResult
    }

    fun checkAvailability(): Availability = Availability(
        licenseManager = classExists(LICENSE_MANAGER_CLASS),
        restrictionPolicy = classExists(RESTRICTION_POLICY_CLASS),
    )

    fun activate(licenseKey: String): ActivationResult {
        if (licenseKey.isBlank()) return ActivationResult.MissingKey
        if (!checkAvailability().licenseManager) return ActivationResult.KnoxUnavailable

        return try {
            val managerClass = Class.forName(LICENSE_MANAGER_CLASS)
            val manager = managerClass
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            managerClass
                .getMethod("activateLicense", String::class.java)
                .invoke(manager, licenseKey)

            Log.i(TAG, "Knox license activation requested")
            ActivationResult.Requested
        } catch (error: ReflectiveOperationException) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            val message = cause.message ?: cause.javaClass.simpleName
            Log.e(TAG, "Unable to request Knox license activation", cause)
            ActivationResult.Failed(message)
        } catch (error: SecurityException) {
            val message = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Knox rejected license activation", error)
            ActivationResult.Failed(message)
        }
    }

    private fun classExists(className: String): Boolean = runCatching {
        Class.forName(className)
    }.isSuccess

    private companion object {
        const val TAG = "KnoxLicenseManager"
        const val LICENSE_MANAGER_CLASS =
            "com.samsung.android.knox.license.KnoxEnterpriseLicenseManager"
        const val RESTRICTION_POLICY_CLASS =
            "com.samsung.android.knox.restriction.RestrictionPolicy"
    }
}
