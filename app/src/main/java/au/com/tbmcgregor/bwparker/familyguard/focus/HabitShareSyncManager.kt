package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import android.util.Log

/**
 * Feeds [HabitShareApiClient] results through the habit pipeline -- [DetectedHabitManager.recordScan]
 * (so Settings' "detected habits" list and proof requirements keep working) and
 * [HabitRuleManager.evaluateTrigger] (so non-windowed "unlock for N minutes" rules still grant).
 * The REST API is the sole source of habit completion data. Called periodically from
 * [au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionEnforcementService] whenever a
 * HabitShare account is connected, keeping completion status always fresh rather than only
 * updating when the tracker app happens to be open.
 */
class HabitShareSyncManager(context: Context) {
    private val apiClient = HabitShareApiClient(context)
    private val detectedHabitManager = DetectedHabitManager(context)
    private val habitRuleManager = HabitRuleManager(context)

    suspend fun syncIfConnected() {
        if (!apiClient.isConnected()) return
        val rows = apiClient.fetchTodayCompletions() ?: run {
            Log.w(TAG, "HabitShare sync skipped this cycle (fetch failed)")
            return
        }
        if (rows.isEmpty()) return
        detectedHabitManager.recordScan(rows)
        // Non-windowed "unlock for N minutes" rules fire here...
        habitRuleManager.evaluateTrigger(HabitTrackerScanner.HABITSHARE_PACKAGE_NAME, rows)
        // ...but time-windowed rules are driven purely by reapplyAll (evaluateTrigger skips them),
        // and it otherwise only runs on the enforcement service's slow ~5-min loop. Call it here so
        // a habit ticked in HabitShare lifts/asserts a windowed block within one 30s sync instead
        // of waiting up to five minutes.
        habitRuleManager.reapplyAll()
    }

    private companion object {
        const val TAG = "HabitShareSyncManager"
    }
}
