package com.jadegenesis.mobile

import com.jadegenesis.mobile.capability.CapabilitySelectionCoordinator
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.replay.CapabilityAlternativeTrace
import com.jadegenesis.mobile.replay.CapabilityDecisionTrace
import com.jadegenesis.mobile.replay.CapabilityDecisionTraceCodec
import com.jadegenesis.mobile.replay.CapabilityReplayLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDecisionTraceTest {

    @Test
    fun coordinatorRecordsRealFreeFirstSelectionContext() {
        val captured = mutableListOf<CapabilityDecisionTrace>()
        var now = 100L
        val coordinator = CapabilitySelectionCoordinator(
            traceSink = captured::add,
            clock = { now++ },
            traceIdProvider = { "capability-test-1" }
        )

        val outcome = coordinator.select(
            operation = "media_transcode",
            nodes = listOf(
                node(
                    id = "pc-b",
                    markers = listOf("local_free:ffmpeg-local")
                ),
                node(
                    id = "pc-a",
                    markers = listOf("local_free:ffmpeg-local")
                )
            )
        )

        assertEquals("ffmpeg-local", outcome.selected?.id)
        assertEquals("pc-a", outcome.selected?.nodeId)
        assertEquals(1, captured.size)
        assertEquals("capability_selection", outcome.trace.decisionKind)
        assertEquals("media_transcode", outcome.trace.operation)
        assertFalse(outcome.trace.allowPaidProviders)
        assertEquals(2, outcome.trace.alternatives.size)
        assertTrue(outcome.trace.alternatives.all { it.eligible })
        assertEquals("ffmpeg-local", outcome.trace.chosenProviderId)
        assertEquals("pc-a", outcome.trace.chosenNodeId)
    }

    @Test
    fun aaReplayReproducesCapabilityChoice() {
        val outcome = CapabilitySelectionCoordinator(
            traceIdProvider = { "capability-test-2" }
        ).select(
            operation = "3d_rendering",
            nodes = listOf(
                node(
                    id = "pc-home",
                    markers = listOf(
                        "local_free:ffmpeg-local",
                        "local_free:blender-local"
                    )
                )
            )
        )

        val replay = CapabilityReplayLab().replay(outcome.trace)

        assertTrue(replay.matched)
        assertEquals("blender-local", replay.replayedProviderId)
        assertEquals("pc-home", replay.replayedNodeId)
    }

    @Test
    fun paidAlternativeIsRejectedDuringReplayEvenIfMarkedEligible() {
        val trace = CapabilityDecisionTrace(
            traceId = "paid-test",
            decisionKind = "capability_selection",
            operation = "image_generation",
            policyName = "free_first_v0",
            preferLocalFree = true,
            allowCloudFreeFallback = true,
            allowPaidProviders = false,
            alternatives = listOf(
                CapabilityAlternativeTrace(
                    providerId = "paid-image",
                    displayName = "Paid Image",
                    nodeId = null,
                    providerType = "EXTERNAL_API",
                    costClass = "PAID",
                    available = true,
                    eligible = true,
                    requiresNetwork = true,
                    operations = listOf("image_generation")
                )
            ),
            chosenProviderId = null,
            chosenNodeId = null,
            startedAt = 1L,
            completedAt = 2L
        )

        val replay = CapabilityReplayLab().replay(trace)

        assertTrue(replay.matched)
        assertNull(replay.replayedProviderId)
    }

    @Test
    fun replayDetectsChangedRecordedWinner() {
        val outcome = CapabilitySelectionCoordinator(
            traceIdProvider = { "capability-test-3" }
        ).select(
            operation = "media_transcode",
            nodes = listOf(
                node(
                    id = "pc-a",
                    markers = listOf("local_free:ffmpeg-local")
                ),
                node(
                    id = "pc-b",
                    markers = listOf("local_free:ffmpeg-local")
                )
            )
        )

        val tampered = outcome.trace.copy(
            chosenNodeId = "pc-b"
        )
        val report = CapabilityReplayLab().evaluate(listOf(tampered))

        assertEquals(1, report.total)
        assertEquals(1, report.mismatched)
        assertFalse(report.passed)
    }

    @Test
    fun codecRoundTripPreservesSelectionWithoutUserContent() {
        val original = CapabilitySelectionCoordinator(
            traceIdProvider = { "capability-codec" },
            clock = { 42L }
        ).select(
            operation = "audio_video_mux",
            nodes = listOf(
                node(
                    id = "pc-home",
                    markers = listOf("local_free:ffmpeg-local")
                )
            )
        ).trace

        val encoded = CapabilityDecisionTraceCodec.toJson(original)
        val restored = CapabilityDecisionTraceCodec.fromJson(encoded)

        assertEquals(original, restored)
        assertFalse(encoded.has("payload"))
        assertFalse(encoded.has("output"))
        assertFalse(encoded.has("user_input"))
    }

    @Test
    fun noDiscoveredProviderRecordsAbstentionAndReplaysIt() {
        val outcome = CapabilitySelectionCoordinator(
            traceIdProvider = { "capability-empty" }
        ).select(
            operation = "text_to_speech",
            nodes = listOf(
                node(
                    id = "pc-home",
                    markers = emptyList()
                )
            )
        )

        assertNull(outcome.selected)
        assertTrue(outcome.trace.alternatives.isEmpty())
        assertTrue(CapabilityReplayLab().replay(outcome.trace).matched)
    }

    private fun node(
        id: String,
        markers: List<String>
    ): GenesisNode = GenesisNode(
        nodeId = id,
        name = id,
        kind = NodeKind.PC,
        status = NodeStatus.ONLINE,
        capabilities = listOf("node_runtime") + markers
    )
}
