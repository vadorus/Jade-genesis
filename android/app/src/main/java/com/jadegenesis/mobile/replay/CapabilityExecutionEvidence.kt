package com.jadegenesis.mobile.replay

import com.jadegenesis.mobile.model.DistributedTaskResult
import org.json.JSONObject
import java.util.UUID

data class CapabilityExecutionEvidence(
    val evidenceId: String,
    val taskId: String,
    val taskKind: String,
    val providerId: String,
    val operation: String,
    val nodeId: String,
    val nodeName: String,
    val success: Boolean,
    val verificationPassed: Boolean,
    /**
     * End-to-end duration observed by the Android caller.
     *
     * Kept as durationMs for storage/source compatibility. Capability
     * comparison decisions must use nodeExecutionMs instead.
     */
    val durationMs: Long,
    val nodeExecutionMs: Long,
    val outputBytes: Long,
    val outputSha256: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fallbackUsed: Boolean,
    val startedAt: Long,
    val completedAt: Long
)

object CapabilityExecutionEvidenceFactory {
    private const val TASK_KIND = "ffmpeg_transcode_probe_v1"
    private const val PROVIDER_ID = "ffmpeg-local"
    private const val OPERATION = "media_transcode_probe"

    fun fromFfmpegProbe(
        result: DistributedTaskResult
    ): CapabilityExecutionEvidence {
        require(result.taskKind == TASK_KIND) {
            "Unsupported capability evidence task kind."
        }

        if (!result.success) {
            return CapabilityExecutionEvidence(
                evidenceId = "evidence-${UUID.randomUUID()}",
                taskId = result.taskId,
                taskKind = result.taskKind,
                providerId = PROVIDER_ID,
                operation = OPERATION,
                nodeId = result.executedNodeId,
                nodeName = result.executedNodeName,
                success = false,
                verificationPassed = false,
                durationMs = result.durationMs,
                nodeExecutionMs = 0L,
                outputBytes = 0L,
                outputSha256 = "",
                codec = "",
                width = 0,
                height = 0,
                fallbackUsed = result.fallbackUsed,
                startedAt = result.startedAt,
                completedAt = result.completedAt
            )
        }

        val json = JSONObject(result.output)
        require(json.optString("provider_id") == PROVIDER_ID) {
            "FFmpeg evidence provider mismatch."
        }
        require(json.optString("operation") == OPERATION) {
            "FFmpeg evidence operation mismatch."
        }
        require(json.optBoolean("success", false)) {
            "FFmpeg evidence reports unsuccessful execution."
        }
        require(json.optBoolean("verification_passed", false)) {
            "FFmpeg evidence was not machine-verified."
        }
        require(!json.optBoolean("user_file_access", true)) {
            "FFmpeg evidence unexpectedly allowed user-file access."
        }
        require(!json.optBoolean("arbitrary_arguments_allowed", true)) {
            "FFmpeg evidence unexpectedly allowed arbitrary arguments."
        }
        require(!json.optBoolean("shell_execution", true)) {
            "FFmpeg evidence unexpectedly used shell execution."
        }
        require(!json.optBoolean("network_input_allowed", true)) {
            "FFmpeg evidence unexpectedly allowed network input."
        }
        require(!json.optBoolean("persistent_output", true)) {
            "FFmpeg evidence unexpectedly persisted output."
        }

        val metrics = json.optJSONObject("metrics")
            ?: error("FFmpeg evidence metrics are missing.")
        val nodeExecutionMs = metrics.optLong("duration_ms", -1L)
        require(nodeExecutionMs >= 0L) {
            "FFmpeg evidence node execution duration is invalid."
        }

        val output = json.getJSONObject("output")
        val codec = output.optString("codec").trim().lowercase()
        val width = output.optInt("width", 0)
        val height = output.optInt("height", 0)
        val outputBytes = output.optLong("bytes", 0L)
        val durationSeconds = output.optDouble("duration_seconds", -1.0)
        val sha = output.optString("sha256")

        require(codec == "mpeg4") {
            "FFmpeg evidence codec is invalid."
        }
        require(width == 160 && height == 90) {
            "FFmpeg evidence dimensions are invalid."
        }
        require(outputBytes > 0L) {
            "FFmpeg evidence output size is invalid."
        }
        require(durationSeconds in 0.25..1.0) {
            "FFmpeg evidence media duration is invalid."
        }
        require(Regex("^[0-9a-f]{64}$").matches(sha)) {
            "FFmpeg evidence SHA-256 is invalid."
        }

        return CapabilityExecutionEvidence(
            evidenceId = "evidence-${UUID.randomUUID()}",
            taskId = result.taskId,
            taskKind = result.taskKind,
            providerId = PROVIDER_ID,
            operation = OPERATION,
            nodeId = result.executedNodeId,
            nodeName = result.executedNodeName,
            success = true,
            verificationPassed = true,
            durationMs = result.durationMs,
            nodeExecutionMs = nodeExecutionMs,
            outputBytes = outputBytes,
            outputSha256 = sha,
            codec = codec,
            width = width,
            height = height,
            fallbackUsed = result.fallbackUsed,
            startedAt = result.startedAt,
            completedAt = result.completedAt
        )
    }
}

