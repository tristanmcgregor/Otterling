package app.otterling

import android.app.Application
import android.util.Log
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registered as the manifest's `android:name` so [onCreate] runs before any other component --
 * a BroadcastReceiver/Service can be the very first thing the process ever runs (e.g.
 * BootCompletedReceiver starting [app.otterling.content.VpnFilterService] directly with no
 * Activity involved at all), not just [MainActivity].
 *
 * There was previously no crash reporting anywhere in this app -- no Crashlytics, no
 * uncaught-exception handler -- so a crash just silently killed the process with nothing
 * recorded anywhere, on-device or off. This installs a handler that persists the stack trace
 * to disk synchronously (the process is about to die, so nothing async/DB-backed can be
 * trusted to finish) and, on the next process start, reports it through the same
 * [AlertReporter] pipeline every other self-diagnostic alert already uses.
 */
class OtterlingApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        reportAndClearPendingCrash()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                crashLogFile().writeText("${System.currentTimeMillis()}\n$trace")
            }
            // Chain to the previous (system) handler so crash behavior -- process death, the
            // system "App has stopped" dialog -- is completely unchanged; this only observes.
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun reportAndClearPendingCrash() {
        val file = crashLogFile()
        if (!file.exists()) return
        val content = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        if (content.isNullOrBlank()) return
        // First line is the timestamp; the exception's toString() (class + message) is the next
        // one -- plenty to identify a recurring crash from the guardian dashboard without
        // dumping a full stack trace into an SMS-length alert.
        val summary = content.lineSequence().drop(1).firstOrNull()?.take(200) ?: "unknown error"
        appScope.launch {
            runCatching {
                AlertReporter(this@OtterlingApplication).report(
                    type = "APP_CRASH",
                    details = "Otterling crashed and restarted: $summary",
                    severity = AlertSeverity.WARNING,
                )
            }.onFailure { Log.w(TAG, "Failed to report previous crash", it) }
        }
    }

    private fun crashLogFile() = File(filesDir, "last_crash.txt")

    private companion object {
        const val TAG = "OtterlingApplication"
    }
}
