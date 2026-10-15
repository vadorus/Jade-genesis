package com.jadegenesis.mobile.cognitive

/**
 * Bound the number of expensive remote generation passes for a slow answer.
 * The initial answer stays marked as NOT VERIFIED when CRITIC is skipped.
 * This is not a cancellation mechanism or an end-to-end latency guarantee.
 */
internal object CognitiveReviewBudgetPolicy {
    const val MAX_INITIAL_GENERATION_BEFORE_CRITIC_MS = 70_000L

    fun skipCritic(verificationRequested: Boolean, initialDurationMs: Long): Boolean =
        verificationRequested && initialDurationMs >= MAX_INITIAL_GENERATION_BEFORE_CRITIC_MS
}