object CapabilityExecutionEvidenceCodec {
    fun toJson(evidence: CapabilityExecutionEvidence): JSONObject =
        JSONObject().apply {
            put("schema_version", 2)
            put("evidence_id", evidence.evidenceId)
            put("task_id", evidence.taskId)
            put("task_kind", evidence.taskKind)
            put("provider_id", evidence.providerId)
            put("operation", evidence.operation)
            put("node_id", evidence.nodeId)
            put("node_name", evidence.nodeName)
            put("success", evidence.success)
            put("verification_passed", evidence.verificationPassed)
            put("duration_ms", evidence.durationMs)
            put("end_to_end_ms", evidence.durationMs)
            put("node_execution_ms", evidence.nodeExecutionMs)
            put("output_bytes", evidence.outputBytes)
            put("output_sha256", evidence.outputSha256)
            put("codec", evidence.codec)
            put("width", evidence.width)
            put("height", evidence.height)
            put("fallback_used", evidence.fallbackUsed)
            put("started_at", evidence.startedAt)
            put("completed_at", evidence.completedAt)
        }

    fun fromJson(json: JSONObject): CapabilityExecutionEvidence {
        val schemaVersion = json.optInt("schema_version", 0)
        require(schemaVersion == 1 || schemaVersion == 2) {
            "Unsupported CapabilityExecutionEvidence schema."
        }

        val endToEndMs = if (schemaVersion >= 2) {
            json.optLong(
                "end_to_end_ms",
                json.optLong("duration_ms", 0L)
            )
        } else {
            json.optLong("duration_ms", 0L)
        }
        val nodeExecutionMs = if (schemaVersion >= 2) {
            json.optLong("node_execution_ms", -1L)
        } else {
            // V1 evidence did not separate network/transport time.
            // Keep it readable, but mark node-local timing as unavailable.
            -1L
        }

        return CapabilityExecutionEvidence(
            evidenceId = json.optString("evidence_id"),
            taskId = json.optString("task_id"),
            taskKind = json.optString("task_kind"),
            providerId = json.optString("provider_id"),
            operation = json.optString("operation"),
            nodeId = json.optString("node_id"),
            nodeName = json.optString("node_name"),
            success = json.optBoolean("success", false),
            verificationPassed =
                json.optBoolean("verification_passed", false),
            durationMs = endToEndMs,
            nodeExecutionMs = nodeExecutionMs,
            outputBytes = json.optLong("output_bytes", 0L),
            outputSha256 = json.optString("output_sha256"),
            codec = json.optString("codec"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            fallbackUsed = json.optBoolean("fallback_used", false),
            startedAt = json.optLong("started_at", 0L),
            completedAt = json.optLong("completed_at", 0L)
        )
    }
}
