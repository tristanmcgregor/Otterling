package app.otterling.proxytest

import android.content.Context

/**
 * Plain (unencrypted) on-device settings for this disposable test app -- unlike the production
 * app's CloudFilterSettings, there's no shipped user data to protect here, and the proxy password
 * never touches a source file (typed into the UI once, persisted only in this app's own prefs).
 */
class ProxyTestSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var user: String
        get() = prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        set(value) = prefs.edit().putString(KEY_USER, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    private companion object {
        const val PREFS = "proxytest_settings"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        // Matches production CloudFilterSettings' defaults (filter-server/docker-compose.yml) --
        // just the non-secret pieces; password is deliberately left blank, typed in on-device.
        const val DEFAULT_HOST = "vpn.bartholomew.help"
        const val DEFAULT_PORT = 8090
        const val DEFAULT_USER = "otterling"
    }
}
