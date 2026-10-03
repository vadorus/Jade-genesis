package com.jadegenesis.mobile.replay

import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import org.json.JSONArray
import org.json.JSONObject

data class DecisionAlternativeTrace(
    val nodeId: String,
    val nodeName: String,
    val nodeKind: String,
    val nodeStatus: String,
    val eligible: Boolean,
    val score: Double?,
    val cpuCores: Int,
    val ramAvailableGb: Double,
    val storageFreeGb: Double = 0.0,
    val activeTaskCount: Int,
    val brainReady: Boolean,
    val brainModel: String,
    val capabilities: List<String>,
    val historyAttempts: Int = 0,
    val historySuccesses: Int = 0,
    val averageDurationMs: Double? = null
)

data class DecisionTrace(
    val traceId: String,
    val decisionKind: String,
    val taskId: String,
    val taskKind: String,
    val requiredCapability: String,
    val workload: TaskWorkload,
    val configId: String,
    val configRevision: Long,
    val resourceMode: ResourceMode,
    val preferRemoteCompute: Boolean,
    val maxParallelTasks: Int,
    val alternatives: List<DecisionAlternativeTrace>,
    val chosenNodeId: String?,
    val chosenNodeName: String?,
    val routeReason: String,
    val outcomeSuccess: Boolean,
    val outcomeNodeId: String,
    val outcomeNodeName: String,
    val outcomeDurationMs: Long,
    val fallbackUsed: Boolean,
    val startedAt: Long,
    val completedAt: Long,
    val scoringContextVersion: Int = 0
)

/**
 * Stable JSON codec used by DecisionTraceStore and Replay Lab.
 *
 * Intentionally excludes task payloads and task outputs. The replay instrument
 * records the decision context, not user content. scoring_context_version=1
 * means the trace contains every input currently consumed by TaskRouter's
 * ranking formula and can therefore be rescored exactly under another
 * RoutingTuning without executing the task again.
 */
object DecisionTraceCodec {

    fun toJson(trace: DecisionTrace): JSONObject = JSONObject().apply {
        put("schema_version", 1)
        put("trace_id", trace.traceId)
        put("decision_kind", trace.decisionKind)
        put("task_id", trace.taskId)
        put("task_kind", trace.taskKind)
        put("required_capability", trace.requiredCapability)
        put("workload", trace.workload.name)
        put("config_id", trace.configId)
        put("config_revision", trace.configRevision)
        put("resource_mode", trace.resourceMode.name)
        put("prefer_remote_compute", trace.preferRemoteCompute)
        put("max_parallel_tasks", trace.maxParallelTasks)
        put("scoring_context_version", trace.scoringContextVersion)
        put("chosen_node_id", trace.chosenNodeId ?: "")
        put("chosen_node_name", trace.chosenNodeName ?: "")
        put("route_reason", trace.routeReason)
        put("outcome_success", trace.outcomeSuccess)
        put("outcome_node_id", trace.outcomeNodeId)
        put("outcome_node_name", trace.outcomeNodeName)
        put("outcome_duration_ms", trace.outcomeDurationMs)
        put("fallback_used", trace.fallbackUsed)
        put("started_at", trace.startedAt)
        put("completed_at", trace.completedAt)

        put(
            "alternatives",
            JSONArray().apply {
                trace.alternatives.forEach { alternative ->
                    put(
                        JSONObject().apply {
                            put("node_id", alternative.nodeId)
                            put("node_name", alternative.nodeName)
                            put("node_kind", alternative.nodeKind)
                            put("node_status", alternative.nodeStatus)
                            put("eligible", alternative.eligible)
                            if (alternative.score == null) {
                                put("score", JSONObject.NULL)
                            } else {
                                put("score", alternative.score)
                            }
                            put("cpu_cores", alternative.cpuCores)
                            put("ram_available_gb", alternative.ramAvailableGb)
                            put("storage_free_gb", alternative.storageFreeGb)
                            put("active_task_count", alternative.activeTaskCount)
                            put("brain_ready", alternative.brainReady)
                            put("brain_model", alternative.brainModel)
                            put("history_attempts", alternative.historyAttempts)
                            put("history_successes", alternative.historySuccesses)
                            if (alternative.averageDurationMs == null) {
                                put("average_duration_ms", JSONObject.NULL)
                            } else {
                                put("average_duration_ms", alternative.averageDurationMs)
                            }
                            put(
                                "capabilities",
                                JSONArray(alternative.capabilities)
                            )
                        }
                    )
                }
            }
        )
    }

