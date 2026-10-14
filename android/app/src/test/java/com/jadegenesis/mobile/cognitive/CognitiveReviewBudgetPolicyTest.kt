package com.jadegenesis.mobile.cognitive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveReviewBudgetPolicyTest {
    @Test fun longFirstPassAvoidsExtraRemoteRounds() {
        assertTrue(CognitiveReviewBudgetPolicy.skipCritic(true, 70_000L))
        assertTrue(CognitiveReviewBudgetPolicy.skipCritic(true, 122_870L))
        assertFalse(CognitiveReviewBudgetPolicy.skipCritic(true, 69_999L))
        assertFalse(CognitiveReviewBudgetPolicy.skipCritic(false, 140_000L))
    }
}
