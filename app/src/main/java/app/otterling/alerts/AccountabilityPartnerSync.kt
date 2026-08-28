package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.content.DashboardConfigStore
import app.otterling.tamper.TamperEventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconciles this device's on-device accountability-partner list
 * ([AccountabilityPartnerSettings]) against the dashboard-authored `accountabilityPartners` field
 * in [DashboardConfigStore]'s cached settings snapshot -- lets a guardian add/remove a partner
 * from the web dashboard (filter-server/dashboard's Accountability screen) with the same effect as
 * using the phone's own Accountability Partners section: a genuinely new number gets the welcome
 * SMS, a dashboard-removed one gets the removal SMS.
 *
 * Union merge, not a destructive mirror: a number added on-device (not yet known to the
 * dashboard -- e.g. added before this device's next settings poll, or a guardian who just prefers
 * the phone's own UI) is never removed just because the dashboard's list doesn't yet contain it.
 * Only a number this sync itself previously observed in the dashboard's list ([KEY_SERVER_KNOWN],
 * never the full on-device list) is eligible for the "dashboard removed it" branch below, so an
 * on-device-only addition can never be clobbered by a dashboard fetch that simply hasn't caught
 * up yet.
 */
class AccountabilityPartnerSync(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val snapshot = DashboardConfigStore(appContext).snapshot() ?: return@withContext
        val serverArray = snapshot.optJSONArray("accountabilityPartners") ?: return@withContext
        val serverNumbers = mutableSetOf<String>()
        for (i in 0 until serverArray.length()) {
            val phone = serverArray.optJSONObject(i)?.optString("phone")?.trim().orEmpty()
            if (phone.isNotEmpty()) serverNumbers += phone
        }

        val settings = AccountabilityPartnerSettings(appContext)
        val reporter = AlertReporter(appContext)
        val local = settings.partnerNumbers().toSet()
        val previouslyServerKnown = serverKnown()

        // Dashboard added a number this device doesn't have yet.
        for (phone in serverNumbers - local) {
            settings.addPartnerNumber(phone)
            runCatching { reporter.sendWelcomeMessage(phone) }
                .onFailure { Log.w(TAG, "welcome SMS failed for dashboard-added partner", it) }
        }

        // Dashboard removed a number this sync previously observed coming from the dashboard.
        for (phone in previouslyServerKnown - serverNumbers) {
            if (phone !in local) continue
            runCatching {
                GuardianSmsSender(appContext).send(
                    "Otterling: you've been removed from accountability alerts on this device.",
                    phone,
                )
            }.onFailure { Log.w(TAG, "removal SMS failed for dashboard-removed partner", it) }
            runCatching {
                TamperEventLogger(appContext).log(
                    type = "ACCOUNTABILITY_PARTNER_REMOVED",
                    details = "Removed $phone (via dashboard)",
                    debounceKey = "ACCOUNTABILITY_PARTNER_REMOVED|$phone",
                )
            }
            settings.removePartnerNumber(phone)
        }

        setServerKnown(serverNumbers)
    }

    private fun serverKnown(): Set<String> = prefs.getStringSet(KEY_SERVER_KNOWN, emptySet()).orEmpty()

    private fun setServerKnown(numbers: Set<String>) {
        prefs.edit().putStringSet(KEY_SERVER_KNOWN, numbers).apply()
    }

    private companion object {
        const val TAG = "AccountabilityPartnerSync"
        const val PREFS = "accountability_partner_sync"
        const val KEY_SERVER_KNOWN = "server_known_numbers"
    }
}
