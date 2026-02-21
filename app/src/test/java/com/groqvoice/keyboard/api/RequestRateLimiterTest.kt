package com.groqvoice.keyboard.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RequestRateLimiter].
 */
class RequestRateLimiterTest {

    @Test
    fun `allows requests up to limit within window`() {
        var now = 1_000L
        val limiter = RequestRateLimiter(
            maxRequests = 3,
            windowMs = 60_000L,
            clock = { now }
        )

        assertTrue(limiter.tryAcquire().allowed)
        assertTrue(limiter.tryAcquire().allowed)
        assertTrue(limiter.tryAcquire().allowed)
        assertFalse(limiter.tryAcquire().allowed)
    }

    @Test
    fun `releases slot after window passes`() {
        var now = 1_000L
        val limiter = RequestRateLimiter(
            maxRequests = 1,
            windowMs = 1_000L,
            clock = { now }
        )

        assertTrue(limiter.tryAcquire().allowed)
        assertFalse(limiter.tryAcquire().allowed)

        now += 1_001L
        assertTrue(limiter.tryAcquire().allowed)
    }

    @Test
    fun `retryAfterSeconds rounds up remaining window`() {
        var now = 0L
        val limiter = RequestRateLimiter(
            maxRequests = 1,
            windowMs = 1_500L,
            clock = { now }
        )

        assertTrue(limiter.tryAcquire().allowed)
        now = 500L

        val decision = limiter.tryAcquire()
        assertFalse(decision.allowed)
        assertTrue(decision.retryAfterSeconds >= 1)
    }
}
