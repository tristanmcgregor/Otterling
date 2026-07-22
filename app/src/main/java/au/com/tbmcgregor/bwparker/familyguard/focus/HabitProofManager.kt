package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.LocalDate

/**
 * Anti-cheat layer on top of [HabitTrackerScanner] detection: lets specific habit names be marked
 * as requiring same-day photo proof (a picture plus a short note, e.g. which chapter was read)
 * before their "done" tick is trusted by [HabitRuleManager]. We can't see or control HabitShare's
 * own state, only whether *our* unlocks fire, so this only ever makes a rule *harder* to satisfy,
 * never easier.
 */
class HabitProofManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).habitProofDao()

    suspend fun requirements(): List<HabitProofRequirement> = dao.getAllRequirements()

    suspend fun isRequired(habitName: String): Boolean =
        dao.getRequirement(habitName.trim())?.required == true

    suspend fun setRequired(habitName: String, required: Boolean) {
        dao.upsertRequirement(HabitProofRequirement(habitName.trim(), required))
    }

    suspend fun hasProofToday(habitName: String): Boolean =
        dao.getLog(habitName.trim(), LocalDate.now().toEpochDay()) != null

    suspend fun recordProof(habitName: String, photoPath: String, note: String) {
        dao.upsertLog(
            HabitProofLog(
                habitName = habitName.trim(),
                dateEpochDay = LocalDate.now().toEpochDay(),
                photoPath = photoPath,
                note = note,
                submittedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recentLogs(limit: Int = 50): List<HabitProofLog> = dao.recentLogs(limit)

    /**
     * Filters [doneNamesLowercase] (already-lowercased habit names the scanner saw ticked today)
     * down to only those that either don't require proof, or already have a same-day proof log --
     * i.e. the set [HabitRuleManager] should actually trust when evaluating rule conditions.
     */
    suspend fun filterSatisfied(doneNamesLowercase: Set<String>): Set<String> {
        val requirements = dao.getAllRequirements().filter { it.required }
        if (requirements.isEmpty() || doneNamesLowercase.isEmpty()) return doneNamesLowercase
        val today = LocalDate.now().toEpochDay()
        val loggedToday = dao.logsForDate(today).map { it.habitName.lowercase() }.toSet()
        val requiredLower = requirements.map { it.habitName.lowercase() }.toSet()
        return doneNamesLowercase.filterNot { it in requiredLower && it !in loggedToday }.toSet()
    }

    /** Of [doneNamesRaw] (raw-cased habit names the scanner saw ticked today), which ones require
     * proof but don't have one yet today -- i.e. should prompt [HabitProofActivity]. */
    suspend fun namesNeedingProof(doneNamesRaw: List<String>): List<String> {
        if (doneNamesRaw.isEmpty()) return emptyList()
        val requirements = dao.getAllRequirements().filter { it.required }
        if (requirements.isEmpty()) return emptyList()
        val today = LocalDate.now().toEpochDay()
        val loggedToday = dao.logsForDate(today).map { it.habitName.lowercase() }.toSet()
        val requiredLower = requirements.map { it.habitName.lowercase() }.toSet()
        return doneNamesRaw.filter { it.lowercase() in requiredLower && it.lowercase() !in loggedToday }
    }
}
