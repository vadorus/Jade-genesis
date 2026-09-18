package com.jadegenesis.mobile

import com.jadegenesis.mobile.replay.CapabilityAlternativeTrace
import com.jadegenesis.mobile.replay.CapabilityBranchHarvester
import com.jadegenesis.mobile.replay.CapabilityDecisionTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityBranchHarvesterTest {

    @Test
    fun harvestsObservedAlternativeWithoutReusingIncumbent() {
        val trace = trace(
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-a"
                ),
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-b"
                )
            )
        )

        val branches = CapabilityBranchHarvester().harvest(trace)

        assertEquals(1, branches.size)
        assertEquals("ffmpeg-local", branches.single().challengerProviderId)
        assertEquals("pc-b", branches.single().challengerNodeId)
        assertEquals("pc-a", branches.single().incumbentNodeId)
    }

    @Test
    fun neverInventsUnobservedProvider() {
        val trace = trace(
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-a"
                )
            )
        )

        val branches = CapabilityBranchHarvester().harvest(trace)

        assertTrue(branches.isEmpty())
    }

    @Test
    fun paidAlternativeIsExcludedWhenPaidProvidersAreDisabled() {
        val trace = trace(
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-a"
                ),
                alternative(
                    providerId = "paid-media",
                    nodeId = null,
                    costClass = "PAID",
                    requiresNetwork = true
                )
            ),
            allowPaidProviders = false
        )

        val branches = CapabilityBranchHarvester().harvest(trace)

        assertTrue(branches.isEmpty())
    }

    @Test
    fun unavailableAlternativeIsExcluded() {
        val trace = trace(
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-a"
                ),
                alternative(
                    providerId = "ffmpeg-local",
                    nodeId = "pc-b",
                    available = false
                )
            )
        )

        assertTrue(CapabilityBranchHarvester().harvest(trace).isEmpty())
    }

    @Test
    fun reportMeasuresRealCounterfactualCoverage() {
        val covered = trace(
            traceId = "covered",
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a"),
                alternative("ffmpeg-local", "pc-b")
            )
        )
        val uncovered = trace(
            traceId = "uncovered",
            chosenProviderId = "ffmpeg-local",
            chosenNodeId = "pc-a",
            alternatives = listOf(
                alternative("ffmpeg-local", "pc-a")
            )
        )

        val report = CapabilityBranchHarvester().evaluate(
            listOf(covered, uncovered)
        )

        assertEquals(2, report.totalTraces)
        assertEquals(1, report.tracesWithCounterfactuals)
        assertEquals(1, report.tracesWithoutCounterfactuals)
        assertEquals(1, report.harvestedBranches)
        assertEquals(0.5, report.coverageRate, 0.0001)
        assertTrue(report.hasCoverage)
    }

    @Test
    fun emptyDatasetDoesNotClaimCoverage() {
        val report = CapabilityBranchHarvester().evaluate(emptyList())

        assertEquals(0.0, report.coverageRate, 0.0001)
        assertFalse(report.hasCoverage)
    }

    private fun trace(
        traceId: String = "trace-1",
        chosenProviderId: String?,
        chosenNodeId: String?,
        alternatives: List<CapabilityAlternativeTrace>,
        allowPaidProviders: Boolean = false
    ): CapabilityDecisionTrace = CapabilityDecisionTrace(
        traceId = traceId,
        decisionKind = "capability_selection",
        operation = "media_transcode",
        policyName = "free_first_v0",
        preferLocalFree = true,
        allowCloudFreeFallback = true,
        allowPaidProviders = allowPaidProviders,
        alternatives = alternatives,
        chosenProviderId = chosenProviderId,
        chosenNodeId = chosenNodeId,
        startedAt = 1L,
        completedAt = 2L
    )

    private fun alternative(
        providerId: String,
        nodeId: String?,
        costClass: String = "LOCAL_FREE",
        available: Boolean = true,
        requiresNetwork: Boolean = false
    ): CapabilityAlternativeTrace = CapabilityAlternativeTrace(
        providerId = providerId,
        displayName = providerId,
        nodeId = nodeId,
        providerType = "LOCAL_TOOL",
        costClass = costClass,
        available = available,
        eligible = available,
        requiresNetwork = requiresNetwork,
        operations = listOf("media_transcode")
    )
}
