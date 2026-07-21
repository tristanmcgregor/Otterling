package au.com.tbmcgregor.bwparker.familyguard.schedule

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek

/**
 * A recurring window during which [packageNames] are allowed.
 *
 * [daysOfWeekMask] uses bit 0 = Monday .. bit 6 = Sunday (see [dayBit]).
 * [startMinuteOfDay] / [endMinuteOfDay] are minutes since midnight (0-1439). If
 * [endMinuteOfDay] < [startMinuteOfDay] the window wraps past midnight (e.g. bedtime 21:00-07:00).
 */
@Entity(tableName = "schedule_rules")
data class ScheduleRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val daysOfWeekMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val packageNames: String,
    val enabled: Boolean = true,
) {
    val packageList: List<String>
        get() = packageNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isActiveAt(dayOfWeek: DayOfWeek, minuteOfDay: Int): Boolean {
        if (!enabled) return false
        return if (startMinuteOfDay <= endMinuteOfDay) {
            (daysOfWeekMask and dayBit(dayOfWeek)) != 0 &&
                minuteOfDay in startMinuteOfDay until endMinuteOfDay
        } else {
            if (minuteOfDay >= startMinuteOfDay) {
                (daysOfWeekMask and dayBit(dayOfWeek)) != 0
            } else if (minuteOfDay < endMinuteOfDay) {
                val previousDay = dayOfWeek.minus(1)
                (daysOfWeekMask and dayBit(previousDay)) != 0
            } else {
                false
            }
        }
    }

    companion object {
        fun dayBit(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

        const val ALL_DAYS_MASK = 0b1111111
    }
}
