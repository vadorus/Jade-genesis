package com.jadegenesis.mobile.replay

import android.content.Context
import org.json.JSONArray

class CapabilityExecutionEvidenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun record(evidence: CapabilityExecutionEvidence) {
        val current = loadArray()
        val next = JSONArray()
        next.put(CapabilityExecutionEvidenceCodec.toJson(evidence))

        val keep = minOf(current.length(), MAX_ITEMS - 1)
        for (index in 0 until keep) {
            next.put(current.getJSONObject(index))
        }

        prefs.edit()
            .putString(KEY_EVIDENCE, next.toString())
            .apply()
    }

    @Synchronized
    fun recent(limit: Int = 32): List<CapabilityExecutionEvidence> {
        val safeLimit = limit.coerceIn(0, MAX_ITEMS)
        if (safeLimit == 0) return emptyList()

        val array = loadArray()
        return buildList {
            val count = minOf(array.length(), safeLimit)
            for (index in 0 until count) {
                runCatching {
                    CapabilityExecutionEvidenceCodec.fromJson(
                        array.getJSONObject(index)
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun loadArray(): JSONArray {
        val raw = prefs.getString(KEY_EVIDENCE, null)
            ?: return JSONArray()

        return runCatching {
            JSONArray(raw)
        }.getOrDefault(JSONArray())
    }

    companion object {
        private const val PREFS_NAME =
            "jade_genesis_capability_execution_evidence_v0"
        private const val KEY_EVIDENCE =
            "capability_execution_evidence_v1"
        const val MAX_ITEMS = 200
    }
}
