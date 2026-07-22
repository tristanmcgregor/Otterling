package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.LocalDate

/**
 * A small command system: "when (a habit/completion pattern shows up in app A) -> unlock app B
 * for N minutes", with as many independent rules as you like. Detection itself is still the same
 * on-screen-text heuristic [FocusGuardAccessibilityService] already uses for the single legacy
 * [HabitGateManager] gate -- this class just lets that trigger fan out to many
 * trigger-app/target-app/duration combinations instead of one hardcoded one.
 *
 * Every [targetPackageName] referenced by a rule is treated like a [RewardApp]: suspended by
 * default, and only unsuspended while at least one of its rules has an active unlock window.
 * [reapplyAll] re-derives and re-asserts that suspended state from scratch every time it's called,
 * so it's safe (and expected) to call it periodically for drift recovery, exactly like the other
 * enforcement managers.
 */
class HabitRuleManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).habitRuleDao()
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    suspend fun rules(): List<HabitRule> = dao.getAll()

    /** [habitName] null means "any/all habits complete" (the original whole-tracker pattern). */
    suspend fun addRule(
        triggerPackageName: String,
        targetPackageName: String,
        unlockMinutes: Int,
        habitName: String? = null,
    ) {
        dao.insert(
            HabitRule(
                triggerPackageName = triggerPackageName,
                targetPackageName = targetPackageName,
                unlockMinutes = unlockMinutes,
                habitName = habitName,
            ),
        )
        reapplyAll()
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.getAll().find { it.id == id }?.let { dao.update(it.copy(enabled = enabled)) }
        reapplyAll()
    }

    suspend fun deleteRule(id: Long) {
        dao.delete(id)
        reapplyAll()
    }

    /**
     * Called by the accessibility service once it has scanned [triggerPackageName]'s screen into
     * [texts] (whole-screen text, for the "all complete" pattern) and [detectedHabitRows] (name +
     * done-today pairs from [HabitTrackerScanner], for single-habit rules). Grants (at most once
     * per calendar day, per rule) every enabled rule whose trigger matches and whose condition is
     * met, and returns how many fired -- useful for a "you just unlocked N app(s)" toast.
     * Idempotent: safe to call on every scan.
     */
    suspend fun evaluateTrigger(
        triggerPackageName: String,
        texts: List<String>,
        detectedHabitRows: List<Pair<String, Boolean>>,
    ): Int {
        val today = LocalDate.now().toEpochDay()
        val candidates = dao.forTrigger(triggerPackageName).filter { it.lastGrantedEpochDay != today }
        if (candidates.isEmpty()) return 0

        val allComplete = looksLikeAllComplete(texts)
        val doneHabitNames = detectedHabitRows.filter { it.second }.map { it.first.lowercase() }

        val now = System.currentTimeMillis()
        var grantedCount = 0
        candidates.forEach { rule ->
            val fires = when (val habitName = rule.habitName?.lowercase()) {
                null -> allComplete
                else -> doneHabitNames.any { it.contains(habitName) || habitName.contains(it) }
            }
            if (!fires) return@forEach
            val newUntil = maxOf(rule.unlockUntilMillis, now) + rule.unlockMinutes * 60_000L
            dao.update(rule.copy(lastGrantedEpochDay = today, unlockUntilMillis = newUntil))
            grantedCount++
        }
        if (grantedCount > 0) reapplyAll()
        return grantedCount
    }

    /** Re-derives suspended state for every target app from scratch. Call periodically. */
    suspend fun reapplyAll() {
        val now = System.currentTimeMillis()
        dao.getAll().groupBy { it.targetPackageName }.forEach { (packageName, rulesForTarget) ->
            val unlocked = rulesForTarget.any { it.unlockUntilMillis > now }
            setSuspended(packageName, suspended = !unlocked)
        }
    }

    private fun setSuspended(packageName: String, suspended: Boolean) {
        val dpm = devicePolicyManager ?: return
        try {
            dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspended)
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to suspend $packageName", error)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot suspend $packageName (not installed?)", error)
        }
    }

    /** Matches common "3/3" or "3 of 3" completion-counter phrasing where done == total > 0. */
    private fun looksLikeAllComplete(texts: List<String>): Boolean {
        val pattern = Regex("""(\d+)\s*(?:/|of)\s*(\d+)""", RegexOption.IGNORE_CASE)
        return texts.any { text ->
            val match = pattern.find(text) ?: return@any false
            val (done, total) = match.destructured
            val totalValue = total.toIntOrNull() ?: return@any false
            totalValue > 0 && done.toIntOrNull() == totalValue
        }
    }

    private companion object {
        const val TAG = "HabitRuleManager"
    }
}
