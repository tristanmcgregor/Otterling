package app.otterling.tamper

import android.content.Context
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.data.AppDatabase

class TamperEventLogger(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).tamperEventDao()

    /**
     * [debounceKey] defaults to [type] (the original behavior) -- callers that can fire for
     * several distinct things under the same type (e.g. a specific restriction, app, or number)
     * should pass a more specific key (e.g. "$type|$restrictionName") so debouncing one doesn't
     * silently swallow an alert about a completely different one within the same 10-minute window.
     */
    suspend fun log(type: String, details: String, debounceKey: String = type) {
        dao.insert(TamperEvent(type = type, details = details))
        val severity = when (type) {
            "ACCESSIBILITY_DISABLED",
            "ACCESSIBILITY_SERVICE_INTERRUPTED",
            "ACCESSIBILITY_SERVICE_DESTROYED",
            "ADMIN_DISABLE_REQUESTED",
            "ADMIN_DISABLED",
            "PROTECTION_OFF",
            "ACCOUNTABILITY_ALERTS_DISABLED",
            "ACCOUNTABILITY_PARTNER_REMOVED",
            "RESTRICTION_DISABLED_BY_USER",
            "UNINSTALL_PROTECTION_DISABLED_BY_USER",
            "CONTENT_FILTER_DISABLED_BY_USER",
            "APP_UNBLOCKED_BY_USER",
            -> AlertSeverity.CRITICAL
            "RESTRICTION_DRIFT",
            "CONTENT_FILTER_DRIFT",
            "APP_BLOCK_DRIFT",
            -> AlertSeverity.WARNING
            else -> AlertSeverity.INFO
        }
        runCatching {
            AlertReporter(appContext).report(
                type = type,
                details = details,
                severity = severity,
                debounceKey = debounceKey,
            )
        }
    }

    suspend fun recent(limit: Int = 20): List<TamperEvent> = dao.recent(limit)

    suspend fun since(sinceMillis: Long): List<TamperEvent> = dao.since(sinceMillis)
}
