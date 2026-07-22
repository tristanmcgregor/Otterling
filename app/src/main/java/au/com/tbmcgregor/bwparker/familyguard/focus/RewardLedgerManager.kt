package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase

/**
 * Tracks unspent reward minutes earned from completed focus sessions and habit check-ins, and
 * lets you spend them to temporarily unsuspend [RewardApp]s. Spending while an unlock window is
 * already active extends it, rather than restarting it.
 */
class RewardLedgerManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).rewardLedgerDao()
    private val rewardAppManager = RewardAppManager(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun earnedMinutes(): Int = dao.get()?.earnedMinutesRemaining ?: 0

    suspend fun addMinutes(minutes: Int) {
        if (minutes <= 0) return
        dao.upsert(RewardLedger(earnedMinutesRemaining = earnedMinutes() + minutes))
    }

    /** Spends [minutes] from the balance, unsuspending reward apps for that long. */
    suspend fun spend(minutes: Int): Boolean {
        val current = earnedMinutes()
        if (minutes <= 0 || minutes > current) return false
        dao.upsert(RewardLedger(earnedMinutesRemaining = current - minutes))
        val base = maxOf(activeUnlockUntilMillis(), System.currentTimeMillis())
        prefs.edit().putLong(KEY_UNLOCK_UNTIL, base + minutes * 60_000L).apply()
        rewardAppManager.setAllSuspended(false)
        return true
    }

    fun activeUnlockUntilMillis(): Long = prefs.getLong(KEY_UNLOCK_UNTIL, 0L)

    fun isCurrentlyUnlocked(): Boolean = System.currentTimeMillis() < activeUnlockUntilMillis()

    /** Call periodically -- re-suspends reward apps once the unlock window has elapsed. */
    suspend fun reapply() {
        rewardAppManager.setAllSuspended(!isCurrentlyUnlocked())
    }

    private companion object {
        const val PREFS_NAME = "reward_ledger_state"
        const val KEY_UNLOCK_UNTIL = "unlock_until_millis"
    }
}
