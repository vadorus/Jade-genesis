package com.jadegenesis.mobile.task

import android.content.Context
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.TaskAttempt
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.model.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

class TaskLedger(context: Context) {
    private val prefs = context.getSharedPreferences(
        "jade_genesis_task_ledger",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_HISTORY = "task_history_v1"
        private const val MAX_HISTORY = 40
    }

    @Synchronized
    fun record(result: DistributedTaskResult) {
        val current = loadJsonArray()
        val next = JSONArray()
        next.put(toJson(result))

        val keep = minOf(current.length(), MAX_HISTORY - 1)
        for (index in 0 until keep) {
            next.put(current.getJSONObject(index))
        }

        prefs.edit()
            .putString(KEY_HISTORY, next.toString())
            .apply()
    }

    @Synchronized
    fun recent(limit: Int = 20): List<DistributedTaskResult> {
        val safeLimit = limit.coerceIn(0, MAX_HISTORY)
        if (safeLimit == 0) return emptyList()

        val array = loadJsonArray()
        return buildList {
            val count = minOf(array.length(), safeLimit)
            for (index in 0 until count) {
                runCatching {
                    fromJson(array.getJSONObject(index))
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun loadJsonArray(): JSONArray {
        val raw = prefs.getString(KEY_HISTORY, null)
            ?: return JSONArray()

        return runCatching {
            JSONArray(raw)
        }.getOrDefault(JSONArray())
    }

    private fun toJson(result: DistributedTaskResult): JSONObject =
        JSONObject().apply {
            put("task_id", result.taskId)
            put("task_kind", result.taskKind)
            put("requested_node_id", result.requestedNodeId ?: "")
            put("requested_node_name", result.requestedNodeName ?: "")
            put("executed_node_id", result.executedNodeId)
            put("executed_node_name", result.executedNodeName)
            put("execution_location", result.executionLocation.name)
            put("status", result.status.name)
            put("success", result.success)
            put("output", result.output)
            put("duration_ms", result.durationMs)
            put("fallback_used", result.fallbackUsed)
            put("fallback_reason", result.fallbackReason ?: "")
            put("route_reason", result.routeReason)
            put("started_at", result.startedAt)
            put("completed_at", result.completedAt)

            val attempts = JSONArray()
            result.attempts.forEach { attempt ->
                attempts.put(
                    JSONObject().apply {
                        put("node_id", attempt.nodeId)
                        put("node_name", attempt.nodeName)
                        put("execution_location", attempt.executionLocation.name)
                        put("success", attempt.success)
                        put("duration_ms", attempt.durationMs)
                        put("error", attempt.error ?: "")
                    }
                )
            }
            put("attempts", attempts)
        }

    private fun fromJson(json: JSONObject): DistributedTaskResult {
        val attemptsJson = json.optJSONArray("attempts") ?: JSONArray()
        val attempts = buildList {
            for (index in 0 until attemptsJson.length()) {
                val item = attemptsJson.getJSONObject(index)
                add(
                    TaskAttempt(
                        nodeId = item.optString("node_id"),
                        nodeName = item.optString("node_name"),
                        executionLocation = parseLocation(
                            item.optString("execution_location")
                        ),
                        success = item.optBoolean("success", false),
                        durationMs = item.optLong("duration_ms", 0L),
                        error = item.optString("error")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        return DistributedTaskResult(
            taskId = json.optString("task_id"),
            taskKind = json.optString("task_kind"),
            requestedNodeId = json.optString("requested_node_id")
                .takeIf { it.isNotBlank() },
            requestedNodeName = json.optString("requested_node_name")
                .takeIf { it.isNotBlank() },
            executedNodeId = json.optString("executed_node_id"),
            executedNodeName = json.optString("executed_node_name"),
            executionLocation = parseLocation(
                json.optString("execution_location")
            ),
            status = parseStatus(json.optString("status")),
            success = json.optBoolean("success", false),
            output = json.optString("output"),
            durationMs = json.optLong("duration_ms", 0L),
            fallbackUsed = json.optBoolean("fallback_used", false),
            fallbackReason = json.optString("fallback_reason")
                .takeIf { it.isNotBlank() },
            routeReason = json.optString("route_reason"),
            attempts = attempts,
            startedAt = json.optLong("started_at", 0L),
            completedAt = json.optLong("completed_at", 0L)
        )
    }

    private fun parseLocation(value: String): TaskExecutionLocation =
        runCatching {
            TaskExecutionLocation.valueOf(value.uppercase())
        }.getOrDefault(TaskExecutionLocation.LOCAL)

    private fun parseStatus(value: String): TaskStatus =
        runCatching {
            TaskStatus.valueOf(value.uppercase())
        }.getOrDefault(TaskStatus.COMPLETED)
}
