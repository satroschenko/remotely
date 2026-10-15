package app.zalatv.core

sealed interface RetryDecision {
    data class RetryAfter(val delayMs: Long, val attempt: Int) : RetryDecision
    data object GiveUp : RetryDecision
}

/**
 * Bounded backoff shared by login, data loading and the player:
 * after [delaysMs].size automatic retries the caller must ask the user ("Повторить").
 */
class RetryPolicy(private val delaysMs: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000)) {
    var failures: Int = 0
        private set

    fun onFailure(): RetryDecision {
        failures++
        return if (failures <= delaysMs.size) RetryDecision.RetryAfter(delaysMs[failures - 1], failures)
        else RetryDecision.GiveUp
    }

    fun onSuccess() {
        failures = 0
    }

    val exhausted: Boolean get() = failures > delaysMs.size
}
