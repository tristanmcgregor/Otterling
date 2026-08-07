package app.otterling.monitoring

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads this app's OWN logcat output for in-app self-diagnosis.
 *
 * The `logcat` command only returns entries for the current process' UID/pid when
 * filtered with `--pid`, so no `READ_LOGS` permission is required. This lets the
 * user inspect diagnostics (e.g. the photo-proof matcher's cosine scores) directly
 * on-device without ADB.
 */
object DebugLogReader {
    suspend fun recentLines(maxLines: Int = 400): List<String> = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        var process: Process? = null
        runCatching {
            process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid=$pid", "-t", maxLines.toString()),
            )
            val proc = process!!
            val lines = proc.inputStream.bufferedReader().use { reader ->
                reader.readLines()
            }
            runCatching { proc.errorStream.close() }
            runCatching { proc.outputStream.close() }
            lines
        }.getOrElse { error ->
            listOf("Couldn't read logs: ${error.message}")
        }.also {
            process?.destroy()
        }
    }
}
