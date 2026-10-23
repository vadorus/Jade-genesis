package com.jadegenesis.mobile.memory

import com.jadegenesis.mobile.model.MemorySnapshot
import org.json.JSONArray
import org.json.JSONObject

object CorrectionContextPayload {
    fun encode(corrections: List<MemorySnapshot>): JSONArray {
        require(corrections.size <= 5)
        return JSONArray().apply {
            corrections.forEach { fact ->
                require(fact.source == CorrectionPolicy.SOURCE && fact.confidence == 1.0 &&
                    fact.content.isNotBlank() && fact.content.length <= CorrectionPolicy.MAX_FACT)
                put(JSONObject().apply {
                    put("source", CorrectionPolicy.SOURCE)
                    put("confidence", 1.0)
                    put("content", fact.content)
                })
            }
        }
    }
}
