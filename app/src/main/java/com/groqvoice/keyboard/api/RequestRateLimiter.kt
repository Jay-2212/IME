package com.groqvoice.keyboard.api

import java.util.ArrayDeque
import kotlin.math.ceil

/**
 * Sliding-window request limiter used to keep client traffic below Groq tier defaults.
 *
 * TSD Appendix A specifies a default limit of 20 requests per minute. This limiter is
 * process-local and thread-safe; it prevents avoidable 429 responses before a request is sent.
 */
class RequestRateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Decision returned for each request attempt.
     *
     * @param allowed True when the request can proceed immediately.
     * @param retryAfterMs Time remaining before one request slot is guaranteed to open.
     */
    data class Decision(
        val allowed: Boolean,
        val retryAfterMs: Long
    ) {
        /** Rounded-up seconds representation suitable for user-facing messages. */
        val retryAfterSeconds: Int
            get() = ceil(retryAfterMs.coerceAtLeast(0L) / 1000.0).toInt()
    }

    private val requestTimestamps = ArrayDeque<Long>()

    /**
     * Attempts to consume one request slot.
     *
     * This method is synchronized because repositories can be called from multiple coroutine
     * dispatchers and worker threads.
     */
    @Synchronized
    fun tryAcquire(): Decision {
        val now = clock()
        trimWindow(now)

        if (requestTimestamps.size >= maxRequests) {
            val oldest = requestTimestamps.firstOrNull() ?: now
            val retryAfter = (oldest + windowMs) - now
            return Decision(allowed = false, retryAfterMs = retryAfter.coerceAtLeast(1L))
        }

        requestTimestamps.addLast(now)
        return Decision(allowed = true, retryAfterMs = 0L)
    }

    /**
     * Clears historical timestamps. Primarily useful for tests.
     */
    @Synchronized
    fun reset() {
        requestTimestamps.clear()
    }

    @Synchronized
    private fun trimWindow(now: Long) {
        while (requestTimestamps.isNotEmpty() && now - requestTimestamps.first() >= windowMs) {
            requestTimestamps.removeFirst()
        }
    }
}
