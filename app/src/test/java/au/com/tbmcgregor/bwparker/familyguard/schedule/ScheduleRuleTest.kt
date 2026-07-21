package au.com.tbmcgregor.bwparker.familyguard.schedule

import java.time.DayOfWeek
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRuleTest {
    @Test
    fun daytimeWindowAllowsOnlySelectedDayAndTime() {
        val rule = rule(
            days = ScheduleRule.dayBit(DayOfWeek.MONDAY),
            start = 8 * 60,
            end = 17 * 60,
        )

        assertTrue(rule.isActiveAt(DayOfWeek.MONDAY, 9 * 60))
        assertFalse(rule.isActiveAt(DayOfWeek.MONDAY, 18 * 60))
        assertFalse(rule.isActiveAt(DayOfWeek.TUESDAY, 9 * 60))
    }

    @Test
    fun overnightWindowCarriesIntoFollowingDay() {
        val rule = rule(
            days = ScheduleRule.dayBit(DayOfWeek.MONDAY),
            start = 21 * 60,
            end = 7 * 60,
        )

        assertTrue(rule.isActiveAt(DayOfWeek.MONDAY, 22 * 60))
        assertTrue(rule.isActiveAt(DayOfWeek.TUESDAY, 6 * 60))
        assertFalse(rule.isActiveAt(DayOfWeek.TUESDAY, 8 * 60))
        assertFalse(rule.isActiveAt(DayOfWeek.WEDNESDAY, 6 * 60))
    }

    @Test
    fun disabledRuleNeverAllowsAccess() {
        val rule = rule(
            days = ScheduleRule.ALL_DAYS_MASK,
            start = 0,
            end = 23 * 60,
            enabled = false,
        )

        assertFalse(rule.isActiveAt(DayOfWeek.MONDAY, 12 * 60))
    }

    private fun rule(days: Int, start: Int, end: Int, enabled: Boolean = true) = ScheduleRule(
        label = "Test",
        daysOfWeekMask = days,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        packageNames = "com.example.app",
        enabled = enabled,
    )
}
