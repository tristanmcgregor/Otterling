package app.otterling.content

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A downloaded, cached, hosts-file-format domain blocklist used by [VpnFilterService] as a
 * client-side fallback when the MITM proxy's own page-content review isn't going to happen for a
 * given flow -- a MITM-exempt app, or the cloud filter ([CloudFilterSettings]) being genuinely
 * unreachable. Defaults to two adult-focused hosts lists (StevenBlack's "porn-only" list plus The
 * Blocklist Project's porn list) so a single source going stale or changing format doesn't
 * silently narrow coverage.
 *
 * Deliberately NOT consulted when the proxy's own verdict is going to apply anyway -- every
 * device on the account (this phone, a Mac running FocusLock) needs the same domain to get the
 * same blocking decision, and this list existing only on Android used to mean a domain the proxy
 * wouldn't block could still get blocked here and nowhere else. See [VpnFilterService.handleDnsPacket]'s
 * `blocked` computation for exactly when this and [ServerClassifiedDomainsManager]'s coarser
 * AI-classified list apply -- the same condition gates both.
 */
class DomainBlocklistManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val blocklistFile = File(context.filesDir, "blocked_domains.txt")
    private val customBlocklist = CustomBlocklistManager(context)

    @Volatile
    private var cachedDomains: Set<String>? = null

    /** True if [hostname] or any of its parent domains is on either the downloaded public lists
     *  or the guardian's own custom blocklist. Combined for [DebugUnsuspendReceiver]'s ADB probe
     *  (which wants "would this be DNS-blocked at all"); [VpnFilterService.handleDnsPacket] uses
     *  [isPublicListBlocked] and [isCustomBlocked] separately instead, since only one of the two
     *  is gated behind proxy-availability -- see this class's doc comment. */
    fun isBlocked(hostname: String): Boolean = isPublicListBlocked(hostname) || isCustomBlocked(hostname)

    /** The two curated public hosts lists only -- gated by [VpnFilterService.handleDnsPacket],
     *  NOT the guardian's own explicit per-device rules (see [isCustomBlocked]). */
    fun isPublicListBlocked(hostname: String): Boolean = matchesAny(hostname, loadedDomains())

    /** The guardian's own dashboard-configured `blockedWebsites` for this specific device (see
     *  [CustomBlocklistManager]'s doc) -- an intentional, per-device rule the guardian set
     *  directly, not an incidental extra filtering layer, so [VpnFilterService.handleDnsPacket]
     *  always enforces this regardless of proxy availability. Path rules (youtube.com/shorts)
     *  must NOT NXDOMAIN the whole host -- only domain-only custom entries participate here. */
    fun isCustomBlocked(hostname: String): Boolean = matchesAny(hostname, customBlocklist.domainOnlyHosts())

    private fun matchesAny(hostname: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var candidate = hostname.lowercase().trimEnd('.')
        while (candidate.isNotEmpty()) {
            if (candidate in domains) return true
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
        val domains = fetchAllSources(sourceUrls(), ::downloadHostsFile)
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
         * Calls [fetch] once per URL in [urls], collecting whatever every *successful* call adds
         * to the shared result set and logging (never propagating) an individual failure -- so
         * one bad source (network blip, timeout, 5xx) can't discard what the other sources already
         * fetched. Previously this loop had no per-URL try/catch, so any single source's exception
         * escaped straight through [refresh]'s outer `runCatching`, aborting the *entire* refresh.
         * Internal (not private) and pure aside from calling [fetch] itself, so it's reusable by
         * [ServerClassifiedDomainsManager]'s own single-source refresh and unit-testable with fake
         * fetch functions.
         */
        internal fun fetchAllSources(urls: List<String>, fetch: (String, MutableSet<String>) -> Unit): Set<String> {
            val domains = HashSet<String>()
            urls.forEach { url ->
                runCatching { fetch(url, domains) }
                    .onFailure { Log.w(TAG, "Failed to download blocklist source $url", it) }
            }
            return domains
        }

        /**
         * Downloads and parses one hosts-format URL into [into]. Internal (not private) so
         * [ServerClassifiedDomainsManager] can reuse the exact same parsing logic for its own,
         * differently-sourced list rather than duplicating it.
         */
        internal fun downloadHostsFile(urlString: String, into: MutableSet<String>) {
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
    }
}
