package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityPairStatus
import com.jadegenesis.mobile.replay.CapabilityPairedComparison
import com.jadegenesis.mobile.replay.CapabilityRepeatedPairAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityRepeatedPairAnalyzerTest {

    @Test
    fun aggregatesVerifiedRoundsAndLatencyStatistics() {
        val comparisons = listOf(
            comparison("a1", 100, 90, "pc-b"),
            comparison("a2", 110, 95, "pc-b"),
            comparison("a3", 105, 120, "pc-a"),
            comparison("a4", 98, 98, null),
            comparison("a5", 140, 100, "pc-b")
        )

        val report = CapabilityRepeatedPairAnalyzer.analyze(
            comparisons = comparisons,
            roundsRequested = 5
        )

        assertEquals(5, report.roundsCompleted)
        assertEquals(5, report.incumbentVerifiedRounds)
        assertEquals(5, report.challengerVerifiedRounds)
        assertEquals(5, report.bothVerifiedRounds)
        assertEquals(5, report.comparableLatencyRounds)
        assertEquals(1, report.incumbentFasterRounds)
        assertEquals(3, report.challengerFasterRounds)
        assertEquals(1, report.tiedRounds)
        assertEquals(105L, report.incumbentMedianMs)
        assertEquals(98L, report.challengerMedianMs)
        assertEquals(140L, report.incumbentP95Ms)
        assertEquals(120L, report.challengerP95Ms)
        assertEquals(-15L, report.medianLatencyDeltaMs)
        assertFalse(report.automaticPromotionAllowed)
    }

    @Test
    fun failedVerificationIsExcludedFromLatencyStatistics() {
        val comparisons = listOf(
            comparison("ok", 100, 80, "pc-b"),
            CapabilityPairedComparison(
                comparisonId = "failed",
                providerId = "ffmpeg-local",
                operation = "media_transcode_probe",
                incumbentEvidenceId = "i2",
                incumbentNodeId = "pc-a",
                incumbentNodeName = "PC A",
                incumbentSuccess = true,
                incumbentVerified = true,
                incumbentDurationMs = 90,
                challengerEvidenceId = "c2",
                challengerNodeId = "pc-b",
                challengerNodeName = "PC B",
                challengerSuccess = false,
                challengerVerified = false,
                challengerDurationMs = 10,
                status = CapabilityPairStatus.INCUMBENT_ONLY_VERIFIED,
                latencyComparable = false,
                latencyDeltaMs = null,
                fasterNodeId = null,
                automaticPromotionAllowed = false
            )
        )

        val report = CapabilityRepeatedPairAnalyzer.analyze(
            comparisons = comparisons,
            roundsRequested = 2
        )

        assertEquals(2, report.incumbentVerifiedRounds)
        assertEquals(1, report.challengerVerifiedRounds)
        assertEquals(1, report.bothVerifiedRounds)
        assertEquals(1, report.comparableLatencyRounds)
        assertEquals(95L, report.incumbentMedianMs)
        assertEquals(80L, report.challengerMedianMs)
        assertEquals(-20L, report.medianLatencyDeltaMs)
    }

    @Test
    fun emptyReportDoesNotInventMeasurements() {
        val report = CapabilityRepeatedPairAnalyzer.analyze(
            comparisons = emptyList(),
            roundsRequested = 5
        )

        assertEquals(0, report.roundsCompleted)
        assertNull(report.incumbentMedianMs)
        assertNull(report.challengerMedianMs)
        assertNull(report.medianLatencyDeltaMs)
        assertFalse(report.automaticPromotionAllowed)
    }

    private fun comparison(
        id: String,
        incumbentMs: Long,
        challengerMs: Long,
        fasterNodeId: String?
    ): CapabilityPairedComparison =
        CapabilityPairedComparison(
            comparisonId = id,
            providerId = "ffmpeg-local",
            operation = "media_transcode_probe",
            incumbentEvidenceId = "inc-$id",
            incumbentNodeId = "pc-a",
            incumbentNodeName = "PC A",
            incumbentSuccess = true,
            incumbentVerified = true,
            incumbentDurationMs = incumbentMs,
            challengerEvidenceId = "chal-$id",
            challengerNodeId = "pc-b",
            challengerNodeName = "PC B",
            challengerSuccess = true,
            challengerVerified = true,
            challengerDurationMs = challengerMs,
            status = CapabilityPairStatus.BOTH_VERIFIED,
            latencyComparable = true,
            latencyDeltaMs = challengerMs - incumbentMs,
            fasterNodeId = fasterNodeId,
            automaticPromotionAllowed = false
        )
}
