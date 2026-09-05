package com.jadegenesis.mobile.cognitive

import android.content.Context
import com.jadegenesis.mobile.model.CognitivePhase
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import org.json.JSONArray
import org.json.JSONObject

class CognitiveLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_cognitive_ledger",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_EVENTS = "events_v1"
        private const val MAX_EVENTS = 100
    }

    @Synchronized
    fun record(event: CognitiveTraceEvent) {
        val current = recent(MAX_EVENTS - 1).toMutableList()
        current.add(0, event)
        save(current.take(MAX_EVENTS))
    }

    @Synchronized
    fun recent(limit: Int = 40): List<CognitiveTraceEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), limit.coerceAtLeast(1))) {
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
        events.forEach { event ->
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
