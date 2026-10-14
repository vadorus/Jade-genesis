package com.jadegenesis.mobile.night

import android.content.Context
import com.jadegenesis.mobile.model.MemorySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local review queue only: keeps memory IDs and reason codes, not memory text.
 * No model invocation, external sync, automated correction or promotion.
 */
internal data class NightReviewCandidate(
    val memoryId: String,
    val reason: String,
    val priority: Int
)

internal object NightMemoryReviewPlanner {
    private const val WINDOW_MS = 36L * 60L * 60L * 1_000L
    const val MAX_ITEMS = 8

    fun propose(
        memories: List<MemorySnapshot>,
        now: Long,
        limit: Int = MAX_ITEMS
    ): List<NightReviewCandidate> = memories.asSequence()
        .filter { item ->
            item.id.isNotBlank() &&
                item.source != "USER" &&
                item.verifiedAt == null &&
                item.supersededBy == null &&
                item.createdAt in (now - WINDOW_MS)..now
        }
        .mapNotNull { item ->
            val score = when (item.type) {
                "FAILURE" -> 4
                "HYPOTHESIS" -> 3
                "KNOWLEDGE" -> if (item.confidence.isFinite() && item.confidence < 0.85) 2 else 0
                else -> 0
            }
            if (score == 0) null else NightReviewCandidate(
                memoryId = item.id,
                reason = when (item.type) {
                    "FAILURE" -> "FAILURE_TO_REEXAMINE"
                    "HYPOTHESIS" -> "HYPOTHESIS_TO_VERIFY"
                    else -> "KNOWLEDGE_NEEDS_EVIDENCE"
                },
                priority = score
            ) to item.createdAt
        }
        .distinctBy { it.first.memoryId }
        .sortedWith(
            compareByDescending<Pair<NightReviewCandidate, Long>> { it.first.priority }
                .thenByDescending { it.second }
        )
        .take(limit.coerceIn(0, MAX_ITEMS))
        .map { it.first }
        .toList()
}

internal class NightReviewAgendaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_night_review_agenda_v1", Context.MODE_PRIVATE
    )

    fun save(candidates: List<NightReviewCandidate>, now: Long) {
        val payload = JSONObject().apply {
            put("version", 1)
            put("generated_at", now.coerceAtLeast(0L))
            put("items", JSONArray().apply {
                candidates.take(NightMemoryReviewPlanner.MAX_ITEMS).forEach { item ->
                    put(JSONObject().apply {
                        put("memory_id", item.memoryId.take(120))
                        put("reason", item.reason)
                        put("priority", item.priority)
                    })
                }
            })
        }
        check(prefs.edit().putString("latest", payload.toString()).commit()) {
            "Cannot save local night review agenda"
        }
    }

    fun latest(): List<NightReviewCandidate> = runCatching {
        val data = JSONObject(prefs.getString("latest", "{}") ?: "{}")
        if (data.optInt("version") != 1) return@runCatching emptyList()
        val items = data.optJSONArray("items") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until minOf(items.length(), NightMemoryReviewPlanner.MAX_ITEMS)) {
                val obj = items.optJSONObject(i) ?: continue
                val id = obj.optString("memory_id").take(120)
                val reason = obj.optString("reason").take(80)
                if (id.isNotBlank() && reason.isNotBlank()) {
                    add(NightReviewCandidate(id, reason, obj.optInt("priority", 0)))
                }
            }
        }
    }.getOrDefault(emptyList())
}
