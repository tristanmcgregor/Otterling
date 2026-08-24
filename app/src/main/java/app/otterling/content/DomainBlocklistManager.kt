package app.otterling.content

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A downloaded, cached, hosts-file-format domain blocklist used by [VpnFilterService] to decide
 * which DNS lookups to block -- the always-on, client-side defense-in-depth layer that stays
 * effective even if the cloud filter ([CloudFilterSettings]) is unreachable. Defaults to two
 * adult-focused hosts lists (StevenBlack's "porn-only" list plus The Blocklist Project's porn
 * list) so a single source going stale or changing format doesn't silently narrow coverage.
 */
class DomainBlocklistManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val blocklistFile = File(context.filesDir, "blocked_domains.txt")
    private val customBlocklist = CustomBlocklistManager(context)
    private val cloudFilterSettings = CloudFilterSettings(context)

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
        prefs.getStringSet(KEY_SOURCES, DEFAULT_SOURCES)?.toList() ?: DEFAULT_SOURCES.toList()

    fun setSourceUrls(urls: Set<String>) {
        prefs.edit().putStringSet(KEY_SOURCES, urls).apply()
    }

    /**
     * The Guardian's own deployed filter-server also serves an accumulated list of every domain
     * its AI classifier has judged bad (see `filter-server/dns_classify_mux.py`'s
     * `_PersistedBadDomains`) -- fetched here alongside the two public sources so those domains
     * stay blocked even during a full filter-server outage (see `VpnFilterService.forwardQuery()`,
     * whose only other fallback during an outage is a public resolver). Deliberately NOT folded
     * into [sourceUrls]/[setSourceUrls] (which back a Guardian-configurable list surfaced in
     * Settings) -- this URL is derived automatically from the configured cloud filter host, not
     * something the Guardian explicitly added and should be able to remove.
     */
    private fun classifiedDomainsUrl(): String? {
        val host = cloudFilterSettings.host().trim()
        return if (host.isNotEmpty()) "https://$host/filter-lists/classified-bad-domains.txt" else null
    }

    /**
     * Downloads and parses the configured hosts-format blocklist(s). Blocking -- call off the main thread.
     *
     * These sources are third-party lists that legitimately change daily, so (unlike the app's own
     * signed releases, which pin a fixed SHA-256 -- see `ApprovedUpdateManager`) there's no stable
     * hash to pin here; the transport is plain system-CA-validated HTTPS. What this *can* still
     * catch, without needing a fixed hash, is the specific failure mode that actually matters: a
     * compromised source/proxy quietly serving a much shorter list to narrow this always-on
     * fail-safe layer. A sudden large drop in domain count relative to the last known-good refresh
     * is treated the same as the existing "parsed to zero" case below -- rejected, keeping whatever
     * was already on disk, rather than silently trusted.
     */
    fun refresh(): Result<Int> = runCatching {
        val urls = combineSourceUrls(sourceUrls(), classifiedDomainsUrl())
        val domains = fetchAllSources(urls, ::downloadHostsFile)
        // A source returning HTTP 200 with a body that doesn't parse to any hosts-file entries
        // (format change, captive portal page, empty response, etc.) isn't an exception, so it
        // used to sail through to writeText() below and silently replace a real blocklist with
        // an empty one -- turning off all content filtering until the next successful refresh,
        // with no error surfaced anywhere. Treat "parsed to zero" as a failure instead and keep
        // whatever was already on disk.
        check(domains.isNotEmpty()) {
            "Refresh produced 0 domains (source format changed or empty response) -- keeping existing blocklist"
        }
        val previousCount = loadedDomains().size
        if (previousCount >= MIN_BASELINE_FOR_SHRINK_CHECK && domains.size < previousCount * MIN_RETAINED_FRACTION) {
            Log.w(
                TAG,
                "Refresh produced ${domains.size} domains, down from $previousCount " +
                    "(< ${(MIN_RETAINED_FRACTION * 100).toInt()}% retained) -- " +
                    "keeping existing blocklist rather than trusting a suspiciously large drop",
            )
            error(
                "Refresh dropped from $previousCount to ${domains.size} domains -- " +
                    "keeping existing blocklist",
            )
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
        private const val TAG = "DomainBlocklistManager"
        // Below this many previously-cached domains, a shrink is more likely a source genuinely
        // having a small list (or a first-ever run) than tampering -- don't reject in that case.
        private const val MIN_BASELINE_FOR_SHRINK_CHECK = 200
        private const val MIN_RETAINED_FRACTION = 0.5
        private const val PREFS_NAME = "domain_blocklist_prefs"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val KEY_SOURCES = "source_urls"
        const val DEFAULT_SOURCE =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
        const val DEFAULT_SOURCE_2 =
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt"
        val DEFAULT_SOURCES = setOf(DEFAULT_SOURCE, DEFAULT_SOURCE_2)

        /**
         * Unions the auto-derived classified-domains URL (if any) onto the Guardian-configured
         * source list, without mutating either -- kept as a pure, [Context]-free function
         * (unlike the rest of this class) so it's directly unit-testable. See
         * [classifiedDomainsUrl]'s own doc for why this union happens here, not inside
         * [sourceUrls]/[setSourceUrls] themselves.
         */
        internal fun combineSourceUrls(sourceUrls: List<String>, classifiedDomainsUrl: String?): List<String> =
            sourceUrls + listOfNotNull(classifiedDomainsUrl)

        /**
         * Calls [fetch] once per URL in [urls], collecting whatever every *successful* call adds
         * to the shared result set and logging (never propagating) an individual failure -- so
         * one bad source (network blip, timeout, 5xx) can't discard what the other sources already
         * fetched. Previously this loop had no per-URL try/catch, so any single source's exception
         * escaped straight through [refresh]'s outer `runCatching`, aborting the *entire* refresh.
         * That mattered less when both sources were reliable public CDNs; it matters much more now
         * that [classifiedDomainsUrl] adds a less-reliable home-server URL into the same list.
         * Pure aside from calling [fetch] itself, so it's unit-testable with fake fetch functions.
         */
        internal fun fetchAllSources(urls: List<String>, fetch: (String, MutableSet<String>) -> Unit): Set<String> {
            val domains = HashSet<String>()
            urls.forEach { url ->
                runCatching { fetch(url, domains) }
                    .onFailure { Log.w(TAG, "Failed to download blocklist source $url", it) }
            }
            return domains
        }
    }
}
