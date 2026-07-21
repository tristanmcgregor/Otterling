package au.com.tbmcgregor.bwparker.familyguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEvent
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventDao

@Database(
    entities = [
        BlockedApp::class,
        TamperEvent::class,
        ProtectedApp::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao

    abstract fun tamperEventDao(): TamperEventDao

    abstract fun protectedAppDao(): ProtectedAppDao

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
    }
}
