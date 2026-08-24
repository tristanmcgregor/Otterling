package app.otterling.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainBlocklistManager] itself needs a real [android.content.Context] (SharedPreferences,
 * filesDir) that this project's plain-JUnit test setup can't construct (no Robolectric) -- so
 * these tests target [DomainBlocklistManager.fetchAllSources] directly: the pure, `Context`-free
 * function `refresh()` was refactored to delegate to so it's unit-testable.
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
}
