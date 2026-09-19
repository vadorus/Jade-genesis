package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityExecutionEvidence
import com.jadegenesis.mobile.replay.CapabilityMeasurementProtocol
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMeasurementProtocolTest {

    @Test
    fun sixRoundsAreBalancedAndFiveRoundsAreRejected() {
        CapabilityMeasurementProtocol.requireBalancedRounds(6)

        try {
            CapabilityMeasurementProtocol.requireBalancedRounds(5)
            throw AssertionError("Expected odd round count to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("pair"))
        }
    }

    @Test
    fun failedWarmupStopsMeasuredSeries() {
        try {
            CapabilityMeasurementProtocol.requireVerifiedWarmup(
                expectedNodeId = "pc-a",
                evidence = evidence(success = false, verified = false)
            )
            throw AssertionError("Expected failed warm-up to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("pas vérifié"))
        }
    }

    @Test
    fun verifiedWarmupFromExpectedNodeIsAccepted() {
        CapabilityMeasurementProtocol.requireVerifiedWarmup(
            expectedNodeId = "pc-a",
            evidence = evidence(success = true, verified = true)
        )
    }

    private fun evidence(
        success: Boolean,
        verified: Boolean
    ): CapabilityExecutionEvidence = CapabilityExecutionEvidence(
        evidenceId = "warmup",
        taskId = "task-warmup",
        taskKind = "ffmpeg_transcode_probe_v1",
        providerId = "ffmpeg-local",
        operation = "media_transcode_probe",
        nodeId = "pc-a",
        nodeName = "PC A",
        success = success,
        verificationPassed = verified,
        durationMs = 80L,
        nodeExecutionMs = 40L,
        outputBytes = if (success) 1234L else 0L,
        outputSha256 = if (success) "a".repeat(64) else "",
        codec = if (success) "mpeg4" else "",
        width = if (success) 160 else 0,
        height = if (success) 90 else 0,
        fallbackUsed = false,
        startedAt = 1L,
        completedAt = 81L
    )
}
