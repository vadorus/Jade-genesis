package com.jadegenesis.mobile.replay

import android.content.Context
import org.json.JSONArray

/**
 * Bounded local store for capability-selection decisions.
 *
 * Instrumentation only: it does not execute providers or change policy.
 */
class CapabilityDecisionTraceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun record(trace: CapabilityDecisionTrace) {
        val current = loadArray()
        val next = JSONArray()
        next.put(CapabilityDecisionTraceCodec.toJson(trace))

        val keep = minOf(current.length(), MAX_ITEMS - 1)
        for (index in 0 until keep) {
            next.put(current.getJSONObject(index))
        }

        prefs.edit()
            .putString(KEY_TRACES, next.toString())
            .apply()
    }

    @Synchronized
    fun recent(limit: Int = 32): List<CapabilityDecisionTrace> {
        val safeLimit = limit.coerceIn(0, MAX_ITEMS)
        if (safeLimit == 0) {
            return emptyList()
        }

        val array = loadArray()
        return buildList {
            val count = minOf(array.length(), safeLimit)
            for (index in 0 until count) {
                runCatching {
                    CapabilityDecisionTraceCodec.fromJson(
                        array.getJSONObject(index)
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun loadArray(): JSONArray {
        val raw = prefs.getString(KEY_TRACES, null)
            ?: return JSONArray()

        return runCatching {
            JSONArray(raw)
        }.getOrDefault(JSONArray())
    }

    companion object {
        private const val PREFS_NAME =
            "jade_genesis_capability_decision_trace_v0"
        private const val KEY_TRACES =
            "capability_decision_traces_v1"
        const val MAX_ITEMS = 200
    }
}
