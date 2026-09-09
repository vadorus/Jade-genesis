package com.jadegenesis.mobile.cognitive

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.model.CognitivePhase
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import org.json.JSONArray
import org.json.JSONObject

class CognitiveLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_cognitive_ledger",
        Context.MODE_PRIVATE
    )
    private var cachedEvents: MutableList<CognitiveTraceEvent>? = null

    companion object {
        private const val KEY_EVENTS = "events_v1"
    }

    @Synchronized
    fun record(event: CognitiveTraceEvent) {
        val current = eventsUnsafe()
        current.add(0, event)
        while (current.size > maxEvents()) {
            current.removeAt(current.lastIndex)
        }
        save(current)
        RuntimeEvalRuntime.currentOrNull()?.recordCognitiveCycle(event)
    }

    @Synchronized
    fun recent(limit: Int = 40): List<CognitiveTraceEvent> {
        val safeLimit = limit.coerceIn(1, maxEvents())
        return eventsUnsafe().take(safeLimit)
    }

    private fun maxEvents(): Int =
        JadeConfigRuntime.current()
            .validated()
            .retention
            .cognitiveEventsMaxItems
            .coerceIn(1, SafetyPolicy.MAX_COGNITIVE_EVENTS)

    private fun eventsUnsafe(): MutableList<CognitiveTraceEvent> {
        cachedEvents?.let { return it }
        val loaded = load().toMutableList()
        cachedEvents = loaded
        return loaded
    }

    private fun load(): List<CognitiveTraceEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), maxEvents())) {
                    val json = array.getJSONObject(index)
                    add(
                        CognitiveTraceEvent(
                            id = json.getString("id"),
                            phase = runCatching {
                                CognitivePhase.valueOf(json.getString("phase"))
                            }.getOrDefault(CognitivePhase.OBSERVE),
                            summary = json.optString("summary"),
                            backendId = json.optString("backend_id")
                                .takeIf { it.isNotBlank() },
                            nodeId = json.optString("node_id")
                                .takeIf { it.isNotBlank() },
                            durationMs = json.optLong("duration_ms"),
                            success = json.optBoolean("success", true),
                            createdAt = json.optLong("created_at")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(events: List<CognitiveTraceEvent>) {
        val array = JSONArray()
        events.take(maxEvents()).forEach { event ->
            array.put(
                JSONObject().apply {
                    put("id", event.id)
                    put("phase", event.phase.name)
                    put("summary", event.summary)
                    put("backend_id", event.backendId ?: "")
                    put("node_id", event.nodeId ?: "")
                    put("duration_ms", event.durationMs)
                    put("success", event.success)
                    put("created_at", event.createdAt)
                }
            )
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }
}
