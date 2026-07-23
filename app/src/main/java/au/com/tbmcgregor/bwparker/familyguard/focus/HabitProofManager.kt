package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.io.File
import java.time.LocalDate

/**
 * Anti-cheat layer on top of [HabitTrackerScanner] detection: lets specific habit names be marked
 * as requiring same-day photo proof before their "done" tick is trusted by [HabitRuleManager].
 * Proof isn't just "any photo" -- [setRequirement] captures one reference photo up front (taken
 * when the requirement is first turned on, e.g. from the rule-builder wizard), and every day's
 * submitted photo must visually match it (see [ImageMatcher], enforced by [HabitProofActivity])
 * before [recordProof] is ever called. We can't see or control HabitShare's own state, only
 * whether *our* unlocks fire, so this only ever makes a rule *harder* to satisfy, never easier.
 */
class HabitProofManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).habitProofDao()

    suspend fun requirements(): List<HabitProofRequirement> = dao.getAllRequirements()

    suspend fun requirement(habitName: String): HabitProofRequirement? = dao.getRequirement(habitName.trim())

    suspend fun isRequired(habitName: String): Boolean =
        dao.getRequirement(habitName.trim())?.required == true

    /** Turns proof on (with the reference photo just taken) or off (clearing any reference photo)
     * for [habitName]. */
    suspend fun setRequirement(habitName: String, required: Boolean, referencePhotoPath: String?) {
        dao.upsertRequirement(
            HabitProofRequirement(
                habitName = habitName.trim(),
                required = required,
                referencePhotoPath = if (required) referencePhotoPath else null,
            ),
        )
    }

    suspend fun hasProofToday(habitName: String): Boolean =
        dao.getLog(habitName.trim(), LocalDate.now().toEpochDay()) != null

    /** Called only once a submitted photo has already been checked to visually match the
     * reference photo (see [ImageMatcher]) -- this is the "approved" record itself. */
    suspend fun recordProof(habitName: String, photoPath: String, note: String = "") {
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

    /** Removes one day's approved proof -- e.g. it was approved by mistake, or you want to force
     * re-proving. Caller is responsible for re-running [HabitRuleManager.reapplyAll] afterwards,
     * since removing today's log can put a target app back into a blocked state. */
    suspend fun deleteLog(habitName: String, dateEpochDay: Long) {
        dao.deleteLog(habitName.trim(), dateEpochDay)
    }

    /** A requirement flagged "required" but missing its reference photo can never actually be
     * matched against anything -- a relic of an old build that allowed enabling proof without one,
     * or a still-in-progress toggle. Treated as not-yet-really-required until a reference photo is
     * (re-)set, so it neither blocks nor triggers [HabitProofActivity] until then. */
    private fun List<HabitProofRequirement>.actuallyRequired() =
        filter { it.required && !it.referencePhotoPath.isNullOrBlank() }

    /**
     * Filters [doneNamesLowercase] (already-lowercased habit names the scanner saw ticked today)
     * down to only those that either don't require proof, or already have a same-day proof log --
     * i.e. the set [HabitRuleManager] should actually trust when evaluating rule conditions.
     */
    suspend fun filterSatisfied(doneNamesLowercase: Set<String>): Set<String> {
        val requirements = dao.getAllRequirements().actuallyRequired()
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
        val requirements = dao.getAllRequirements().actuallyRequired()
        if (requirements.isEmpty()) return emptyList()
        val today = LocalDate.now().toEpochDay()
        val loggedToday = dao.logsForDate(today).map { it.habitName.lowercase() }.toSet()
        val requiredLower = requirements.map { it.habitName.lowercase() }.toSet()
        return doneNamesRaw.filter { it.lowercase() in requiredLower && it.lowercase() !in loggedToday }
    }

    companion object {
        /** Per-habit directory holding every reference photo captured for it. Multiple references
         * (e.g. different angles) make genuine matches far more forgiving without loosening the
         * similarity threshold, which is the biggest single win against false rejects. */
        fun referenceDir(context: Context, habitName: String): File {
            val safeName = habitName.trim().replace(Regex("[^A-Za-z0-9]"), "_").take(60)
            return File(File(context.filesDir, "habit_refs"), safeName).apply { mkdirs() }
        }

        /** All readable reference photos for [habitName], newest first. Migrates a legacy single
         * reference at [legacyPrimaryPath] into the per-habit dir if the dir is otherwise empty. */
        fun referenceFiles(context: Context, habitName: String, legacyPrimaryPath: String? = null): List<File> {
            val dir = referenceDir(context, habitName)
            val inDir = dir.listFiles { f -> f.isFile && f.length() > 0 }?.toList().orEmpty()
            if (inDir.isNotEmpty()) return inDir.sortedByDescending { it.lastModified() }
            val legacy = legacyPrimaryPath?.let(::File)
            return if (legacy != null && legacy.exists() && legacy.length() > 0) listOf(legacy) else emptyList()
        }

        /** A fresh file path to capture the next reference photo into. */
        fun newReferenceFile(context: Context, habitName: String): File =
            File(referenceDir(context, habitName), "ref_${System.currentTimeMillis()}.jpg")

        /** Deletes every reference photo for [habitName]. */
        fun clearReferences(context: Context, habitName: String) {
            referenceDir(context, habitName).listFiles()?.forEach { it.delete() }
        }

        fun deleteReference(file: File) {
            runCatching { file.delete() }
        }
    }
}
