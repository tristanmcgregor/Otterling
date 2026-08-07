package app.otterling.focus

import android.content.Context
import app.otterling.monitoring.ProtectionController
import android.util.Log

/**
 * Feeds [HabitShareApiClient] results through the habit pipeline -- [DetectedHabitManager.recordScan]
 * (so Settings' "detected habits" list and proof requirements keep working) and
 * [HabitRuleManager.evaluateTrigger] (so non-windowed "unlock for N minutes" rules still grant).
 * The REST API is the sole source of habit completion data. Called periodically from
 * [app.otterling.monitoring.ProtectionEnforcementService] whenever a
 * HabitShare account is connected, keeping completion status always fresh rather than only
 * updating when the tracker app happens to be open.
 */
class HabitShareSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val apiClient = HabitShareApiClient(context)
    private val detectedHabitManager = DetectedHabitManager(context)
    private val habitRuleManager = HabitRuleManager(context)
    private val proofManager = HabitProofManager(context)

    suspend fun syncIfConnected() {
        if (!ProtectionController(appContext).isEnabled()) return
        if (!apiClient.isConnected()) return
        val rows = apiClient.fetchTodayCompletions() ?: run {
            Log.w(TAG, "HabitShare sync skipped this cycle (fetch failed)")
            return
        }
        if (rows.isEmpty()) return
        detectedHabitManager.recordScan(rows)
        promptForProofIfNeeded(rows)
        // Non-windowed "unlock for N minutes" rules fire here...
        habitRuleManager.evaluateTrigger(HabitTrackerScanner.HABITSHARE_PACKAGE_NAME, rows)
        // ...but time-windowed rules are driven purely by reapplyAll (evaluateTrigger skips them),
        // and it otherwise only runs on the enforcement service's slow ~5-min loop. Call it here so
        // a habit ticked in HabitShare lifts/asserts a windowed block within one 30s sync instead
        // of waiting up to five minutes.
        habitRuleManager.reapplyAll()
    }

    /** Surfaces the photo-proof prompt for the first proof-required habit that's newly done but not
     * yet verified today. [HabitProofPrompter] debounces per habit+day, so this is safe to call on
     * every (once-a-second) sync -- it won't relaunch the camera in a loop. */
    private suspend fun promptForProofIfNeeded(rows: List<Pair<String, Boolean>>) {
        val doneNamesRaw = rows.filter { it.second }.map { it.first }
        val needsProof = proofManager.namesNeedingProof(doneNamesRaw)
        for (habitName in needsProof) {
            if (HabitProofPrompter.promptFor(appContext, habitName)) break
        }
    }

    private companion object {
        const val TAG = "HabitShareSyncManager"
    }
}
