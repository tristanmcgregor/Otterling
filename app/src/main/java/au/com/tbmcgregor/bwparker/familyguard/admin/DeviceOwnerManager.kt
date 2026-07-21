package au.com.tbmcgregor.bwparker.familyguard.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class DeviceOwnerManager(private val context: Context) {
    data class Status(
        val isDeviceAdminActive: Boolean,
        val isDeviceOwner: Boolean,
    )

    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    /** e.g. "adb shell dpm set-device-owner au.com.tbmcgregor.bwparker.familyguard/.admin.DeviceAdminReceiverImpl" */
    val provisioningAdbCommand: String
        get() = "adb shell dpm set-device-owner ${adminComponent.flattenToShortString()}"

    val removeAdminAdbCommand: String
        get() = "adb shell dpm remove-active-admin ${adminComponent.flattenToShortString()}"

    fun currentStatus(): Status = Status(
        isDeviceAdminActive = devicePolicyManager?.isAdminActive(adminComponent) == true,
        isDeviceOwner = devicePolicyManager?.isDeviceOwnerApp(context.packageName) == true,
    )
}
