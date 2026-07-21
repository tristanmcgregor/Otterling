package au.com.tbmcgregor.bwparker.familyguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.tbmcgregor.bwparker.familyguard.monitoring.AppUsageSession
import au.com.tbmcgregor.bwparker.familyguard.monitoring.AppUsageSessionDao
import au.com.tbmcgregor.bwparker.familyguard.monitoring.AppUsageStat
import au.com.tbmcgregor.bwparker.familyguard.monitoring.AppUsageStatDao
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleRule
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleRuleDao
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEvent
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventDao

@Database(
    entities = [
        BlockedApp::class,
        ScheduleRule::class,
        AppUsageStat::class,
        AppUsageSession::class,
        TamperEvent::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao

    abstract fun scheduleRuleDao(): ScheduleRuleDao

    abstract fun appUsageStatDao(): AppUsageStatDao

    abstract fun appUsageSessionDao(): AppUsageSessionDao

    abstract fun tamperEventDao(): TamperEventDao

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
    }
}
