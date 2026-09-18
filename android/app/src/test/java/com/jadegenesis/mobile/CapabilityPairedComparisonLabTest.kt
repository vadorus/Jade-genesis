package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityExecutionEvidence
import com.jadegenesis.mobile.replay.CapabilityPairStatus
import com.jadegenesis.mobile.replay.CapabilityPairedComparisonLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPairedComparisonLabTest {

    @Test
    fun bothVerifiedAllowsLatencyComparison() {
        val incumbent = evidence(
            evidenceId = "inc",
            nodeId = "pc-a",
            durationMs = 120L,
            endToEndMs = 150L,
            success = true,
            verified = true
        )
        val challenger = evidence(
            evidenceId = "chal",
            nodeId = "pc-b",
            durationMs = 80L,
            endToEndMs = 900L,
            success = true,
            verified = true
        )

        val comparison = CapabilityPairedComparisonLab().compare(
            incumbent,
            challenger
        )

        assertEquals(
            CapabilityPairStatus.BOTH_VERIFIED,
            comparison.status
        )
        assertTrue(comparison.latencyComparable)
        assertEquals(-40L, comparison.latencyDeltaMs)
        assertEquals("pc-b", comparison.fasterNodeId)
        assertEquals(150L, comparison.incumbentEndToEndMs)
        assertEquals(900L, comparison.challengerEndToEndMs)
        assertFalse(comparison.automaticPromotionAllowed)
    }

    @Test
    fun latencyIsIgnoredWhenChallengerFailsVerification() {
        val incumbent = evidence(
            evidenceId = "inc",
            nodeId = "pc-a",
            durationMs = 120L,
            success = true,
            verified = true
        )
        val challenger = evidence(
            evidenceId = "chal",
            nodeId = "pc-b",
            durationMs = 20L,
            success = false,
            verified = false
        )

        val comparison = CapabilityPairedComparisonLab().compare(
            incumbent,
            challenger
        )

        assertEquals(
            CapabilityPairStatus.INCUMBENT_ONLY_VERIFIED,
            comparison.status
        )
        assertFalse(comparison.latencyComparable)
        assertNull(comparison.latencyDeltaMs)
        assertNull(comparison.fasterNodeId)
    }

    @Test
    fun challengerOnlyVerifiedIsRecordedWithoutPromotion() {
        val comparison = CapabilityPairedComparisonLab().compare(
            evidence(
                evidenceId = "inc",
                nodeId = "pc-a",
                durationMs = 50L,
                success = false,
                verified = false
            ),
            evidence(
                evidenceId = "chal",
                nodeId = "pc-b",
                durationMs = 100L,
                success = true,
                verified = true
            )
        )

        assertEquals(
            CapabilityPairStatus.CHALLENGER_ONLY_VERIFIED,
            comparison.status
        )
        assertFalse(comparison.latencyComparable)
        assertFalse(comparison.automaticPromotionAllowed)
    }

    @Test
    fun sameNodeCannotBeComparedAgainstItself() {
        val lab = CapabilityPairedComparisonLab()

        try {
            lab.compare(
                evidence(
                    evidenceId = "a",
                    nodeId = "pc-a",
                    durationMs = 10L,
                    success = true,
                    verified = true
                ),
                evidence(
                    evidenceId = "b",
                    nodeId = "pc-a",
                    durationMs = 20L,
                    success = true,
                    verified = true
                )
            )
            throw AssertionError("Expected compare() to reject same node.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                expected.message.orEmpty()
                    .contains("different nodes")
            )
        }
    }

    @Test
    fun latestForNodesUsesMostRecentEvidenceAndFailsClosedWhenMissing() {
        val lab = CapabilityPairedComparisonLab()
        val evidence = listOf(
            evidence(
                evidenceId = "pc-a-latest",
                nodeId = "pc-a",
                durationMs = 100L,
                success = true,
                verified = true
            ),
            evidence(
                evidenceId = "pc-a-old",
                nodeId = "pc-a",
                durationMs = 900L,
                success = true,
                verified = true
            ),
            evidence(
                evidenceId = "pc-b-latest",
                nodeId = "pc-b",
                durationMs = 110L,
                success = true,
                verified = true
            )
        )

        val comparison = lab.latestForNodes(
            evidence = evidence,
            incumbentNodeId = "pc-a",
            challengerNodeId = "pc-b"
        )

        requireNotNull(comparison)
        assertEquals("pc-a-latest", comparison.incumbentEvidenceId)
        assertEquals("pc-b-latest", comparison.challengerEvidenceId)

        assertNull(
            lab.latestForNodes(
                evidence = evidence,
                incumbentNodeId = "pc-a",
                challengerNodeId = "pc-missing"
            )
        )
    }

    private fun evidence(
        evidenceId: String,
        nodeId: String,
        durationMs: Long,
        endToEndMs: Long = durationMs + 300L,
        success: Boolean,
        verified: Boolean
    ): CapabilityExecutionEvidence =
        CapabilityExecutionEvidence(
            evidenceId = evidenceId,
            taskId = "task-$evidenceId",
            taskKind = "ffmpeg_transcode_probe_v1",
            providerId = "ffmpeg-local",
            operation = "media_transcode_probe",
            nodeId = nodeId,
            nodeName = nodeId,
            success = success,
            verificationPassed = verified,
            durationMs = endToEndMs,
            nodeExecutionMs = durationMs,
            outputBytes = if (success) 1234L else 0L,
            outputSha256 = if (success) {
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            } else {
                ""
            },
            codec = if (success) "mpeg4" else "",
            width = if (success) 160 else 0,
            height = if (success) 90 else 0,
            fallbackUsed = false,
            startedAt = 1L,
            completedAt = 1L + durationMs
        )
}
