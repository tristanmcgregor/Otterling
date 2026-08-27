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
        // one -- plenty to identify *what* crashed. On its own that's not enough to fix a crash
        // like OutOfMemoryError, whose message never says *where* -- so this also hunts for the
        // first stack frame inside our own code (an "at app.otterling..." line) and appends it,
        // still short enough for an SMS-length alert but now enough to point at the actual call
        // site next time, instead of needing a fresh repro with a debugger attached.
        val lines = content.lineSequence().drop(1)
        val exceptionLine = lines.firstOrNull()?.take(200) ?: "unknown error"
        val ownFrame = lines.firstOrNull { it.trimStart().startsWith("at app.otterling") }?.trim()
        val summary = if (ownFrame != null) "$exceptionLine ($ownFrame)".take(300) else exceptionLine
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
