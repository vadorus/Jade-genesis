package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityCanaryRecommendationLab
import com.jadegenesis.mobile.replay.CapabilityCanaryRecommendationStatus
import com.jadegenesis.mobile.replay.CapabilityRepeatedPairReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCanaryRecommendationLabTest {

    @Test
    fun strongVerifiedSignalBecomesManualCanaryEligible() {
        val result = CapabilityCanaryRecommendationLab.evaluate(
            report(
                rounds = 6,
                incumbentVerified = 6,
                challengerVerified = 6,
                bothVerified = 6,
                comparable = 6,
                challengerFaster = 5,
                incumbentMedian = 100,
                challengerMedian = 80
            )
        )

        assertEquals(
            CapabilityCanaryRecommendationStatus.ELIGIBLE_FOR_MANUAL_CANARY,
            result.status
        )
        assertTrue(result.requiresExplicitApproval)
        assertFalse(result.automaticPromotionAllowed)
        assertNotNull(result.medianImprovementPercent)
        assertEquals(20.0, result.medianImprovementPercent!!, 0.0001)
    }

    @Test
    fun fewerThanSixRoundsIsInsufficient() {
        val result = CapabilityCanaryRecommendationLab.evaluate(
            report(
                rounds = 5,
                incumbentVerified = 5,
                challengerVerified = 5,
                bothVerified = 5,
                comparable = 5,
                challengerFaster = 5,
                incumbentMedian = 100,
                challengerMedian = 50
            )
        )

        assertEquals(
            CapabilityCanaryRecommendationStatus.INSUFFICIENT_EVIDENCE,
            result.status
        )
        assertFalse(result.automaticPromotionAllowed)
    }

    @Test
    fun verificationRegressionBlocksCanary() {
        val result = CapabilityCanaryRecommendationLab.evaluate(
            report(
                rounds = 6,
                incumbentVerified = 6,
                challengerVerified = 5,
                bothVerified = 5,
                comparable = 5,
                challengerFaster = 5,
                incumbentMedian = 100,
                challengerMedian = 70
            )
        )

        assertEquals(
            CapabilityCanaryRecommendationStatus.CHALLENGER_VERIFICATION_REGRESSION,
            result.status
        )
    }

    @Test
    fun smallMedianGainIsInconclusive() {
        val result = CapabilityCanaryRecommendationLab.evaluate(
            report(
                rounds = 6,
                incumbentVerified = 6,
                challengerVerified = 6,
                bothVerified = 6,
                comparable = 6,
                challengerFaster = 5,
                incumbentMedian = 100,
                challengerMedian = 95
            )
        )

        assertEquals(
            CapabilityCanaryRecommendationStatus.INCONCLUSIVE,
            result.status
        )
    }

    @Test
    fun fewerThanEightyPercentFasterRoundsIsInconclusive() {
        val result = CapabilityCanaryRecommendationLab.evaluate(
            report(
                rounds = 6,
                incumbentVerified = 6,
                challengerVerified = 6,
                bothVerified = 6,
                comparable = 6,
                challengerFaster = 4,
                incumbentMedian = 100,
                challengerMedian = 70
            )
        )

        assertEquals(
            CapabilityCanaryRecommendationStatus.INCONCLUSIVE,
            result.status
        )
    }

    private fun report(
        rounds: Int,
        incumbentVerified: Int,
        challengerVerified: Int,
        bothVerified: Int,
        comparable: Int,
        challengerFaster: Int,
        incumbentMedian: Long,
        challengerMedian: Long
    ): CapabilityRepeatedPairReport =
        CapabilityRepeatedPairReport(
            providerId = "ffmpeg-local",
            operation = "media_transcode_probe",
            incumbentNodeId = "pc-a",
            challengerNodeId = "pc-b",
            roundsRequested = rounds,
            roundsCompleted = rounds,
            incumbentVerifiedRounds = incumbentVerified,
            challengerVerifiedRounds = challengerVerified,
            bothVerifiedRounds = bothVerified,
            comparableLatencyRounds = comparable,
            incumbentFasterRounds = comparable - challengerFaster,
            challengerFasterRounds = challengerFaster,
            tiedRounds = 0,
            incumbentMedianMs = incumbentMedian,
            challengerMedianMs = challengerMedian,
            incumbentP95Ms = incumbentMedian,
            challengerP95Ms = challengerMedian,
            medianLatencyDeltaMs = challengerMedian - incumbentMedian,
            automaticPromotionAllowed = false
        )
}
