package au.com.tbmcgregor.bwparker.familyguard.content

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URI
import java.util.Locale

/**
 * Enforces [CustomBlocklistManager] path-prefix rules (e.g. `youtube.com/shorts`) by reading the
 * browser address bar or recognising in-app Shorts UIs. Whole-host rules stay on the VPN DNS
 * filter -- this only handles entries that include a path, which HTTPS encryption hides from the
 * VPN.
 */
object UrlPathBlockEnforcer {
    private const val TAG = "UrlPathBlockEnforcer"

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
    )

    val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "app.morphe.android.youtube",
        "com.vanced.android.youtube",
        "com.google.android.apps.youtube.kids",
    )

    fun shouldBlockBrowserUrl(entries: List<BlocklistEntry>, urlText: String): Boolean {
        val parsed = parseUrl(urlText) ?: return false
        return entries.any { it.matches(parsed.first, parsed.second) }
    }

    fun shouldBlockYoutubeShorts(entries: List<BlocklistEntry>): Boolean =
        entries.any { entry ->
            !entry.isDomainOnly() &&
                BlocklistEntry.hostMatches("youtube.com", entry.host) &&
                (entry.pathPrefix == "/shorts" || entry.pathPrefix?.startsWith("/shorts") == true)
        }

    /** Best-effort URL from a browser accessibility tree (omnibox / address bar). */
    fun extractBrowserUrl(root: AccessibilityNodeInfo): String? {
        val found = ArrayDeque<AccessibilityNodeInfo>()
        found.add(root)
        var visited = 0
        var fallback: String? = null
        while (found.isNotEmpty() && visited < 300) {
            val node = found.removeFirst()
            visited++
            val id = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val candidate = text.ifBlank { desc }
            if (candidate.isNotBlank() && looksLikeUrl(candidate)) {
                if (isOmniboxId(id) || node.isEditable) {
                    return candidate
                }
                if (fallback == null) fallback = candidate
            }
            for (i in 0 until node.childCount) {
                found.add(node.getChild(i) ?: continue)
            }
        }
        return fallback
    }

    fun parseUrl(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return null
        if (host.isEmpty() || '.' !in host) return null
        val path = BlocklistEntry.normalizePath(uri.path ?: "/")
        return host to path
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        if (t.startsWith("http://") || t.startsWith("https://")) return true
        // Chrome often shows "youtube.com/shorts/…" without a scheme once focused leaves the bar.
        if (' ' in t) return false
        if ('/' in t && '.' in t.substringBefore('/')) return true
        return t.contains('.') && t.length in 4..500 && !t.contains(' ')
    }

    private fun isOmniboxId(id: String): Boolean =
        id.contains("url_bar") ||
            id.contains("omnibox") ||
            id.contains("location_bar") ||
            id.contains("url_field") ||
            id.contains("address_bar") ||
            id.contains("search_box") && id.contains("url")

    fun logMatch(entry: BlocklistEntry, host: String, path: String) {
        Log.d(TAG, "Blocked ${entry.display()} matched $host$path")
    }
}
