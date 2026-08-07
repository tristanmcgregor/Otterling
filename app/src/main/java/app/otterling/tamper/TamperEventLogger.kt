package app.otterling.tamper

import android.content.Context
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.data.AppDatabase

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
