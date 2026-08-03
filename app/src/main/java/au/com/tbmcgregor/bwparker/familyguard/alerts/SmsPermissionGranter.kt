package au.com.tbmcgregor.bwparker.familyguard.alerts

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

object SmsPermissionGranter {
    private const val TAG = "SmsPermissionGranter"

    fun grantSendSms(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner -- cannot auto-grant SEND_SMS")
            return
        }
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                Manifest.permission.SEND_SMS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            Log.i(TAG, "SEND_SMS granted and locked by Device Owner")
        } catch (error: SecurityException) {
            Log.e(TAG, "Failed to grant SEND_SMS", error)
        }
    }

    fun hasSendSms(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}
