package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Samsung's battery management can freeze/kill [au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionEnforcementService]
 * while the app isn't open, letting a cleared restriction sit unenforced until the app is next
 * opened. Being exempt from battery optimization is the single biggest lever against that, so
 * this fires the one-tap system exemption dialog directly instead of making the user hunt through
 * Settings -> Apps -> Battery menus by hand.
 */
class BatteryOptimizationManager(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun isExempt(): Boolean =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Shows the standard system "Allow app to ignore battery optimizations?" dialog, scoped to
     * this app. Requires [android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS], already
     * declared in the manifest. One tap for the user, no manual navigation required.
     */
    fun exemptionRequestIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
