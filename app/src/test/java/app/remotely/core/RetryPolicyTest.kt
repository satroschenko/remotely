package app.remotely.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test fun fiveRetriesThenGiveUp() {
        val p = RetryPolicy()
        val delays = (1..5).map { (p.onFailure() as RetryDecision.RetryAfter).delayMs }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
        assertEquals(RetryDecision.GiveUp, p.onFailure())
        assertTrue(p.exhausted)
    }

    @Test fun successResets() {
        val p = RetryPolicy()
        repeat(6) { p.onFailure() }
        p.onSuccess()
        assertFalse(p.exhausted)
        assertEquals(RetryDecision.RetryAfter(2_000, 1), p.onFailure())
    }
}
