package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks directly to HabitShare's own backend (the same one the HabitShare app itself uses) to
 * read exact done/not-done status for the day -- the sole source of habit completion data. This
 * is an *unofficial*, reverse-engineered API (see
 * https://github.com/cykirk/habitshare-api-python) -- not published or supported by HabitShare,
 * so it could change without notice. It logs in with the same username/password the user already
 * uses inside the HabitShare app ([HabitShareAccountManager]) and only ever talks to
 * HabitShare's own server; nothing is sent to any other third party.
 */
class HabitShareApiClient(context: Context) {
    private val accountManager = HabitShareAccountManager(context)

    sealed class LoginResult {
        object Success : LoginResult()
        object InvalidCredentials : LoginResult()
        object NetworkError : LoginResult()
    }

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("username", username).put("password", password).toString()
            val connection = openConnection(LOGIN_URL, method = "POST")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val responseText = readStream(connection.inputStream)
                val token = JSONObject(responseText).optString("key", "")
                if (token.isBlank()) {
                    Log.w(TAG, "Login succeeded but response had no key: $responseText")
                    return@withContext LoginResult.NetworkError
                }
                accountManager.saveCredentials(username, password)
                accountManager.saveToken(token)
                LoginResult.Success
            } else {
                Log.w(TAG, "HabitShare login failed: HTTP $code -- ${readStreamSafely(connection)}")
                if (code == 400 || code == 401 || code == 403) LoginResult.InvalidCredentials else LoginResult.NetworkError
            }
        } catch (error: Exception) {
            Log.w(TAG, "HabitShare login error", error)
            LoginResult.NetworkError
        }
    }

    fun isConnected(): Boolean = accountManager.isConnected()

    fun connectedUsername(): String? = accountManager.username()

    fun disconnect() = accountManager.disconnect()

    /**
     * Returns (habitName, doneToday) pairs for every habit on the account, or null if the fetch
     * failed outright (no credentials configured, network error, or re-login also failed) --
     * callers should treat null as "couldn't reach HabitShare this cycle", not "nothing done".
     */
    suspend fun fetchTodayCompletions(): List<Pair<String, Boolean>>? = withContext(Dispatchers.IO) {
        var token = accountManager.token() ?: return@withContext tryReloginThenFetch()
        val result = fetchWithToken(token)
        if (result == null) {
            // Token may have expired -- re-login with stored credentials and retry once.
            return@withContext tryReloginThenFetch()
        }
        result
    }

    private suspend fun tryReloginThenFetch(): List<Pair<String, Boolean>>? {
        val username = accountManager.username() ?: return null
        val password = accountManager.password() ?: return null
        if (login(username, password) !is LoginResult.Success) return null
        val token = accountManager.token() ?: return null
        return fetchWithToken(token)
    }

    private fun fetchWithToken(token: String): List<Pair<String, Boolean>>? {
        return try {
            val connection = openConnection(HABITS_URL, method = "GET")
            connection.setRequestProperty("Authorization", "Token $token")
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HabitShare fetch failed: HTTP $code -- ${readStreamSafely(connection)}")
                return null
            }
            val responseText = readStream(connection.inputStream)
            logRawResponseForDebugging(responseText)
            parseHabits(responseText)
        } catch (error: Exception) {
            Log.w(TAG, "HabitShare fetch error", error)
            null
        }
    }

    /** Splits long JSON across multiple logcat lines -- Logcat truncates a single line around
     * ~4000 chars, and this response is the one thing worth inspecting in full if the parser
     * below ever needs adjusting for a HabitShare API change. */
    private fun logRawResponseForDebugging(responseText: String) {
        responseText.chunked(3000).forEachIndexed { index, chunk ->
            Log.d(TAG, "raw habits response [$index]: $chunk")
        }
    }

    /**
     * Defensive/generic parsing: HabitShare's actual JSON shape isn't documented anywhere, so
     * this tries several plausible field names rather than hard-coding one guess. See
     * [logRawResponseForDebugging] output in Logcat to refine this if it's ever wrong for a given
     * habit shape.
     */
    private fun parseHabits(responseText: String): List<Pair<String, Boolean>> {
        val array = extractHabitArray(responseText) ?: return emptyList()
        // HabitShare's backend is US-hosted, so a habit ticked "now" can be stamped under the UTC
        // calendar date rather than the device-local one (e.g. early-morning local time, when the
        // UTC clock is still on the previous date). Accept either so a just-ticked habit is seen
        // as done today. Only ever widens for a "success" entry (see [trackerListHasToday]); a
        // "fail" never counts.
        val todayLocal = LocalDate.now()
        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        val rows = mutableListOf<Pair<String, Boolean>>()
        for (i in 0 until array.length()) {
            val habit = array.optJSONObject(i) ?: continue
            val name = firstNonBlank(habit, "title", "name", "habitName") ?: continue
            rows.add(name to isDoneToday(habit, todayLocal, todayUtc))
        }
        Log.d(TAG, "parsed ${rows.size} habits; done today: ${rows.filter { it.second }.map { it.first }}")
        return rows
    }

    private fun extractHabitArray(responseText: String): JSONArray? = try {
        val trimmed = responseText.trim()
        when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                listOf("results", "habits", "data").firstNotNullOfOrNull { key ->
                    obj.optJSONArray(key)
                } ?: JSONArray().also {
                    Log.w(TAG, "Habits response object had no recognised array field: $trimmed")
                }
            }
            else -> null
        }
    } catch (error: Exception) {
        Log.w(TAG, "Failed to parse habits JSON", error)
        null
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> obj.optString(key, "").takeIf { it.isNotBlank() } }

    private fun isDoneToday(habit: JSONObject, todayLocal: LocalDate, todayUtc: LocalDate): Boolean {
        // The dated tracker list is the authoritative record of what happened *today*, so it wins
        // over any top-level done/status/completed flag: those are frequently habit-level fields
        // (e.g. "this habit is archived/finished overall"), not today-specific, and checking them
        // first short-circuited "today" wrongly. Only fall back to the direct flags when the habit
        // carries no recognised dated tracker list at all.
        for (listKey in TRACKER_LIST_KEYS) {
            val list = habit.optJSONArray(listKey) ?: continue
            return trackerListHasToday(list, todayLocal, todayUtc)
        }
        for (key in DIRECT_BOOLEAN_KEYS) {
            if (habit.has(key)) return habit.optBoolean(key, false)
        }
        for (key in DIRECT_STATUS_KEYS) {
            val status = habit.optString(key, "")
            if (status.isNotBlank()) return status.equals("done", ignoreCase = true) ||
                status.equals("completed", ignoreCase = true) || status.equals("checked", ignoreCase = true)
        }
        return false
    }

    private fun trackerListHasToday(list: JSONArray, todayLocal: LocalDate, todayUtc: LocalDate): Boolean {
        fun LocalDate.isToday() = this == todayLocal || this == todayUtc
        for (i in 0 until list.length()) {
            when (val entry = list.opt(i)) {
                // HabitShare's actual shape: each tracker is a 3-element array
                // [ "YYYY-MM-DD", "success"|"fail", "note" ]. A same-day "success" is a done tick;
                // a same-day "fail" is an explicit not-done and must NOT count. We scan the whole
                // list (rather than returning on the first same-day entry) so a "fail" on one
                // accepted date can't mask a "success" on the other.
                is JSONArray -> {
                    val entryDate = entry.optString(0, "").let(::parseFlexibleDate) ?: continue
                    if (!entryDate.isToday()) continue
                    if (entry.optString(1, "").equals("success", ignoreCase = true)) return true
                }
                is JSONObject -> {
                    val dateText = firstNonBlank(entry, "date", "day", "checkinDate", "createdAt", "created_at")
                    val entryDate = dateText?.let(::parseFlexibleDate) ?: continue
                    if (!entryDate.isToday()) continue
                    val status = firstNonBlank(entry, "status", "state")
                    if (status != null) {
                        if (status.equals("success", ignoreCase = true) ||
                            status.equals("done", ignoreCase = true) ||
                            status.equals("completed", ignoreCase = true)
                        ) {
                            return true
                        }
                    } else {
                        val hasExplicitFlag = DIRECT_BOOLEAN_KEYS.any { entry.has(it) }
                        if (hasExplicitFlag) {
                            if (DIRECT_BOOLEAN_KEYS.any { entry.optBoolean(it, false) }) return true
                        } else {
                            return true
                        }
                    }
                }
                is String -> {
                    val entryDate = parseFlexibleDate(entry) ?: continue
                    if (entryDate.isToday()) return true
                }
                else -> Unit
            }
        }
        return false
    }

    private fun parseFlexibleDate(text: String): LocalDate? = try {
        LocalDate.parse(text.take(10))
    } catch (error: Exception) {
        null
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (method == "POST") connection.doOutput = true
        return connection
    }

    private fun readStream(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

    private fun readStreamSafely(connection: HttpURLConnection): String = try {
        readStream(connection.errorStream ?: return "")
    } catch (error: Exception) {
        ""
    }

    private companion object {
        const val TAG = "HabitShareApiClient"
        const val BASE_URL = "https://habitshare.herokuapp.com/"
        const val LOGIN_URL = BASE_URL + "rest-auth/login/"
        // Habits live at the top-level /habits (only *friends* sit under /api/v3/ in HabitShare's
        // API) -- pointing this at /api/v3/habits silently 404s every sync.
        const val HABITS_URL = BASE_URL + "habits"
        const val TIMEOUT_MS = 15_000
        val DIRECT_BOOLEAN_KEYS = listOf(
            "done", "completed", "checkedInToday", "doneToday", "isDoneToday", "todayDone",
            "completedToday", "checkedToday", "isCheckedToday",
        )
        val DIRECT_STATUS_KEYS = listOf("todayStatus", "today_status", "status")
        val TRACKER_LIST_KEYS = listOf("trackers", "checkins", "logs", "history")
    }
}
