package com.jadegenesis.mobile

import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.replay.DecisionAlternativeTrace
import com.jadegenesis.mobile.replay.DecisionTrace
import com.jadegenesis.mobile.replay.DecisionTraceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTraceTest {

    @Test
    fun decisionTraceRoundTripsWithoutUserPayloadOrOutput() {
        val trace = sampleTrace()
        val json = DecisionTraceCodec.toJson(trace)

        assertFalse(json.has("payload"))
        assertFalse(json.has("output"))
        assertFalse(json.toString().contains("SECRET_USER_CONTENT"))

        val restored = DecisionTraceCodec.fromJson(json)

        assertEquals(trace.traceId, restored.traceId)
        assertEquals(trace.taskId, restored.taskId)
        assertEquals(trace.configId, restored.configId)
        assertEquals(trace.configRevision, restored.configRevision)
        assertEquals(trace.resourceMode, restored.resourceMode)
        assertEquals(trace.chosenNodeId, restored.chosenNodeId)
        assertEquals(2, restored.alternatives.size)
        assertEquals(87.5, restored.alternatives[0].score!!, 0.0001)
        assertNull(restored.alternatives[1].score)
    }

    @Test
    fun tracePreservesEligibleAndIneligibleAlternatives() {
        val restored = DecisionTraceCodec.fromJson(
            DecisionTraceCodec.toJson(sampleTrace())
        )

        assertTrue(restored.alternatives[0].eligible)
        assertFalse(restored.alternatives[1].eligible)
        assertEquals(
            listOf("task_execution_v3", "text_analysis"),
            restored.alternatives[0].capabilities
        )
    }

    private fun sampleTrace(): DecisionTrace = DecisionTrace(
        traceId = "decision-task-1",
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
            DecisionAlternativeTrace(
                nodeId = "pc-1",
                nodeName = "PC",
                nodeKind = "PC",
                nodeStatus = "ONLINE",
                eligible = true,
                score = 87.5,
                cpuCores = 12,
                ramAvailableGb = 12.0,
                activeTaskCount = 0,
                brainReady = true,
                brainModel = "qwen3:4b",
                capabilities = listOf(
                    "task_execution_v3",
                    "text_analysis"
                )
            ),
            DecisionAlternativeTrace(
                nodeId = "pixel-1",
                nodeName = "Pixel",
                nodeKind = "PHONE",
                nodeStatus = "LOCAL",
                eligible = false,
                score = null,
                cpuCores = 8,
                ramAvailableGb = 4.0,
                activeTaskCount = 0,
                brainReady = false,
                brainModel = "",
                capabilities = listOf("task_execution_v3")
            )
        ),
        chosenNodeId = "pc-1",
        chosenNodeName = "PC",
        routeReason = "PC ranked first",
        outcomeSuccess = true,
        outcomeNodeId = "pc-1",
        outcomeNodeName = "PC",
        outcomeDurationMs = 420L,
        fallbackUsed = false,
        startedAt = 10L,
        completedAt = 430L
    )
}
