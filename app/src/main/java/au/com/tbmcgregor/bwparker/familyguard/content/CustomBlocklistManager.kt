package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context
import java.net.IDN
import java.net.URI
import java.util.Locale

/** Parent-managed domains added locally, independently of the downloaded blocklist. */
class CustomBlocklistManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun domains(): Set<String> =
        prefs.getStringSet(KEY_DOMAINS, emptySet()).orEmpty().toSortedSet()

    fun add(domain: String): Result<String> = runCatching {
        val normalized = normalize(domain)
        // apply() updates the in-memory preferences synchronously (so the live VPN sees it on its
        // next lookup) while flushing to disk asynchronously.
        prefs.edit().putStringSet(KEY_DOMAINS, domains() + normalized).apply()
        normalized
    }

    fun remove(domain: String): Boolean {
        val normalized = runCatching { normalize(domain) }.getOrNull() ?: return false
        val updated = domains() - normalized
        prefs.edit().putStringSet(KEY_DOMAINS, updated).apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "custom_blocklist_prefs"
        private const val KEY_DOMAINS = "domains"

        /**
         * Accepts a bare domain or URL, strips URL decorations, converts international names to
         * ASCII, and rejects IPs, wildcards, single-label hosts, and malformed DNS labels.
         */
        fun normalize(input: String): String {
            val raw = input.trim()
            require(raw.isNotEmpty()) { "Enter a website domain." }
            require(!raw.contains('*')) { "Wildcards aren't needed; subdomains are blocked automatically." }

            val uri = runCatching {
                URI(if (raw.contains("://")) raw else "https://$raw")
            }.getOrElse { throw IllegalArgumentException("Enter a valid domain, such as example.com.") }
            val host = uri.host?.trimEnd('.')
                ?: throw IllegalArgumentException("Enter a valid domain, such as example.com.")
            val ascii = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }
                .getOrElse { throw IllegalArgumentException("That domain contains invalid characters.") }
                .lowercase(Locale.US)

            require(ascii.length <= 253 && '.' in ascii) { "Enter a full domain, such as example.com." }
            require(!IPV4.matches(ascii) && !ascii.contains(':')) { "IP addresses aren't supported." }
            require(ascii.split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }) { "Enter a valid domain, such as example.com." }
            return ascii
        }

        private val IPV4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}
