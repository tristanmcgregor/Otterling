package app.otterling.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmExemptionPolicyTest {
    private val suffixes = setOf(".googlevideo.com", ".youtube.com")

    @Test
    fun emptyExemptSetNeverExempts() {
        assertFalse(MitmExemptionPolicy.isExempt(ownerUid = 42, exemptUids = emptySet(), hostname = "youtube.com", exemptHostSuffixes = suffixes))
        assertFalse(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = emptySet(), hostname = "youtube.com", exemptHostSuffixes = suffixes))
    }

    @Test
    fun uidMatchExemptsRegardlessOfHostname() {
        assertTrue(MitmExemptionPolicy.isExempt(ownerUid = 10001, exemptUids = setOf(10001), hostname = null, exemptHostSuffixes = suffixes))
        assertTrue(MitmExemptionPolicy.isExempt(ownerUid = 10001, exemptUids = setOf(10001), hostname = "totally-unrelated.example", exemptHostSuffixes = suffixes))
    }

    @Test
    fun uidPresentButNotMatchingNeverFallsThroughToHostname() {
        // Critical precedence case: a resolved UID that isn't in the exempt set must never be
        // rescued by a hostname match -- an unrelated app hitting a curated domain by coincidence
        // must not get exempted.
        assertFalse(
            MitmExemptionPolicy.isExempt(ownerUid = 99999, exemptUids = setOf(10001), hostname = "youtube.com", exemptHostSuffixes = suffixes),
        )
    }

    @Test
    fun uidNullWithNoHostnameIsNotExempt() {
        assertFalse(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = null, exemptHostSuffixes = suffixes))
        assertFalse(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "", exemptHostSuffixes = suffixes))
    }

    @Test
    fun uidNullFallsBackToHostnameSuffix() {
        assertTrue(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "youtube.com", exemptHostSuffixes = suffixes))
        assertTrue(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "www.youtube.com", exemptHostSuffixes = suffixes))
        assertTrue(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "YOUTUBE.COM", exemptHostSuffixes = suffixes))
        assertTrue(
            MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "rr3---sn-abc.googlevideo.com", exemptHostSuffixes = suffixes),
        )
    }

    @Test
    fun uidNullWithNonMatchingHostnameIsNotExempt() {
        assertFalse(MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "example.com", exemptHostSuffixes = suffixes))
    }

    @Test
    fun lookalikeDomainIsNeverMatchedBySuffixCheck() {
        assertFalse(
            MitmExemptionPolicy.isExempt(
                ownerUid = null,
                exemptUids = setOf(10001),
                hostname = "evil-googlevideo.com.attacker.net",
                exemptHostSuffixes = suffixes,
            ),
        )
        assertFalse(
            MitmExemptionPolicy.isExempt(ownerUid = null, exemptUids = setOf(10001), hostname = "notgooglevideo.com", exemptHostSuffixes = suffixes),
        )
    }
}
