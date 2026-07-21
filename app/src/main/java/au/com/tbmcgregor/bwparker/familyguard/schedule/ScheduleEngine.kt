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

    suspend fun delete(id: Long) {
        val removedPackages = dao.getById(id)?.packageList.orEmpty()
        dao.delete(id)
        val stillScheduled = dao.getAll().flatMapTo(mutableSetOf()) { it.packageList }
        val permanentlyBlocked = suspensionManager.blockedApps()
            .filter { it.blocked }
            .mapTo(mutableSetOf()) { it.packageName }
        (removedPackages - stillScheduled - permanentlyBlocked).forEach {
            suspensionManager.applyTemporarySuspension(it, suspended = false)
        }
    }

    /** Re-evaluates all rules against [now] and applies/releases suspensions. */
    suspend fun applyNow(now: LocalDateTime = LocalDateTime.now()) {
        val rules = dao.getAll()
        val minuteOfDay = now.hour * 60 + now.minute

        val allScheduledPackages = rules.flatMapTo(mutableSetOf()) { it.packageList }
        val enabledPackages = rules.filter { it.enabled }.flatMapTo(mutableSetOf()) { it.packageList }
        val currentlyAllowed = rules
            .filter { it.isActiveAt(now.dayOfWeek, minuteOfDay) }
            .flatMapTo(mutableSetOf()) { it.packageList }
        val toSuspend = enabledPackages - currentlyAllowed
        val toRelease = (allScheduledPackages - toSuspend).toMutableSet()

        val permanentlyBlocked = suspensionManager.blockedApps()
            .filter { it.blocked }
            .map { it.packageName }
            .toSet()
        toRelease -= permanentlyBlocked

        toSuspend.forEach { suspensionManager.applyTemporarySuspension(it, suspended = true) }
        toRelease.forEach { suspensionManager.applyTemporarySuspension(it, suspended = false) }

        Log.i(TAG, "Applied ${rules.size} schedule rules: ${toSuspend.size} suspended, ${toRelease.size} released")
    }

    private companion object {
        const val TAG = "ScheduleEngine"
    }
}
