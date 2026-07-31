package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * A single custom blocklist rule. [pathPrefix] null means "block the whole host" (enforced by the
 * VPN DNS filter). A non-null prefix (e.g. `/shorts`) means "only block this path" -- HTTPS paths
 * aren't visible to the VPN, so those are enforced by accessibility when a browser / YouTube app
 * shows a matching URL or Shorts UI.
 */
data class BlocklistEntry(
    val host: String,
    val pathPrefix: String? = null,
) {
    /** Canonical stored / displayed form, e.g. `youtube.com` or `youtube.com/shorts`. */
    fun display(): String = if (pathPrefix.isNullOrEmpty()) host else "$host$pathPrefix"

    fun isDomainOnly(): Boolean = pathPrefix.isNullOrEmpty()

    /** True if [hostname] is [host] or a subdomain of it, and [path] matches the prefix rule. */
    fun matches(hostname: String, path: String): Boolean {
        if (!hostMatches(hostname, host)) return false
        val prefix = pathPrefix ?: return true
        val normalizedPath = normalizePath(path)
        return normalizedPath == prefix || normalizedPath.startsWith("$prefix/")
    }

    companion object {
        fun hostMatches(hostname: String, ruleHost: String): Boolean {
            val h = hostname.lowercase(Locale.US).trimEnd('.')
            val r = ruleHost.lowercase(Locale.US).trimEnd('.')
            return h == r || h.endsWith(".$r")
        }

        fun normalizePath(path: String): String {
            var p = path.trim().substringBefore('?').substringBefore('#')
            if (p.isEmpty()) p = "/"
            if (!p.startsWith('/')) p = "/$p"
            while (p.length > 1 && p.endsWith('/')) p = p.dropLast(1)
            return p.lowercase(Locale.US)
        }
    }
}

/** Parent-managed block rules added locally, independently of the downloaded blocklist. */
class CustomBlocklistManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun entries(): List<BlocklistEntry> =
        prefs.getStringSet(KEY_ENTRIES, null)
            ?.mapNotNull { parseStored(it) }
            ?.sortedBy { it.display() }
            ?: legacyDomainsAsEntries()

    /** Domain-only rules (no path) -- what the VPN DNS filter should NXDOMAIN. */
    fun domainOnlyHosts(): Set<String> =
        entries().filter { it.isDomainOnly() }.map { it.host }.toSet()

    /** Path-prefix rules enforced outside DNS (accessibility). */
    fun pathEntries(): List<BlocklistEntry> =
        entries().filter { !it.isDomainOnly() }

    /** @deprecated Prefer [entries]; kept for call sites that only need display strings. */
    fun domains(): Set<String> = entries().map { it.display() }.toSortedSet()

    fun add(input: String): Result<String> = runCatching {
        val entry = normalize(input)
        val updated = (entries().map { it.display() }.toSet() + entry.display())
        prefs.edit()
            .putStringSet(KEY_ENTRIES, updated)
            .remove(KEY_DOMAINS) // drop legacy key once we've written the new format
            .apply()
        entry.display()
    }

    fun remove(displayOrInput: String): Boolean {
        val key = runCatching { normalize(displayOrInput).display() }.getOrNull()
            ?: displayOrInput.trim().lowercase(Locale.US)
        val current = entries().map { it.display() }.toSet()
        if (key !in current) return false
        prefs.edit().putStringSet(KEY_ENTRIES, current - key).remove(KEY_DOMAINS).apply()
        return true
    }

    private fun legacyDomainsAsEntries(): List<BlocklistEntry> {
        val legacy = prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty()
        if (legacy.isEmpty()) return emptyList()
        // One-time migrate into the new key so path support and DNS filtering stay in sync.
        prefs.edit().putStringSet(KEY_ENTRIES, legacy).remove(KEY_DOMAINS).apply()
        return legacy.map { BlocklistEntry(it) }.sortedBy { it.display() }
    }

    private fun parseStored(raw: String): BlocklistEntry? =
        runCatching { normalize(raw) }.getOrNull()

    companion object {
        private const val PREFS_NAME = "custom_blocklist_prefs"
        private const val KEY_DOMAINS = "domains" // legacy domain-only set
        private const val KEY_ENTRIES = "entries"

        /**
         * Accepts a bare domain, a domain+path (`youtube.com/shorts`), or a full URL. Path+query
         * are reduced to a lowercase path prefix (query/fragment stripped). Domain-only rules
         * block the whole host (including subdomains) via DNS; path rules are for accessibility.
         */
        fun normalize(input: String): BlocklistEntry {
            val raw = input.trim()
            require(raw.isNotEmpty()) { "Enter a website or URL." }
            require(!raw.contains('*')) { "Wildcards aren't needed; subdomains are matched automatically." }

            val uri = runCatching {
                URI(if (raw.contains("://")) raw else "https://$raw")
            }.getOrElse { throw IllegalArgumentException("Enter a valid website, such as example.com or youtube.com/shorts.") }
            val host = uri.host?.trimEnd('.')
                ?: throw IllegalArgumentException("Enter a valid website, such as example.com.")
            val ascii = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }
                .getOrElse { throw IllegalArgumentException("That domain contains invalid characters.") }
                .lowercase(Locale.US)
                .removePrefix("www.")

            require(ascii.length <= 253 && '.' in ascii) { "Enter a full domain, such as example.com." }
            require(!IPV4.matches(ascii) && !ascii.contains(':')) { "IP addresses aren't supported." }
            require(ascii.split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }) { "Enter a valid domain, such as example.com." }

            val path = uri.path?.let { BlocklistEntry.normalizePath(it) }
            val pathPrefix = path?.takeIf { it != "/" }
            return BlocklistEntry(host = ascii, pathPrefix = pathPrefix)
        }

        private val IPV4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}
