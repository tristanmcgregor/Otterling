package au.com.tbmcgregor.bwparker.familyguard.focus

/**
 * Extracts individual habit rows (name + done-today state) from a habit tracker app's
 * accessibility tree, given an in-order flattening of every node into an [Entry]. There's no
 * public API for most habit trackers (e.g. HabitShare), so this is inherently a heuristic: it
 * pairs each checkbox/toggle-like node with the nearest text label in traversal order, on the
 * assumption that a habit list renders as repeated "[name][checkbox]" or "[checkbox][name]" rows.
 * Tune against real screens using the debug capture/detected-habits list in Settings if rows come
 * out missing or misattributed.
 */
object HabitTrackerScanner {
    /** One flattened accessibility node: its text (if any), and whether/how it's checked. */
    data class Entry(val text: String?, val checkable: Boolean, val checked: Boolean)

    private const val SEARCH_WINDOW = 6

    fun extractRows(entries: List<Entry>): List<Pair<String, Boolean>> {
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
