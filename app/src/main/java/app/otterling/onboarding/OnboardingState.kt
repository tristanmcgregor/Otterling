package app.otterling.onboarding

import android.content.Context

/**
 * Tracks the first-run setup wizard's completion, and separately whether its one-time Welcome
 * screen has been shown -- nothing here is sensitive, so plain (unencrypted) prefs are fine.
 *
 * [isComplete] is a one-way flag: once set, the wizard never runs again, even if the Guardian
 * later disables something it configured via Settings. This is strictly a first-run gate, not an
 * ongoing enforcement mechanism -- [app.otterling.monitoring.ProtectionController]
 * already owns that.
 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeenWelcome(): Boolean = prefs.getBoolean(KEY_SEEN_WELCOME, false)

    fun setSeenWelcome() {
        prefs.edit().putBoolean(KEY_SEEN_WELCOME, true).apply()
    }

    fun isComplete(): Boolean = prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete() {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "onboarding_state"
        const val KEY_SEEN_WELCOME = "seen_welcome"
        const val KEY_COMPLETE = "complete"
    }
}
