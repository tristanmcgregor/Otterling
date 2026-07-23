package au.com.tbmcgregor.bwparker.familyguard.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomBlocklistManagerTest {
    @Test
    fun normalizesBareDomainsAndUrls() {
        assertEquals("example.com", CustomBlocklistManager.normalize(" Example.COM. "))
        assertEquals("example.com", CustomBlocklistManager.normalize("https://example.com/path"))
    }

    @Test
    fun rejectsUnsafeOrMalformedHosts() {
        listOf("", "localhost", "*.example.com", "127.0.0.1", "-bad.example").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                CustomBlocklistManager.normalize(input)
            }
        }
    }
}
