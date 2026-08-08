package app.otterling.alerts

import android.content.Context

/**
 * Shared alert-detection settings: what counts as a flagged event (trigger words, watched apps),
 * independent of who receives the alert -- see [AccountabilityPartnerSettings] for the actual SMS
 * recipient/number/cap. Kept under this name (rather than renamed) so the existing on-device
 * SharedPreferences file -- and whatever trigger words/watched apps a user already configured --
 * isn't silently reset by a rename.
 */
class GuardianAlertSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        seedDefaultTriggerWordsIfNeeded()
    }

    fun triggerWords(): List<String> =
        prefs.getString(KEY_TRIGGERS, "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setTriggerWords(raw: String) {
        prefs.edit().putString(KEY_TRIGGERS, raw).apply()
    }

    fun watchedPackages(): Set<String> =
        prefs.getStringSet(KEY_WATCHED, emptySet())?.toSet().orEmpty()

    fun setWatchedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_WATCHED, packages).apply()
    }

    fun addWatchedPackage(packageName: String) {
        setWatchedPackages(watchedPackages() + packageName)
    }

    fun removeWatchedPackage(packageName: String) {
        setWatchedPackages(watchedPackages() - packageName)
    }

    fun smsInfoEvents(): Boolean = prefs.getBoolean(KEY_SMS_INFO, false)

    fun setSmsInfoEvents(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_INFO, enabled).apply()
    }

    fun lastDebounceMillis(key: String): Long = prefs.getLong(debounceKey(key), 0L)

    fun setLastDebounceMillis(key: String, millis: Long) {
        prefs.edit().putLong(debounceKey(key), millis).apply()
    }

    private fun debounceKey(key: String): String = "debounce_$key"

    /**
     * One-time merge of [DEFAULT_TRIGGER_WORDS] into whatever's already stored -- runs at most
     * once ever per install (tracked by [KEY_SEEDED_DEFAULT_TRIGGERS]), same pattern as
     * MitmExemptManager's default-package seeding. Merges rather than overwrites so a word a user
     * already added (e.g. before this list existed) survives, and once seeded a user is free to
     * remove any of these without them reappearing.
     */
    private fun seedDefaultTriggerWordsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_DEFAULT_TRIGGERS, false)) return
        val merged = (triggerWords().toSet() + DEFAULT_TRIGGER_WORDS).sorted()
        prefs.edit()
            .putString(KEY_TRIGGERS, merged.joinToString("\n"))
            .putBoolean(KEY_SEEDED_DEFAULT_TRIGGERS, true)
            .apply()
    }

    companion object {
        private const val PREFS = "guardian_alert_settings"
        private const val KEY_TRIGGERS = "trigger_words"
        private const val KEY_WATCHED = "watched_packages"
        private const val KEY_SMS_INFO = "sms_info_events"
        private const val KEY_SEEDED_DEFAULT_TRIGGERS = "seeded_default_triggers_v1"
        const val DEBOUNCE_MS = 10 * 60_000L

        /** Reuses the same low-false-positive keyword set the server-side filter's title/page
         *  check already uses (mitm_nsfw_addon.py's TITLE_KEYWORDS), plus well-known explicit
         *  site/service names worth catching as a search term even before any page loads. */
        val DEFAULT_TRIGGER_WORDS = setOf(
            "porn",
            "pornstar",
            "xxx video",
            "hardcore sex",
            "nude cams",
            "hentai",
            "nude photos",
            "adult video",
            "cam girls",
            "live sex cams",
            "amateur porn",
            "onlyfans",
            "pornhub",
            "xvideos",
            "xnxx",
            "redtube",
            "youporn",
            "xhamster",
            "spankbang",
            "motherless",
            "chaturbate",
            "brazzers",
            "bangbros",
        )
    }
}
