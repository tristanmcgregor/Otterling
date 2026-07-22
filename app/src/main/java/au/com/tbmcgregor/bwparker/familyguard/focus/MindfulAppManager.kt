package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase

/**
 * Apps that show a short friction/delay screen ([FrictionActivity]) before opening, instead of a
 * hard block -- for things you sometimes need but tend to open on autopilot.
 */
class MindfulAppManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).mindfulAppDao()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun apps(): List<MindfulApp> = dao.getAll()

    suspend fun add(packageName: String, delaySeconds: Int = 20) {
        dao.upsert(MindfulApp(packageName, delaySeconds))
    }

    suspend fun remove(packageName: String) = dao.delete(packageName)

    fun markPassed(packageName: String) {
        prefs.edit().putLong(keyFor(packageName), System.currentTimeMillis()).apply()
    }

    /** True if this app's delay screen was already passed recently -- avoids nagging on every switch. */
    fun isWithinGracePeriod(packageName: String, graceMillis: Long = DEFAULT_GRACE_MILLIS): Boolean =
        System.currentTimeMillis() - prefs.getLong(keyFor(packageName), 0L) < graceMillis

    private fun keyFor(packageName: String) = "passed_$packageName"

    private companion object {
        const val PREFS_NAME = "mindful_app_state"
        const val DEFAULT_GRACE_MILLIS = 10 * 60 * 1000L
    }
}
