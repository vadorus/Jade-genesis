package com.jadegenesis.mobile

import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.replay.BranchHarvestPlanner
import com.jadegenesis.mobile.replay.DecisionAlternativeTrace
import com.jadegenesis.mobile.replay.DecisionTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchHarvestPlannerTest {

    private val planner = BranchHarvestPlanner()

    @Test
    fun plannerExcludesRecordedPrimaryNode() {
        val plan = planner.plan(
            trace = trace(),
            maxBranches = 3
        )

        assertTrue(
            plan.candidates.none {
                it.nodeId == "primary"
            }
        )
    }

    @Test
    fun plannerSelectsBestEligibleAlternativesInOrder() {
        val plan = planner.plan(
            trace = trace(),
            maxBranches = 2
        )

        assertEquals(
            listOf("alt-fast", "alt-second"),
            plan.candidates.map { it.nodeId }
        )
    }

    @Test
    fun plannerNeverSelectsIneligibleAlternative() {
        val plan = planner.plan(
            trace = trace(),
            maxBranches = 3
        )

        assertTrue(
            plan.candidates.none {
                it.nodeId == "blocked"
            }
        )
    }

    @Test
    fun plannerClampsRequestedBranchCount() {
        val plan = planner.plan(
            trace = trace(),
            maxBranches = 99
        )

        assertTrue(
            plan.candidates.size <=
                BranchHarvestPlanner.MAX_BRANCHES
        )
    }

    @Test
    fun zeroBranchPlanIsEmpty() {
        val plan = planner.plan(
            trace = trace(),
            maxBranches = 0
        )

        assertTrue(plan.candidates.isEmpty())
    }

    private fun trace(): DecisionTrace = DecisionTrace(
        traceId = "decision-branch-1",
        decisionKind = "task_routing",
        taskId = "task-1",
        taskKind = "text_analysis",
        requiredCapability = "text_analysis",
        workload = TaskWorkload.LIGHT,
        configId = "builtin-1",
        configRevision = 1L,
        resourceMode = ResourceMode.BALANCED,
        preferRemoteCompute = false,
        maxParallelTasks = 2,
        alternatives = listOf(
            alternative(
                id = "primary",
                score = 100.0,
                eligible = true
            ),
            alternative(
                id = "alt-second",
                score = 60.0,
                eligible = true
            ),
            alternative(
                id = "blocked",
                score = 999.0,
                eligible = false
            ),
            alternative(
                id = "alt-fast",
                score = 75.0,
                eligible = true
            )
        ),
        chosenNodeId = "primary",
        chosenNodeName = "primary",
        routeReason = "test",
        outcomeSuccess = true,
        outcomeNodeId = "primary",
        outcomeNodeName = "primary",
        outcomeDurationMs = 100L,
        fallbackUsed = false,
        startedAt = 1L,
        completedAt = 101L
    )

    private fun alternative(
        id: String,
        score: Double?,
        eligible: Boolean
    ): DecisionAlternativeTrace =
        DecisionAlternativeTrace(
            nodeId = id,
            nodeName = id,
            nodeKind = "PC",
            nodeStatus = "ONLINE",
            eligible = eligible,
            score = score,
            cpuCores = 8,
            ramAvailableGb = 8.0,
            activeTaskCount = 0,
            brainReady = true,
            brainModel = "test-model",
            capabilities = listOf(
                "task_execution_v3",
                "text_analysis"
            )
        )
}
