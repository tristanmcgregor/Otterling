package au.com.tbmcgregor.bwparker.familyguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
                    // Pre-release app with no meaningful data yet -- simplest safe path
                    // across schema changes until the app has real installs to preserve.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
