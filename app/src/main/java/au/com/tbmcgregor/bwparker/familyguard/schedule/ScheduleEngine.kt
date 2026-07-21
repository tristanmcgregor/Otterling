package au.com.tbmcgregor.bwparker.familyguard.schedule

import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.LocalDateTime

/**
 * Evaluates [ScheduleRule]s against the current time and suspends/releases the packages they
 * name accordingly. Runs independently of [AppSuspensionManager]'s permanent block list -- a
 * package that's permanently blocked stays suspended even if no schedule rule currently covers it.
 */
class ScheduleEngine(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).scheduleRuleDao()
    private val suspensionManager = AppSuspensionManager(context)

    suspend fun rules(): List<ScheduleRule> = dao.getAll()

    suspend fun upsert(rule: ScheduleRule) = dao.upsert(rule)

    suspend fun delete(id: Long) = dao.delete(id)

    /** Re-evaluates all rules against [now] and applies/releases suspensions. */
    suspend fun applyNow(now: LocalDateTime = LocalDateTime.now()) {
        val rules = dao.getAll()
        val minuteOfDay = now.hour * 60 + now.minute

        val toSuspend = mutableSetOf<String>()
        val toRelease = mutableSetOf<String>()
        rules.forEach { rule ->
            val target = if (rule.isActiveAt(now.dayOfWeek, minuteOfDay)) toSuspend else toRelease
            target += rule.packageList
        }

        val permanentlyBlocked = suspensionManager.blockedApps()
            .filter { it.blocked }
            .map { it.packageName }
            .toSet()
        toRelease -= permanentlyBlocked
        toRelease -= toSuspend

        toSuspend.forEach { suspensionManager.applyTemporarySuspension(it, suspended = true) }
        toRelease.forEach { suspensionManager.applyTemporarySuspension(it, suspended = false) }

        Log.i(TAG, "Applied ${rules.size} schedule rules: ${toSuspend.size} suspended, ${toRelease.size} released")
    }

    private companion object {
        const val TAG = "ScheduleEngine"
    }
}
