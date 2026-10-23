package com.jadegenesis.mobile.memory

import com.jadegenesis.mobile.model.MemorySnapshot

class CorrectionStore(private val dao: CorrectionDao) {
    suspend fun visible(): List<UserCorrection> {
        val active = dao.activeFacts().map { it.id }.toSet()
        return dao.rows().filter { it.state == "PENDING" || it.id in active }
    }

    suspend fun active(): List<UserCorrection> {
        val rows = visible()
        val blocked = rows.filter { it.state == "PENDING" }.flatMap { it.targets.split('|') }.toSet()
        return rows.filter { it.state == "ACTIVE" && it.id !in blocked }
    }

    suspend fun relevant(query: String): List<MemorySnapshot> = active()
        .map { it to CorrectionPolicy.score(it.question, it.fact, query) }
        // An identical original question scores 1000; otherwise require two distinct terms.
        .filter { it.second >= 2 }
        .sortedWith(compareByDescending<Pair<UserCorrection, Int>> { it.second }
            .thenByDescending { it.first.createdAt }.thenBy { it.first.id })
        .take(5).map { (row, _) ->
            MemorySnapshot(id = row.id, type = "FACT", content = row.fact,
                source = CorrectionPolicy.SOURCE, confidence = 1.0, createdAt = row.createdAt,
                originNode = "")
        }
}
