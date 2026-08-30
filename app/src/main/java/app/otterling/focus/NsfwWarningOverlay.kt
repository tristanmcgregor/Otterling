package app.otterling.focus

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import app.otterling.restrictions.OverlayPermissionManager

/**
 * Short "appear on top" banner shown the moment a screenshot is flagged as NSFW but before the app
 * is actually blocked (see FocusGuardAccessibilityService.handleCapturedScreenshot's 3-strikes
 * counter) -- a heads-up that content was flagged, not the block itself. Falls back to a plain
 * Toast if the "Display over other apps" permission hasn't been granted (see
 * [OverlayPermissionManager]), so a warning is still shown even before the user completes that
 * onboarding step, just without the always-on-top guarantee.
 */
object NsfwWarningOverlay {
    private const val VISIBLE_MILLIS = 2_500L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String = "NSFW content detected") {
        val appContext = context.applicationContext
        if (!OverlayPermissionManager(appContext).isGranted()) {
            mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
            return
        }
        mainHandler.post { showOverlay(appContext, message) }
    }

    private fun showOverlay(context: Context, message: String) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 96
        }
        val view = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 40, 0, 0))
            setPadding(32, 20, 32, 20)
            textSize = 14f
        }

        val removed = booleanArrayOf(false)
        fun removeSafely() {
            if (removed[0]) return
            removed[0] = true
            runCatching { windowManager.removeView(view) }
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure { return }
        mainHandler.postDelayed(::removeSafely, VISIBLE_MILLIS)
    }
}
