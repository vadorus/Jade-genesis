package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityAlternativeTrace
import com.jadegenesis.mobile.replay.CapabilityDecisionTrace
import com.jadegenesis.mobile.replay.CapabilityManualChallengerPolicy
import com.jadegenesis.mobile.replay.CapabilityShadowLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityShadowLabTest {

    @Test
    fun manualChallengerSelectsObservedFreeAlternativeInShadow() {
        val trace = trace(
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a"),
                alternative("ffmpeg-local", "pc-b")
            )
        )
        val policy = policy(
            preferredProviderId = "ffmpeg-local",
            preferredNodeId = "pc-b"
        )

        val observation = CapabilityShadowLab().compare(trace, policy)

        assertTrue(observation.applicable)
        assertTrue(observation.targetObserved)
        assertTrue(observation.changed)
        assertEquals("ffmpeg-local", observation.shadowProviderId)
        assertEquals("pc-b", observation.shadowNodeId)
        assertEquals("pc-a", observation.incumbentNodeId)
    }

    @Test
    fun paidProviderIsRejectedEvenWhenHistoricalTraceAllowedPaid() {
        val trace = trace(
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a"),
                alternative(
                    providerId = "paid-media",
                    nodeId = null,
                    costClass = "PAID",
                    requiresNetwork = true
                )
            ),
            allowPaidProviders = true
        )
        val policy = policy(preferredProviderId = "paid-media")

        val observation = CapabilityShadowLab().compare(trace, policy)

        assertTrue(observation.applicable)
        assertFalse(observation.targetObserved)
        assertFalse(observation.changed)
        assertEquals("ffmpeg-local", observation.shadowProviderId)
        assertEquals("pc-a", observation.shadowNodeId)
    }

    @Test
    fun unobservedTargetNeverGetsInvented() {
        val trace = trace(
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a")
            )
        )
        val policy = policy(preferredProviderId = "mystery-provider")

        val observation = CapabilityShadowLab().compare(trace, policy)

        assertFalse(observation.targetObserved)
        assertFalse(observation.changed)
        assertEquals(trace.chosenProviderId, observation.shadowProviderId)
        assertEquals(trace.chosenNodeId, observation.shadowNodeId)
    }

    @Test
    fun preferredNodeMustMatchObservedAlternative() {
        val trace = trace(
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a"),
                alternative("ffmpeg-local", "pc-b")
            )
        )
        val policy = policy(
            preferredProviderId = "ffmpeg-local",
            preferredNodeId = "pc-c"
        )

        val observation = CapabilityShadowLab().compare(trace, policy)

        assertFalse(observation.targetObserved)
        assertFalse(observation.changed)
        assertEquals("pc-a", observation.shadowNodeId)
    }

    @Test
    fun otherOperationsAreSkippedWithoutChangingDecision() {
        val trace = trace(
            operation = "audio_video_mux",
            alternatives = listOf(
                alternative(
                    "ffmpeg-local",
                    "pc-a",
                    operations = listOf("audio_video_mux")
                )
            )
        )
        val policy = policy(
            operation = "media_transcode",
            preferredProviderId = "ffmpeg-local"
        )

        val observation = CapabilityShadowLab().compare(trace, policy)

        assertFalse(observation.applicable)
        assertFalse(observation.changed)
        assertEquals("ffmpeg-local", observation.shadowProviderId)
    }

    @Test
    fun reportSeparatesComparableEvidenceFromMissingCoverage() {
        val changed = trace(
            traceId = "changed",
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a"),
                alternative("ffmpeg-local", "pc-b")
            )
        )
        val noTarget = trace(
            traceId = "no-target",
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a")
            )
        )
        val skipped = trace(
            traceId = "skipped",
            operation = "audio_video_mux",
            alternatives = listOf(
                alternative(
                    "ffmpeg-local",
                    "pc-a",
                    operations = listOf("audio_video_mux")
                )
            )
        )

        val report = CapabilityShadowLab().evaluate(
            traces = listOf(changed, noTarget, skipped),
            policy = policy(
                preferredProviderId = "ffmpeg-local",
                preferredNodeId = "pc-b"
            )
        )

        assertEquals(3, report.totalTraces)
        assertEquals(2, report.applicableTraces)
        assertEquals(1, report.skippedOtherOperation)
        assertEquals(1, report.tracesWithTargetObserved)
        assertEquals(1, report.tracesWithoutTargetObserved)
        assertEquals(1, report.changedChoices)
        assertEquals(1, report.unchangedChoices)
        assertEquals(0.5, report.changeRate, 0.0001)
        assertTrue(report.hasComparableEvidence)
    }

    private fun policy(
        operation: String = "media_transcode",
        preferredProviderId: String,
        preferredNodeId: String? = null
    ): CapabilityManualChallengerPolicy =
        CapabilityManualChallengerPolicy(
            policyId = "manual-ffmpeg-shadow-v0",
            operation = operation,
            preferredProviderId = preferredProviderId,
            preferredNodeId = preferredNodeId
        )

    private fun trace(
        traceId: String = "trace-1",
        operation: String = "media_transcode",
        alternatives: List<CapabilityAlternativeTrace>,
        allowPaidProviders: Boolean = false
    ): CapabilityDecisionTrace =
        CapabilityDecisionTrace(
            traceId = traceId,
            decisionKind = "capability_selection",
            operation = operation,
            policyName = "free_first_v0",
            preferLocalFree = true,
            allowCloudFreeFallback = true,
            allowPaidProviders = allowPaidProviders,
            alternatives = alternatives,
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            startedAt = 1L,
            completedAt = 2L
        )

    private fun alternative(
        providerId: String,
        nodeId: String?,
        costClass: String = "LOCAL_FREE",
        available: Boolean = true,
        requiresNetwork: Boolean = false,
        operations: List<String> = listOf("media_transcode")
    ): CapabilityAlternativeTrace =
        CapabilityAlternativeTrace(
            providerId = providerId,
            displayName = providerId,
            nodeId = nodeId,
            providerType = "LOCAL_TOOL",
            costClass = costClass,
            available = available,
            eligible = available,
            requiresNetwork = requiresNetwork,
            operations = operations
        )
}
