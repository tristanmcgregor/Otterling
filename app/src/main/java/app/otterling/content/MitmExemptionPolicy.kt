package app.otterling.content

/**
 * Decides whether a TCP flow should skip the mitmproxy CONNECT hop (see [TcpRelayManager.establish])
 * because it belongs to a certificate-pinned app the Guardian has exempted -- see
 * [MitmExemptManager]. Pure/framework-free so this is unit-testable without Robolectric.
 */
object MitmExemptionPolicy {
    /**
     * [ownerUid], when non-null, is authoritative: it either is or isn't in [exemptUids], and the
     * hostname fallback is never consulted either way -- an unrelated app whose UID happens to
     * resolve must never get exempted just because it also happens to hit a curated domain.
     * [hostname] (from [TcpRelayManager]'s DNS-answer cache) is only used when UID attribution
     * couldn't resolve an owner at all (e.g. pre-API-29 devices, or a lookup failure).
     */
    fun isExempt(
        ownerUid: Int?,
        exemptUids: Set<Int>,
        hostname: String?,
        exemptHostSuffixes: Set<String>,
    ): Boolean {
        if (exemptUids.isEmpty()) return false
        if (ownerUid != null) return ownerUid in exemptUids
        if (hostname.isNullOrEmpty()) return false
        val host = hostname.lowercase()
        return exemptHostSuffixes.any { suffix ->
            val apex = suffix.removePrefix(".").lowercase()
            host == apex || host.endsWith(suffix.lowercase())
        }
    }

    /** Apex-domain suffixes only (never exact CDN edge hostnames, which rotate constantly) --
     *  keeps this list stable even as e.g. YouTube's edge-node hostnames change underneath it. */
    val DEFAULT_HOST_SUFFIXES = setOf(
        ".googlevideo.com",
        ".youtube.com",
        ".ytimg.com",
        ".ggpht.com",
    )
}
