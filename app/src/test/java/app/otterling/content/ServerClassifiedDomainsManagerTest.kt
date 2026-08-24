package app.otterling.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ServerClassifiedDomainsManager] itself needs a real [android.content.Context] this project's
 * plain-JUnit test setup can't construct (no Robolectric) -- so this targets
 * [ServerClassifiedDomainsManager.classifiedDomainsUrl] directly: the pure, `Context`-free
 * function [ServerClassifiedDomainsManager.sourceUrl] delegates to.
 */
class ServerClassifiedDomainsManagerTest {
    @Test
    fun `classifiedDomainsUrl builds the filter-lists path from a configured host`() {
        assertEquals(
            "https://vpn.bartholomew.help/filter-lists/classified-bad-domains.txt",
            ServerClassifiedDomainsManager.classifiedDomainsUrl("vpn.bartholomew.help"),
        )
    }

    @Test
    fun `classifiedDomainsUrl trims whitespace around the host`() {
        assertEquals(
            "https://vpn.bartholomew.help/filter-lists/classified-bad-domains.txt",
            ServerClassifiedDomainsManager.classifiedDomainsUrl("  vpn.bartholomew.help  "),
        )
    }

    @Test
    fun `classifiedDomainsUrl returns null for a blank host`() {
        assertNull(ServerClassifiedDomainsManager.classifiedDomainsUrl(""))
        assertNull(ServerClassifiedDomainsManager.classifiedDomainsUrl("   "))
    }
}
