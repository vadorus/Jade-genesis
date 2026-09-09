package com.jadegenesis.mobile.eval

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RuntimeEvalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun recordExecution(
        request: DistributedTaskRequest,
        node: GenesisNode,
        success: Boolean,
        durationMs: Long,
        output: String = "",
        error: String? = null,
        fallbackUsed: Boolean = false,
        createdAt: Long = System.currentTimeMillis()
    ): RuntimeEvalObservation? {
        if (!shouldEvaluate(request.taskKind)) return null
        val outputJson = runCatching { JSONObject(output) }.getOrNull()
        val model = outputJson?.optString("model")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: node.brainLoadedModel.ifBlank { node.brainModel }
        val tokensPerSecond = finiteDouble(
            outputJson,
            "tokens_per_second",
            node.brainTokensPerSecond
        ).coerceAtLeast(0.0)

        val observation = RuntimeEvalObservation(
            observationId = "eval-${UUID.randomUUID()}",
            taskId = request.taskId,
            taskKind = request.taskKind,
            workload = request.workload,
            nodeId = node.nodeId,
            nodeName = node.name,
            nodeKind = node.kind,
            model = model,
            success = success,
            durationMs = durationMs.coerceAtLeast(0L),
            outputChars = output.length.coerceAtLeast(0),
            tokensPerSecond = tokensPerSecond,
            fallbackUsed = fallbackUsed,
            error = error?.trim()?.take(240)?.takeIf { it.isNotBlank() },
            createdAt = createdAt.coerceAtLeast(0L)
        )
        record(observation)
        return observation
    }

    fun record(observation: RuntimeEvalObservation) = synchronized(lock) {
        validate(observation)
        val current = loadUnsafe().toMutableList()
        current.removeAll { it.observationId == observation.observationId }
        current.add(0, observation)
        saveUnsafe(current.take(maxItems()))
    }

    fun recent(limit: Int = 100): List<RuntimeEvalObservation> = synchronized(lock) {
        val safeLimit = limit.coerceIn(0, maxItems())
        if (safeLimit == 0) return@synchronized emptyList()
        loadUnsafe().take(safeLimit)
    }

    fun stats(
        nodeId: String,
        taskKind: String,
        model: String? = null
    ): RuntimeEvalStats? = synchronized(lock) {
        val config = JadeConfigRuntime.current().validated()
        val window = config.evaluation.reportWindowItems
            .coerceIn(1, SafetyPolicy.MAX_RUNTIME_EVAL_REPORT_WINDOW)
        RuntimeEvalEngine.aggregate(
            observations = loadUnsafe().take(window),
            nodeId = nodeId,
            taskKind = taskKind,
            model = model
        )
    }

    fun report(): RuntimeEvalReport = synchronized(lock) {
        val config = JadeConfigRuntime.current().validated()
        val window = config.evaluation.reportWindowItems
            .coerceIn(1, SafetyPolicy.MAX_RUNTIME_EVAL_REPORT_WINDOW)
        RuntimeEvalEngine.report(
            observations = loadUnsafe().take(window),
            tuning = config.evaluation
        )
    }

    fun count(): Int = synchronized(lock) { loadUnsafe().size }

    private fun shouldEvaluate(taskKind: String): Boolean =
        taskKind.isNotBlank() && taskKind != "shared_state_sync"

    private fun maxItems(): Int =
        JadeConfigRuntime.current()
            .validated()
            .retention
            .runtimeEvalMaxItems
            .coerceIn(1, SafetyPolicy.MAX_RUNTIME_EVAL_OBSERVATIONS)

    private fun loadUnsafe(): List<RuntimeEvalObservation> {
        val primary = prefs.getString(KEY_OBSERVATIONS, null)
        if (!primary.isNullOrBlank()) {
            decode(primary)?.let { return it }
        }

        val backup = prefs.getString(KEY_OBSERVATIONS_BACKUP, null)
        if (!backup.isNullOrBlank()) {
            decode(backup)?.let { recovered ->
                prefs.edit()
                    .putString(KEY_OBSERVATIONS, encode(recovered))
                    .apply()
                return recovered
            }
        }
        return emptyList()
    }

    private fun saveUnsafe(observations: List<RuntimeEvalObservation>) {
        val encoded = encode(observations)
        val previous = prefs.getString(KEY_OBSERVATIONS, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_OBSERVATIONS_BACKUP, previous)
        }
        editor.putString(KEY_OBSERVATIONS, encoded).apply()
    }

    private fun encode(observations: List<RuntimeEvalObservation>): String =
        JSONArray().apply {
            observations.forEach { observation ->
                put(
                    JSONObject().apply {
                        put("schema_version", RuntimeEvalEngine.SCHEMA_VERSION)
                        put("observation_id", observation.observationId)
                        put("task_id", observation.taskId)
                        put("task_kind", observation.taskKind)
                        put("workload", observation.workload.name)
                        put("node_id", observation.nodeId)
                        put("node_name", observation.nodeName)
                        put("node_kind", observation.nodeKind.name)
                        put("model", observation.model)
                        put("success", observation.success)
                        put("duration_ms", observation.durationMs)
                        put("output_chars", observation.outputChars)
                        put("tokens_per_second", observation.tokensPerSecond)
                        put("fallback_used", observation.fallbackUsed)
                        put("error", observation.error ?: "")
                        put("created_at", observation.createdAt)
                    }
                )
            }
        }.toString()

    private fun decode(raw: String): List<RuntimeEvalObservation>? =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    if (
                        json.optInt("schema_version", RuntimeEvalEngine.SCHEMA_VERSION) !=
                        RuntimeEvalEngine.SCHEMA_VERSION
                    ) {
                        continue
                    }
                    val observation = RuntimeEvalObservation(
                        observationId = json.optString("observation_id"),
                        taskId = json.optString("task_id"),
                        taskKind = json.optString("task_kind"),
                        workload = parseWorkload(json.optString("workload")),
                        nodeId = json.optString("node_id"),
                        nodeName = json.optString("node_name"),
                        nodeKind = parseNodeKind(json.optString("node_kind")),
                        model = json.optString("model"),
                        success = json.optBoolean("success", false),
                        durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L),
                        outputChars = json.optInt("output_chars", 0).coerceAtLeast(0),
                        tokensPerSecond = finiteDouble(
                            json,
                            "tokens_per_second",
                            0.0
                        ).coerceAtLeast(0.0),
                        fallbackUsed = json.optBoolean("fallback_used", false),
                        error = json.optString("error").takeIf { it.isNotBlank() },
                        createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L)
                    )
                    runCatching { validate(observation) }
                        .onSuccess { add(observation) }
                }
            }
        }.getOrNull()

    private fun validate(observation: RuntimeEvalObservation) {
        require(observation.observationId.isNotBlank())
        require(observation.taskId.isNotBlank())
        require(observation.taskKind.isNotBlank())
        require(observation.nodeId.isNotBlank())
        require(observation.nodeName.isNotBlank())
        require(observation.durationMs >= 0L)
        require(observation.outputChars >= 0)
        require(
            observation.tokensPerSecond.isFinite() &&
                observation.tokensPerSecond >= 0.0
        )
        require(observation.createdAt >= 0L)
    }

    private fun parseWorkload(value: String): TaskWorkload =
        runCatching { TaskWorkload.valueOf(value.uppercase()) }
            .getOrDefault(TaskWorkload.MEDIUM)

    private fun parseNodeKind(value: String): NodeKind =
        runCatching { NodeKind.valueOf(value.uppercase()) }
            .getOrDefault(NodeKind.UNKNOWN)

    private fun finiteDouble(
        json: JSONObject?,
        key: String,
        fallback: Double
    ): Double {
        if (json == null) return fallback
        val value = json.optDouble(key, fallback)
        return if (value.isFinite()) value else fallback
    }

    companion object {
        private const val PREFS_NAME = "jade_runtime_eval"
        private const val KEY_OBSERVATIONS = "observations_v1"
        private const val KEY_OBSERVATIONS_BACKUP = "observations_v1_backup"
    }
}
