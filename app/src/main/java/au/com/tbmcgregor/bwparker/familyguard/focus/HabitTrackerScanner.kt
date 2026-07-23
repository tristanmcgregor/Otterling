package au.com.tbmcgregor.bwparker.familyguard.focus

/**
 * Constants describing the habit tracker this app integrates with. Habit completion itself is read
 * exclusively from HabitShare's REST API (see [HabitShareApiClient] / [HabitShareSyncManager]);
 * there is no longer any on-screen/accessibility parsing of the tracker.
 */
object HabitTrackerScanner {
    /** The only habit tracker this app knows how to read -- habit rules always gate on this app,
     * so the rule-builder UI never needs to ask which tracker to use. */
    const val HABITSHARE_PACKAGE_NAME = "com.habitshareapp"
}
