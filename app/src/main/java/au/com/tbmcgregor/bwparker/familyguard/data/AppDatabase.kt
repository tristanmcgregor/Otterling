package au.com.tbmcgregor.bwparker.familyguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudget
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetDao
import au.com.tbmcgregor.bwparker.familyguard.focus.AppUsageCounter
import au.com.tbmcgregor.bwparker.familyguard.focus.AppUsageCounterDao
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabit
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabitDao
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusSession
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusSessionDao
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRule
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleDao
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulApp
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulAppDao
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardApp
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardAppDao
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedger
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedgerDao
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEvent
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventDao

@Database(
    entities = [
        BlockedApp::class,
        TamperEvent::class,
        ProtectedApp::class,
        RewardApp::class,
        MindfulApp::class,
        AppTimeBudget::class,
        AppUsageCounter::class,
        FocusSession::class,
        RewardLedger::class,
        HabitRule::class,
        DetectedHabit::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao

    abstract fun tamperEventDao(): TamperEventDao

    abstract fun protectedAppDao(): ProtectedAppDao

    abstract fun rewardAppDao(): RewardAppDao

    abstract fun mindfulAppDao(): MindfulAppDao

    abstract fun appTimeBudgetDao(): AppTimeBudgetDao

    abstract fun appUsageCounterDao(): AppUsageCounterDao

    abstract fun focusSessionDao(): FocusSessionDao

    abstract fun rewardLedgerDao(): RewardLedgerDao

    abstract fun habitRuleDao(): HabitRuleDao

    abstract fun detectedHabitDao(): DetectedHabitDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_guard.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                    )
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS schedule_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        daysOfWeekMask INTEGER NOT NULL,
                        startMinuteOfDay INTEGER NOT NULL,
                        endMinuteOfDay INTEGER NOT NULL,
                        packageNames TEXT NOT NULL,
                        enabled INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_usage_stats (
                        packageName TEXT NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        totalForegroundMillis INTEGER NOT NULL,
                        PRIMARY KEY(packageName, dateEpochDay)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tamper_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        details TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_usage_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        endedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        // Scheduled access windows feature was removed; drop the now-unused table.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS schedule_rules")
            }
        }

        // Usage logging & reporting feature was removed; drop the now-unused tables.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS app_usage_stats")
                db.execSQL("DROP TABLE IF EXISTS app_usage_sessions")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS protected_apps (
                        packageName TEXT NOT NULL,
                        PRIMARY KEY(packageName)
                    )
                    """.trimIndent(),
                )
            }
        }

        // Self-improvement features: reward-gated apps, friction/"mindful" apps, per-app time
        // budgets (with an optional stricter sub-limit for a heuristically-detected in-app
        // feature, e.g. YouTube Shorts), focus sessions, and the habit-tracker reward gate.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reward_apps (
                        packageName TEXT NOT NULL,
                        PRIMARY KEY(packageName)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mindful_apps (
                        packageName TEXT NOT NULL,
                        delaySeconds INTEGER NOT NULL,
                        PRIMARY KEY(packageName)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_time_budgets (
                        packageName TEXT NOT NULL,
                        dailyLimitMinutes INTEGER NOT NULL,
                        subLimitMinutes INTEGER,
                        subLimitLabel TEXT,
                        PRIMARY KEY(packageName)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_usage_counters (
                        packageName TEXT NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        totalSeconds INTEGER NOT NULL,
                        subSeconds INTEGER NOT NULL,
                        PRIMARY KEY(packageName, dateEpochDay)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS focus_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        plannedMinutes INTEGER NOT NULL,
                        endedAtMillis INTEGER,
                        completed INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS habit_gate_state (
                        dateEpochDay INTEGER NOT NULL,
                        rewardGranted INTEGER NOT NULL,
                        PRIMARY KEY(dateEpochDay)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reward_ledger (
                        id INTEGER NOT NULL,
                        earnedMinutesRemaining INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        // Habit-rule command system: "when (habit done in app A) -> unlock (app B) for (N) min".
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS habit_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        triggerPackageName TEXT NOT NULL,
                        targetPackageName TEXT NOT NULL,
                        unlockMinutes INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        lastGrantedEpochDay INTEGER NOT NULL,
                        unlockUntilMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        // Per-habit scanning: detected habit rows (name + done-today), and an optional single
        // habit name a HabitRule can gate on instead of only "all habits done".
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habit_rules ADD COLUMN habitName TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS detected_habits (
                        name TEXT NOT NULL,
                        doneToday INTEGER NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        PRIMARY KEY(name)
                    )
                    """.trimIndent(),
                )
            }
        }

        // Habit Tracker Reward Gate feature was removed (superseded by the Habit Rules command
        // system); drop its now-unused table.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS habit_gate_state")
            }
        }
    }
}
