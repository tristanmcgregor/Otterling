package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A downloaded, cached, hosts-file-format domain blocklist used by [VpnFilterService] to decide
 * which DNS lookups to block. Defaults to StevenBlack's "porn-only" hosts list (the standard
 * combined adult-content blocklist referenced by most parental-control tools).
 */
class DomainBlocklistManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val blocklistFile = File(context.filesDir, "blocked_domains.txt")
    private val customBlocklist = CustomBlocklistManager(context)

    @Volatile
    private var cachedDomains: Set<String>? = null

    /** True if [hostname] or any of its parent domains is on the blocklist. */
    fun isBlocked(hostname: String): Boolean {
        val domains = loadedDomains()
        // Path rules (youtube.com/shorts) must NOT NXDOMAIN the whole host -- only domain-only
        // custom entries participate in DNS blocking.
        val customDomains = customBlocklist.domainOnlyHosts()
        if (domains.isEmpty() && customDomains.isEmpty()) return false
        var candidate = hostname.lowercase().trimEnd('.')
        while (candidate.isNotEmpty()) {
            if (candidate in domains || candidate in customDomains) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex == -1) break
            candidate = candidate.substring(dotIndex + 1)
        }
        return false
    }

    fun domainCount(): Int = loadedDomains().size

    fun lastUpdatedMillis(): Long = prefs.getLong(KEY_LAST_UPDATED, 0L)

    fun sourceUrls(): List<String> =
        prefs.getStringSet(KEY_SOURCES, setOf(DEFAULT_SOURCE))?.toList() ?: listOf(DEFAULT_SOURCE)

    fun setSourceUrls(urls: Set<String>) {
        prefs.edit().putStringSet(KEY_SOURCES, urls).apply()
    }

    /** Downloads and parses the configured hosts-format blocklist(s). Blocking -- call off the main thread. */
    fun refresh(): Result<Int> = runCatching {
        val domains = HashSet<String>()
        sourceUrls().forEach { url -> downloadHostsFile(url, domains) }
        // A source returning HTTP 200 with a body that doesn't parse to any hosts-file entries
        // (format change, captive portal page, empty response, etc.) isn't an exception, so it
        // used to sail through to writeText() below and silently replace a real blocklist with
        // an empty one -- turning off all content filtering until the next successful refresh,
        // with no error surfaced anywhere. Treat "parsed to zero" as a failure instead and keep
        // whatever was already on disk.
        check(domains.isNotEmpty()) {
            "Refresh produced 0 domains (source format changed or empty response) -- keeping existing blocklist"
        }
        blocklistFile.writeText(domains.joinToString("\n"))
        cachedDomains = domains
        prefs.edit().putLong(KEY_LAST_UPDATED, System.currentTimeMillis()).apply()
        domains.size
    }

    private fun loadedDomains(): Set<String> {
        cachedDomains?.let { return it }
        val loaded = if (blocklistFile.exists()) {
            runCatching { blocklistFile.readLines().toHashSet() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        cachedDomains = loaded
        return loaded
    }

    private fun downloadHostsFile(urlString: String, into: MutableSet<String>) {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEach
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                        val domain = parts[1].lowercase().trimEnd('.')
                        if (domain.isNotEmpty() && domain != "localhost") into.add(domain)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS_NAME = "domain_blocklist_prefs"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val KEY_SOURCES = "source_urls"
        const val DEFAULT_SOURCE =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
    }
}
