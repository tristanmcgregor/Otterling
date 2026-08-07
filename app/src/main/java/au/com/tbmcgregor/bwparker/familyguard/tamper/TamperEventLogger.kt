package au.com.tbmcgregor.bwparker.familyguard.tamper

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertReporter
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertSeverity
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase

class TamperEventLogger(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).tamperEventDao()

    suspend fun log(type: String, details: String) {
        dao.insert(TamperEvent(type = type, details = details))
        val severity = when (type) {
            "ACCESSIBILITY_DISABLED",
            "ADMIN_DISABLE_REQUESTED",
            "PROTECTION_OFF",
            -> AlertSeverity.CRITICAL
            "RESTRICTION_DRIFT" -> AlertSeverity.WARNING
            else -> AlertSeverity.INFO
        }
        runCatching {
            AlertReporter(appContext).report(
                type = type,
                details = details,
                severity = severity,
                debounceKey = type,
            )
        }
    }

    suspend fun recent(limit: Int = 20): List<TamperEvent> = dao.recent(limit)

    suspend fun since(sinceMillis: Long): List<TamperEvent> = dao.since(sinceMillis)
}
