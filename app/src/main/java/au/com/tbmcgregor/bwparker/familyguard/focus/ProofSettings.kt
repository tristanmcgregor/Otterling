package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context

/**
 * App-wide settings for habit photo-proof verification. Currently just the match strictness, which
 * lets accuracy be tuned on-device without a rebuild (see [ImageMatcher.Sensitivity]).
 */
class ProofSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sensitivity(): ImageMatcher.Sensitivity {
        val name = prefs.getString(KEY_SENSITIVITY, null)
        return runCatching { name?.let(ImageMatcher.Sensitivity::valueOf) }.getOrNull()
            ?: ImageMatcher.Sensitivity.NORMAL
    }

    fun setSensitivity(value: ImageMatcher.Sensitivity) {
        prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "habit_proof_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
    }
}
