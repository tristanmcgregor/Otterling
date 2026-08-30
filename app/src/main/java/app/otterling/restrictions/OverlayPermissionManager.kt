package app.otterling.restrictions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * "Appear on top" (a.k.a. "draw over other apps") -- needed so the NSFW-screenshot warning banner
 * (see [app.otterling.focus.NsfwWarningOverlay]) can show above whatever app the user is currently
 * in, the same way [BatteryOptimizationManager] fires the battery-exemption dialog directly instead
 * of sending the user hunting through Settings by hand.
 */
class OverlayPermissionManager(private val context: Context) {
    fun isGranted(): Boolean = Settings.canDrawOverlays(context)

    fun permissionRequestIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