    fun fromJson(json: JSONObject): DecisionTrace {
        require(json.optInt("schema_version", 0) == 1) {
            "Unsupported DecisionTrace schema."
        }

        val alternativesJson =
            json.optJSONArray("alternatives") ?: JSONArray()
        val alternatives = buildList {
            for (index in 0 until alternativesJson.length()) {
                val item = alternativesJson.getJSONObject(index)
                val capabilityJson =
                    item.optJSONArray("capabilities") ?: JSONArray()
                val capabilities = buildList {
                    for (capIndex in 0 until capabilityJson.length()) {
                        add(capabilityJson.optString(capIndex))
                    }
                }

                add(
                    DecisionAlternativeTrace(
                        nodeId = item.optString("node_id"),
                        nodeName = item.optString("node_name"),
                        nodeKind = item.optString("node_kind"),
                        nodeStatus = item.optString("node_status"),
                        eligible = item.optBoolean("eligible", false),
                        score = if (item.isNull("score")) {
                            null
                        } else {
                            item.optDouble("score")
                                .takeIf { it.isFinite() }
                        },
                        cpuCores = item.optInt("cpu_cores", 0),
                        ramAvailableGb =
                            item.optDouble("ram_available_gb", 0.0),
                        storageFreeGb =
                            item.optDouble("storage_free_gb", 0.0),
                        activeTaskCount =
                            item.optInt("active_task_count", 0),
                        brainReady = item.optBoolean("brain_ready", false),
                        brainModel = item.optString("brain_model"),
                        capabilities = capabilities,
                        historyAttempts =
                            item.optInt("history_attempts", 0),
                        historySuccesses =
                            item.optInt("history_successes", 0),
                        averageDurationMs = if (item.isNull("average_duration_ms")) {
                            null
                        } else {
                            item.optDouble("average_duration_ms")
                                .takeIf { it.isFinite() && it >= 0.0 }
                        }
                    )
                )
            }
        }

        return DecisionTrace(
            traceId = json.optString("trace_id"),
            decisionKind = json.optString("decision_kind"),
            taskId = json.optString("task_id"),
            taskKind = json.optString("task_kind"),
            requiredCapability =
                json.optString("required_capability"),
            workload = parseWorkload(json.optString("workload")),
            configId = json.optString("config_id"),
            configRevision = json.optLong("config_revision", 0L),
            resourceMode =
                parseResourceMode(json.optString("resource_mode")),
            preferRemoteCompute =
                json.optBoolean("prefer_remote_compute", false),
            maxParallelTasks =
                json.optInt("max_parallel_tasks", 1),
            alternatives = alternatives,
            chosenNodeId = json.optString("chosen_node_id")
                .takeIf { it.isNotBlank() },
            chosenNodeName = json.optString("chosen_node_name")
                .takeIf { it.isNotBlank() },
            routeReason = json.optString("route_reason"),
            outcomeSuccess =
                json.optBoolean("outcome_success", false),
            outcomeNodeId = json.optString("outcome_node_id"),
            outcomeNodeName = json.optString("outcome_node_name"),
            outcomeDurationMs =
                json.optLong("outcome_duration_ms", 0L),
            fallbackUsed = json.optBoolean("fallback_used", false),
            startedAt = json.optLong("started_at", 0L),
            completedAt = json.optLong("completed_at", 0L),
            scoringContextVersion =
                json.optInt("scoring_context_version", 0)
        )
    }

    private fun parseWorkload(value: String): TaskWorkload =
        runCatching {
            TaskWorkload.valueOf(value.uppercase())
        }.getOrDefault(TaskWorkload.LIGHT)

    private fun parseResourceMode(value: String): ResourceMode =
        runCatching {
            ResourceMode.valueOf(value.uppercase())
        }.getOrDefault(ResourceMode.BALANCED)
}
