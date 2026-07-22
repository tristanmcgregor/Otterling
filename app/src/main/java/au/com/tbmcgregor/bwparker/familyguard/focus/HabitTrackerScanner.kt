package au.com.tbmcgregor.bwparker.familyguard.focus

/**
 * Extracts individual habit rows (name + done-today state) from a habit tracker app's
 * accessibility tree, given an in-order flattening of every node into an [Entry]. There's no
 * public API for most habit trackers, so this is inherently heuristic, and uses whichever of two
 * patterns actually matches the screen:
 *
 * 1. A "streak summary" content description on the row itself, e.g. HabitShare's
 *    "Attend supper, Streak: +1  |  Overall: 83%  |  1 Friend" -- the habit's name is everything
 *    before ", Streak:". This reliably gives the habit *name*, but NOT done-today: HabitShare
 *    doesn't expose per-day completion as a checkable/checked node, and the streak count does not
 *    correlate with today specifically (verified against a real device: habits with a non-zero
 *    streak were confirmed not done today). Done-today defaults to false here and is overridden by
 *    [FocusGuardAccessibilityService]'s screenshot-based colour check of the "today" day cell,
 *    which is the only place the state actually exists (as cell colour, not accessibility metadata).
 * 2. Falls back to pairing each checkbox/toggle-like node with the nearest text label in
 *    traversal order, for trackers that *do* use standard checkable widgets.
 *
 * Tune against real screens using the debug capture/detected-habits list in Settings if rows come
 * out missing or misattributed.
 */
object HabitTrackerScanner {
    /** One flattened accessibility node: its text, content description, and checkable/checked state. */
    data class Entry(
        val text: String?,
        val contentDescription: String? = null,
        val checkable: Boolean = false,
        val checked: Boolean = false,
    )

    private const val SEARCH_WINDOW = 6
    private val STREAK_SUMMARY_PATTERN = Regex("""^(.+?),\s*Streak:""")

    fun extractRows(entries: List<Entry>): List<Pair<String, Boolean>> {
        val streakRows = extractStreakSummaryRows(entries)
        if (streakRows.isNotEmpty()) return streakRows
        return extractCheckableRows(entries)
    }

    private fun extractStreakSummaryRows(entries: List<Entry>): List<Pair<String, Boolean>> {
        val rows = mutableListOf<Pair<String, Boolean>>()
        entries.forEach { entry ->
            val match = STREAK_SUMMARY_PATTERN.find(entry.contentDescription ?: return@forEach) ?: return@forEach
            val name = match.groupValues[1].trim().takeIf { it.isNotBlank() } ?: return@forEach
            rows += name to false
        }
        return rows
    }

    private fun extractCheckableRows(entries: List<Entry>): List<Pair<String, Boolean>> {
        val rows = mutableListOf<Pair<String, Boolean>>()
        entries.forEachIndexed { index, entry ->
            if (!entry.checkable) return@forEachIndexed
            val label = nearestText(entries, index) ?: return@forEachIndexed
            rows += label to entry.checked
        }
        return rows
    }

    /** Searches outward from [index] in both directions, closest distance first. */
    private fun nearestText(entries: List<Entry>, index: Int): String? {
        for (offset in 1..SEARCH_WINDOW) {
            entries.getOrNull(index - offset)?.text?.takeIf { it.isNotBlank() }?.let { return it }
            entries.getOrNull(index + offset)?.text?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
