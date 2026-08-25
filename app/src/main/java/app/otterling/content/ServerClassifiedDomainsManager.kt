package app.otterling.content

import android.content.Context
import java.io.File

/**
 * The Guardian's own deployed filter-server accumulates a list of every domain its AI classifier
 * has ever judged bad (see `filter-server/dns_classify_mux.py`'s `_PersistedBadDomains`), served
 * at `https://<host>/filter-lists/classified-bad-domains.txt`. This is a deliberately **separate**
 * cache from [DomainBlocklistManager]'s two curated public adult-domain lists, held to a different
 * trust bar: those are established, human-curated ground truth. Both lists are gated by the exact
 * same condition below though -- see [VpnFilterService.handleDnsPacket]'s `blocked` computation.
 * This list is a coarser, domain-only AI guess -- less certain than the *same* server's mitmproxy
 * content inspector (`mitm_nsfw_addon.py`), which sees the actual page content, not just the
 * domain name, and would normally be trusted to make the more informed per-request call instead.
 *
 * So [VpnFilterService.handleDnsPacket] only consults [isBlocked] here when that more-informed
 * check isn't going to happen for this flow anyway:
 * - The app is MITM-exempt ([MitmExemptManager]) -- it never routes through the proxy at all,
 *   regardless of the proxy's health, so this list is the *only* content-level signal it ever gets.
 * - The proxy is disabled or a live outage is detected ([ProxyOutageTracker.isLikelyDown]) -- no
 *   request is getting content-inspected by anything right now, so falling back to this coarser
 *   signal is strictly better than no signal at all.
 *
 * When neither condition holds, this list is deliberately *not* consulted: the proxy's own
 * per-request content inspection is trusted to be the more accurate decision-maker, and letting a
 * coarser domain-only guess preempt it would work against the very reason this list is scoped
 * differently from [DomainBlocklistManager]'s unconditional public lists in the first place.
 */
class ServerClassifiedDomainsManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listFile = File(context.filesDir, "classified_bad_domains.txt")
    private val cloudFilterSettings = CloudFilterSettings(context)

    @Volatile
    private var cachedDomains: Set<String>? = null

    /** True if [hostname] or any of its parent domains is on this list. See the class doc for
     *  when a caller should actually consult this -- gated the same way [DomainBlocklistManager
     *  .isBlocked] is, not checked unconditionally. */
    fun isBlocked(hostname: String): Boolean {
        val domains = loadedDomains()
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

    /**
     * Downloads and parses this deployment's classified-bad-domains list. Blocking -- call off the
     * main thread. Reuses [DomainBlocklistManager.downloadHostsFile]'s exact parsing logic (same
     * hosts-file format, this server just adds a third, ignored timestamp column) and
     * [DomainBlocklistManager.fetchAllSources]'s failure isolation, even though there's only one
     * source here -- keeps both classes' refresh failure/logging behavior identical rather than
     * subtly drifting apart.
     *
     * No shrink-guard the way [DomainBlocklistManager] has one: that guard is tuned for large
     * (200+), stable public lists where a sudden big drop is suspicious. This list starts at zero
     * on a fresh deployment and grows monotonically as the server classifies more domains over
     * time, so an equivalent guard would reject entirely normal early growth. The only real
     * failure mode guarded against here is an empty fetch silently wiping an existing non-empty
     * list (a network blip, a server-side format change, or the server not being configured yet).
     */
    fun refresh(): Result<Int> = runCatching {
        val url = sourceUrl() ?: return@runCatching loadedDomains().size
        val domains = DomainBlocklistManager.fetchAllSources(listOf(url), DomainBlocklistManager::downloadHostsFile)
        if (domains.isEmpty()) {
            // Fetch failure (already logged by fetchAllSources) or a genuinely-empty server list
            // (e.g. nothing classified yet) -- either way, don't overwrite a real cached list.
            return@runCatching loadedDomains().size
        }
        listFile.writeText(domains.joinToString("\n"))
        cachedDomains = domains
        prefs.edit().putLong(KEY_LAST_UPDATED, System.currentTimeMillis()).apply()
        domains.size
    }

    private fun sourceUrl(): String? = classifiedDomainsUrl(cloudFilterSettings.host())

    private fun loadedDomains(): Set<String> {
        cachedDomains?.let { return it }
        val loaded = if (listFile.exists()) {
            runCatching { listFile.readLines().toHashSet() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        cachedDomains = loaded
        return loaded
    }

    companion object {
        private const val PREFS_NAME = "server_classified_domains_prefs"
        private const val KEY_LAST_UPDATED = "last_updated"

        /** Pure, [Context]-free so it's directly unit-testable -- see [sourceUrl]. Internal (not
         *  private) for exactly that reason. */
        internal fun classifiedDomainsUrl(host: String): String? {
            val trimmed = host.trim()
            return if (trimmed.isNotEmpty()) "https://$trimmed/filter-lists/classified-bad-domains.txt" else null
        }
    }
}
