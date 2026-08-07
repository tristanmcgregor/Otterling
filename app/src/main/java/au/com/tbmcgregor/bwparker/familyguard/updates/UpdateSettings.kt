package au.com.tbmcgregor.bwparker.familyguard.updates

import android.content.Context

/**
 * Non-secret settings for the "Request update" flow -- just where to open a browser to file the
 * request. Nothing here participates in the install trust chain (see [ApprovedUpdateManager] for
 * that); a wrong or empty value here only means the "Open GitHub issue" button has nowhere to go,
 * it can't cause an unreviewed build to install.
 */
class UpdateSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** e.g. "https://github.com/your-username/TrittyBlocker/issues/new" -- set once per family
     *  deployment, same idea as [au.com.tbmcgregor.bwparker.familyguard.content.CloudFilterSettings]'s host. */
    fun githubIssuesUrl(): String = prefs.getString(KEY_ISSUES_URL, "").orEmpty()

    fun setGithubIssuesUrl(url: String) {
        prefs.edit().putString(KEY_ISSUES_URL, url.trim()).apply()
    }

    private companion object {
        const val PREFS = "update_settings"
        const val KEY_ISSUES_URL = "github_issues_url"
    }
}
