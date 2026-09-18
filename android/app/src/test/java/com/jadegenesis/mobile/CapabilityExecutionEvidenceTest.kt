package com.jadegenesis.mobile

import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.model.TaskStatus
import com.jadegenesis.mobile.replay.CapabilityExecutionEvidenceCodec
import com.jadegenesis.mobile.replay.CapabilityExecutionEvidenceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityExecutionEvidenceTest {

    @Test
    fun successfulProbeBecomesContentFreeVerifiedEvidence() {
        val evidence = CapabilityExecutionEvidenceFactory.fromFfmpegProbe(
            result(success = true)
        )
        val json = CapabilityExecutionEvidenceCodec.toJson(evidence)

        assertTrue(evidence.success)
        assertTrue(evidence.verificationPassed)
        assertEquals("ffmpeg-local", evidence.providerId)
        assertEquals("media_transcode_probe", evidence.operation)
        assertEquals("mpeg4", evidence.codec)
        assertEquals(160, evidence.width)
        assertEquals(90, evidence.height)
        assertEquals(1234L, evidence.outputBytes)
        assertFalse(json.has("payload"))
        assertFalse(json.has("output"))
        assertFalse(json.toString().contains("SECRET_MEDIA_PATH"))

        val restored = CapabilityExecutionEvidenceCodec.fromJson(json)
        assertEquals(evidence.taskId, restored.taskId)
        assertEquals(evidence.outputSha256, restored.outputSha256)
        assertEquals(evidence.durationMs, restored.durationMs)
        assertEquals(41L, evidence.nodeExecutionMs)
        assertEquals(87L, evidence.durationMs)
        assertEquals(evidence.nodeExecutionMs, restored.nodeExecutionMs)
    }

    @Test
    fun schemaOneEvidenceRemainsReadableWithoutInventingNewTiming() {
        val legacy = org.json.JSONObject().apply {
            put("schema_version", 1)
            put("evidence_id", "legacy")
            put("task_id", "task-legacy")
            put("task_kind", "ffmpeg_transcode_probe_v1")
            put("provider_id", "ffmpeg-local")
            put("operation", "media_transcode_probe")
            put("node_id", "pc-a")
            put("node_name", "PC A")
            put("success", true)
            put("verification_passed", true)
            put("duration_ms", 123L)
            put("output_bytes", 1L)
            put("output_sha256", "a".repeat(64))
            put("codec", "mpeg4")
            put("width", 160)
            put("height", 90)
            put("fallback_used", false)
            put("started_at", 1L)
            put("completed_at", 124L)
        }

        val restored = CapabilityExecutionEvidenceCodec.fromJson(legacy)
        assertEquals(123L, restored.durationMs)
        assertEquals(123L, restored.nodeExecutionMs)
    }

    @Test
    fun failedProbeStillProducesFailureEvidenceWithoutToolOutput() {
        val evidence = CapabilityExecutionEvidenceFactory.fromFfmpegProbe(
            result(success = false)
        )

        assertFalse(evidence.success)
        assertFalse(evidence.verificationPassed)
        assertEquals(0L, evidence.outputBytes)
        assertEquals("", evidence.outputSha256)
    }

    private fun result(success: Boolean): DistributedTaskResult {
        val output = if (success) {
            """
            {
              "provider_id":"ffmpeg-local",
              "operation":"media_transcode_probe",
              "success":true,
              "verification_passed":true,
              "user_file_access":false,
              "arbitrary_arguments_allowed":false,
              "shell_execution":false,
              "network_input_allowed":false,
              "persistent_output":false,
              "metrics":{"duration_ms":41},
              "output":{
                "codec":"mpeg4",
                "width":160,
                "height":90,
                "bytes":1234,
                "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              }
            }
            """.trimIndent()
        } else {
            ""
        }

        return DistributedTaskResult(
            taskId = "task-ffmpeg-1",
            taskKind = "ffmpeg_transcode_probe_v1",
            requestedNodeId = "pc-a",
            requestedNodeName = "PC A",
            executedNodeId = "pc-a",
            executedNodeName = "PC A",
            executionLocation = TaskExecutionLocation.REMOTE,
            status = if (success) TaskStatus.COMPLETED else TaskStatus.FAILED,
            success = success,
            output = output,
            durationMs = 87L,
            fallbackUsed = false,
            fallbackReason = null,
            routeReason = "test",
            attempts = emptyList(),
            startedAt = 10L,
            completedAt = 97L
        )
    }
}
