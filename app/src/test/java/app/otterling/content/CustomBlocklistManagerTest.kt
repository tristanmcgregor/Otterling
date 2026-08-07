package app.otterling.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomBlocklistManagerTest {
    @Test
    fun normalizesBareDomainsAndUrls() {
        assertEquals(BlocklistEntry("example.com"), CustomBlocklistManager.normalize(" Example.COM. "))
        // Full URL with a path becomes a path rule (not stripped to the host alone).
        assertEquals(
            BlocklistEntry("example.com", "/path"),
            CustomBlocklistManager.normalize("https://example.com/path"),
        )
        assertEquals(
            BlocklistEntry("youtube.com", "/shorts"),
            CustomBlocklistManager.normalize("youtube.com/shorts"),
        )
        assertEquals(
            BlocklistEntry("youtube.com", "/shorts/abc"),
            CustomBlocklistManager.normalize("https://www.youtube.com/shorts/abc?feature=share"),
        )
    }

    @Test
    fun pathRuleMatchesPrefixOnly() {
        val shorts = BlocklistEntry("youtube.com", "/shorts")
        assertTrue(shorts.matches("youtube.com", "/shorts"))
        assertTrue(shorts.matches("www.youtube.com", "/shorts/abc123"))
        assertTrue(shorts.matches("m.youtube.com", "/shorts"))
        assertFalse(shorts.matches("youtube.com", "/watch"))
        assertFalse(shorts.matches("youtube.com", "/video"))
        assertFalse(shorts.matches("youtube.com", "/"))
        assertFalse(shorts.matches("vimeo.com", "/shorts"))
    }

    @Test
    fun domainOnlyMatchesWholeHost() {
        val all = BlocklistEntry("example.com")
        assertTrue(all.matches("example.com", "/anything"))
        assertTrue(all.matches("a.b.example.com", "/"))
        assertFalse(all.matches("example.org", "/"))
    }

    @Test
    fun rejectsUnsafeOrMalformedHosts() {
        listOf("", "localhost", "*.example.com", "127.0.0.1", "-bad.example").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                CustomBlocklistManager.normalize(input)
            }
        }
    }

    @Test
    fun browserUrlMatcherUsesPathRules() {
        val entries = listOf(BlocklistEntry("youtube.com", "/shorts"))
        assertTrue(UrlPathBlockEnforcer.shouldBlockBrowserUrl(entries, "https://www.youtube.com/shorts/xyz"))
        assertFalse(UrlPathBlockEnforcer.shouldBlockBrowserUrl(entries, "https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlPathBlockEnforcer.shouldBlockYoutubeShorts(entries))
        assertFalse(UrlPathBlockEnforcer.shouldBlockYoutubeShorts(listOf(BlocklistEntry("youtube.com", "/watch"))))
    }
}
