package au.com.tbmcgregor.bwparker.familyguard.focus

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RewardAppDao {
    @Query("SELECT * FROM reward_apps ORDER BY packageName")
    suspend fun getAll(): List<RewardApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: RewardApp)

    @Query("DELETE FROM reward_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface MindfulAppDao {
    @Query("SELECT * FROM mindful_apps ORDER BY packageName")
    suspend fun getAll(): List<MindfulApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: MindfulApp)

    @Query("DELETE FROM mindful_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface AppTimeBudgetDao {
    @Query("SELECT * FROM app_time_budgets ORDER BY packageName")
    suspend fun getAll(): List<AppTimeBudget>

    @Query("SELECT * FROM app_time_budgets WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppTimeBudget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: AppTimeBudget)

    @Query("DELETE FROM app_time_budgets WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface AppUsageCounterDao {
    @Query("SELECT * FROM app_usage_counters WHERE packageName = :packageName AND dateEpochDay = :dateEpochDay")
    suspend fun get(packageName: String, dateEpochDay: Long): AppUsageCounter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(counter: AppUsageCounter)

    @Query("DELETE FROM app_usage_counters WHERE dateEpochDay < :beforeEpochDay")
    suspend fun deleteOlderThan(beforeEpochDay: Long)
}

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insert(session: FocusSession): Long

    @Query("UPDATE focus_sessions SET endedAtMillis = :endedAtMillis, completed = :completed WHERE id = :id")
    suspend fun finish(id: Long, endedAtMillis: Long, completed: Boolean)

    @Query("SELECT * FROM focus_sessions ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<FocusSession>
}

@Dao
interface HabitGateStateDao {
    @Query("SELECT * FROM habit_gate_state WHERE dateEpochDay = :dateEpochDay")
    suspend fun get(dateEpochDay: Long): HabitGateState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: HabitGateState)
}

@Dao
interface RewardLedgerDao {
    @Query("SELECT * FROM reward_ledger WHERE id = 0")
    suspend fun get(): RewardLedger?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ledger: RewardLedger)
}

@Dao
interface HabitRuleDao {
    @Query("SELECT * FROM habit_rules ORDER BY id")
    suspend fun getAll(): List<HabitRule>

    @Query("SELECT * FROM habit_rules WHERE triggerPackageName = :triggerPackageName AND enabled = 1")
    suspend fun forTrigger(triggerPackageName: String): List<HabitRule>

    @Insert
    suspend fun insert(rule: HabitRule): Long

    @androidx.room.Update
    suspend fun update(rule: HabitRule)

    @Query("DELETE FROM habit_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DetectedHabitDao {
    @Query("SELECT * FROM detected_habits ORDER BY name")
    suspend fun getAll(): List<DetectedHabit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(habit: DetectedHabit)
}
