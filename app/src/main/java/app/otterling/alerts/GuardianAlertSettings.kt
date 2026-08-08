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
        // Bump the trailing version (_v1 -> _v2 -> ...) whenever DEFAULT_TRIGGER_WORDS grows, so
        // the merge in seedDefaultTriggerWordsIfNeeded() runs one more time for installs that
        // already seeded an earlier version -- otherwise an expanded list would only ever reach
        // brand-new installs, never existing ones.
        private const val KEY_SEEDED_DEFAULT_TRIGGERS = "seeded_default_triggers_v2"
        const val DEBOUNCE_MS = 10 * 60_000L

        /**
         * Starts from the same low-false-positive keyword set the server-side filter's title/page
         * check already uses (mitm_nsfw_addon.py's TITLE_KEYWORDS) plus well-known site/service
         * names, then adds a curated subset of the public LDNOOBW word list
         * (github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) -- NOT the
         * whole thing. That list mixes genuinely explicit terms with generic profanity ("fuck",
         * "shit", "bitch"), unrelated slurs, and ambiguous clinical/common words ("ass", "cock",
         * "cum", "tit", "dick", "vagina", "viagra") that would either misfire constantly against
         * this app's plain substring match (bare "cum" matches "cucumber"/"document"; bare "ass"
         * matches "class"/"assignment") or flag things that have nothing to do with adult content
         * at all. Only specific, unambiguous, multi-character explicit terms/phrases were kept.
         */
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
            // Curated additions from LDNOOBW (see doc comment above for what was excluded/why).
            "2g1c",
            "alabama hot pocket",
            "anilingus",
            "autoerotic",
            "ball gag",
            "bareback",
            "barely legal",
            "bdsm",
            "beastiality",
            "bestiality",
            "big tits",
            "blowjob",
            "blow job",
            "blue waffle",
            "bondage",
            "bukkake",
            "camgirl",
            "camslut",
            "camwhore",
            "cleveland steamer",
            "clitoris",
            "creampie",
            "cumshot",
            "cunnilingus",
            "deepthroat",
            "deep throat",
            "dildo",
            "doggystyle",
            "doggy style",
            "dominatrix",
            "double penetration",
            "ejaculation",
            "erotica",
            "fellatio",
            "fisting",
            "futanari",
            "gangbang",
            "gang bang",
            "gokkun",
            "golden shower",
            "hardcore porn",
            "incest porn",
            "jailbait",
            "jizz",
            "lolita",
            "masturbate",
            "masturbating",
            "masturbation",
            "milf",
            "missionary position",
            "nympho",
            "nymphomania",
            "orgy",
            "paedophile",
            "pedophile",
            "pegging",
            "prostitute",
            "rimjob",
            "semen",
            "sex tape",
            "sexcam",
            "squirting",
            "strapon",
            "strap on",
            "swinger",
            "threesome",
            "upskirt",
            "voyeur",
            "webcam sex",
            "cybersex",
        )
    }
}
