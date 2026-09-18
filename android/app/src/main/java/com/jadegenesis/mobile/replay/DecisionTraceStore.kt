package com.jadegenesis.mobile.replay

import android.content.Context
import org.json.JSONArray

/**
 * Bounded local store for N1 routing decisions.
 *
 * This is instrumentation only. It does not change routing decisions, execute
 * alternatives, or promote policies.
 */
class DecisionTraceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun record(trace: DecisionTrace) {
        val current = loadArray()
        val next = JSONArray()
        next.put(DecisionTraceCodec.toJson(trace))

        val keep = minOf(current.length(), MAX_ITEMS - 1)
        for (index in 0 until keep) {
            next.put(current.getJSONObject(index))
        }

        prefs.edit()
            .putString(KEY_TRACES, next.toString())
            .apply()
    }

    @Synchronized
    fun recent(limit: Int = 32): List<DecisionTrace> {
        val safeLimit = limit.coerceIn(0, MAX_ITEMS)
        if (safeLimit == 0) {
            return emptyList()
        }

        val array = loadArray()
        return buildList {
            val count = minOf(array.length(), safeLimit)
            for (index in 0 until count) {
                runCatching {
                    DecisionTraceCodec.fromJson(
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
            "jade_genesis_decision_trace_v0"
        private const val KEY_TRACES = "decision_traces_v1"
        const val MAX_ITEMS = 200
    }
}
