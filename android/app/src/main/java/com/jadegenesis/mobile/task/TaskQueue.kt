package com.jadegenesis.mobile.task

import android.content.Context
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.QueueTaskStatus
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.TaskWorkload
import org.json.JSONArray
import org.json.JSONObject

class TaskQueue(context: Context) {
    private val prefs = context.getSharedPreferences(
        "jade_genesis_task_queue",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_QUEUE = "task_queue_v1"
        private const val MAX_ITEMS = 30
    }

    init {
        recoverInterrupted()
    }

    @Synchronized
    fun enqueue(request: DistributedTaskRequest): QueuedTaskSnapshot {
        val now = System.currentTimeMillis()
        val snapshot = QueuedTaskSnapshot(
            taskId = request.taskId,
            taskKind = request.taskKind,
            workload = request.workload,
            status = QueueTaskStatus.PENDING,
            queuedAt = now,
            updatedAt = now
        )
        upsert(snapshot)
        return snapshot
    }

    @Synchronized
    fun markRunning(
        taskId: String,
        nodeName: String,
        attempts: Int
    ) {
        update(taskId) {
            it.copy(
                status = QueueTaskStatus.RUNNING,
                selectedNodeName = nodeName,
                attempts = attempts,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    fun markCompleted(
        taskId: String,
        nodeName: String,
        attempts: Int
    ) {
        update(taskId) {
            it.copy(
                status = QueueTaskStatus.COMPLETED,
                selectedNodeName = nodeName,
                attempts = attempts,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    fun markFailed(
        taskId: String,
        nodeName: String?,
        attempts: Int,
        error: String
    ) {
        update(taskId) {
            it.copy(
                status = QueueTaskStatus.FAILED,
                selectedNodeName = nodeName ?: it.selectedNodeName,
                attempts = attempts,
                error = error.take(180),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    fun recent(limit: Int = 12): List<QueuedTaskSnapshot> {
        val safeLimit = limit.coerceIn(0, MAX_ITEMS)
        if (safeLimit == 0) return emptyList()

        return load()
            .sortedByDescending { it.updatedAt }
            .take(safeLimit)
    }

    @Synchronized
    fun pendingCount(): Int =
        load().count {
            it.status == QueueTaskStatus.PENDING ||
                it.status == QueueTaskStatus.RUNNING
        }

    private fun recoverInterrupted() {
        val now = System.currentTimeMillis()
        val recovered = load().map { item ->
            if (item.status == QueueTaskStatus.RUNNING) {
                item.copy(
                    status = QueueTaskStatus.FAILED,
                    error = "Exécution interrompue avant confirmation.",
                    updatedAt = now
                )
            } else {
                item
            }
        }
        save(recovered)
    }

    private fun update(
        taskId: String,
        transform: (QueuedTaskSnapshot) -> QueuedTaskSnapshot
    ) {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.taskId == taskId }
        if (index < 0) return
        items[index] = transform(items[index])
        save(items)
    }

    private fun upsert(snapshot: QueuedTaskSnapshot) {
        val items = load().toMutableList()
        items.removeAll { it.taskId == snapshot.taskId }
        items.add(0, snapshot)
        save(items)
    }

    private fun load(): List<QueuedTaskSnapshot> {
        val raw = prefs.getString(KEY_QUEUE, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    add(
                        QueuedTaskSnapshot(
                            taskId = json.optString("task_id"),
                            taskKind = json.optString("task_kind"),
                            workload = parseWorkload(
                                json.optString("workload")
                            ),
                            status = parseStatus(
                                json.optString("status")
                            ),
                            selectedNodeName = json
                                .optString("selected_node_name")
                                .takeIf { it.isNotBlank() },
                            attempts = json.optInt("attempts", 0),
                            error = json
                                .optString("error")
                                .takeIf { it.isNotBlank() },
                            queuedAt = json.optLong("queued_at", 0L),
                            updatedAt = json.optLong("updated_at", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<QueuedTaskSnapshot>) {
        val array = JSONArray()
        items
            .sortedByDescending { it.updatedAt }
            .take(MAX_ITEMS)
            .forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("task_id", item.taskId)
                        put("task_kind", item.taskKind)
                        put("workload", item.workload.name)
                        put("status", item.status.name)
                        put(
                            "selected_node_name",
                            item.selectedNodeName ?: ""
                        )
                        put("attempts", item.attempts)
                        put("error", item.error ?: "")
                        put("queued_at", item.queuedAt)
                        put("updated_at", item.updatedAt)
                    }
                )
            }

        prefs.edit()
            .putString(KEY_QUEUE, array.toString())
            .apply()
    }

    private fun parseWorkload(value: String): TaskWorkload =
        runCatching {
            TaskWorkload.valueOf(value.uppercase())
        }.getOrDefault(TaskWorkload.LIGHT)

    private fun parseStatus(value: String): QueueTaskStatus =
        runCatching {
            QueueTaskStatus.valueOf(value.uppercase())
        }.getOrDefault(QueueTaskStatus.FAILED)
}
