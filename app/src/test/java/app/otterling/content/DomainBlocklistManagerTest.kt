package app.otterling.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainBlocklistManager] itself needs a real [android.content.Context] (SharedPreferences,
 * filesDir) that this project's plain-JUnit test setup can't construct (no Robolectric) -- so
 * these tests target [DomainBlocklistManager.fetchAllSources] and
 * [DomainBlocklistManager.combineSourceUrls] directly: the two pure, `Context`-free functions
 * `refresh()` was refactored to delegate to specifically so they're unit-testable.
 */
class DomainBlocklistManagerTest {
    @Test
    fun `fetchAllSources collects a succeeding source's domains even when another source throws`() {
        val result = DomainBlocklistManager.fetchAllSources(
            listOf("https://good.example/list", "https://bad.example/list"),
        ) { url, into ->
            if (url == "https://bad.example/list") throw java.io.IOException("simulated network failure")
            into.add("example.com")
            into.add("sub.example.com")
        }
        assertEquals(setOf("example.com", "sub.example.com"), result)
    }

    @Test
    fun `fetchAllSources returns everything when every source succeeds`() {
        val result = DomainBlocklistManager.fetchAllSources(
            listOf("https://a.example/list", "https://b.example/list"),
        ) { url, into ->
            into.add(if (url.contains("a.")) "a.example.com" else "b.example.com")
        }
        assertEquals(setOf("a.example.com", "b.example.com"), result)
    }

    @Test
    fun `fetchAllSources returns an empty set, not a thrown exception, when every source fails`() {
        val result = DomainBlocklistManager.fetchAllSources(
            listOf("https://a.example/list", "https://b.example/list"),
        ) { _, _ -> throw java.io.IOException("simulated network failure") }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `combineSourceUrls appends the classified-domains URL when present`() {
        val result = DomainBlocklistManager.combineSourceUrls(
            listOf("https://a.example/list", "https://b.example/list"),
            "https://vpn.bartholomew.help/filter-lists/classified-bad-domains.txt",
        )
        assertEquals(
            listOf(
                "https://a.example/list",
                "https://b.example/list",
                "https://vpn.bartholomew.help/filter-lists/classified-bad-domains.txt",
            ),
            result,
        )
    }

    @Test
    fun `combineSourceUrls leaves the source list unchanged when there is no classified-domains URL`() {
        val sourceUrls = listOf("https://a.example/list", "https://b.example/list")
        assertEquals(sourceUrls, DomainBlocklistManager.combineSourceUrls(sourceUrls, null))
    }
}
