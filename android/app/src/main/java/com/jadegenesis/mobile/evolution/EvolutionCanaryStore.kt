package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload
import org.json.JSONArray
import org.json.JSONObject

enum class EvolutionCanaryStatus {
    RUNNING,
    STOPPED,
    COMPLETE
}

data class EvolutionCanarySession(
    val candidateId: String,
    val scenarioSetId: String,
    val championConfigJson: String,
    val challengerConfigJson: String,
    val baseline: List<RuntimeEvalObservation> = emptyList(),
    val challenger: List<RuntimeEvalObservation> = emptyList(),
    val status: EvolutionCanaryStatus = EvolutionCanaryStatus.RUNNING,
    val reason: String = "",
    val startedAt: Long,
    val updatedAt: Long
) {
    val pairCount: Int get() = minOf(baseline.size, challenger.size)
}

class EvolutionCanaryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun load(): EvolutionCanarySession? {
        val primary = prefs.getString(KEY_SESSION, null)
        if (!primary.isNullOrBlank()) {
            decode(primary)?.let { return it }
        }
        val backup = prefs.getString(KEY_SESSION_BACKUP, null)
        if (!backup.isNullOrBlank()) {
            decode(backup)?.let { recovered ->
                prefs.edit().putString(KEY_SESSION, encode(recovered)).apply()
                return recovered
            }
        }
        return null
    }

    @Synchronized
    fun save(session: EvolutionCanarySession) {
        require(session.baseline.size <= SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES)
        require(session.challenger.size <= SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES)
        val encoded = encode(session)
        val previous = prefs.getString(KEY_SESSION, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_SESSION_BACKUP, previous)
        }
        editor.putString(KEY_SESSION, encoded).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_SESSION_BACKUP).apply()
    }

    private fun encode(session: EvolutionCanarySession): String =
        JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("candidate_id", session.candidateId)
            put("scenario_set_id", session.scenarioSetId)
            put("champion_config_json", session.championConfigJson)
            put("challenger_config_json", session.challengerConfigJson)
            put("baseline", encodeObservations(session.baseline))
            put("challenger", encodeObservations(session.challenger))
            put("status", session.status.name)
            put("reason", session.reason.take(500))
            put("started_at", session.startedAt)
            put("updated_at", session.updatedAt)
        }.toString()

    private fun decode(raw: String): EvolutionCanarySession? = runCatching {
        val json = JSONObject(raw)
        require(json.optInt("schema_version", 0) == SCHEMA_VERSION)
        val baseline = decodeObservations(json.optJSONArray("baseline"))
        val challenger = decodeObservations(json.optJSONArray("challenger"))
        require(baseline.size == challenger.size)
        EvolutionCanarySession(
            candidateId = json.getString("candidate_id"),
            scenarioSetId = json.getString("scenario_set_id"),
            championConfigJson = json.getString("champion_config_json"),
            challengerConfigJson = json.getString("challenger_config_json"),
            baseline = baseline,
            challenger = challenger,
            status = parseStatus(json.optString("status")),
            reason = json.optString("reason").take(500),
            startedAt = json.optLong("started_at", 0L).coerceAtLeast(0L),
            updatedAt = json.optLong("updated_at", 0L).coerceAtLeast(0L)
        )
    }.getOrNull()

    private fun encodeObservations(
        observations: List<RuntimeEvalObservation>
    ): JSONArray = JSONArray().apply {
        observations.forEach { observation ->
            put(
                JSONObject().apply {
                    put("observation_id", observation.observationId)
                    put("task_id", observation.taskId)
                    put("task_kind", observation.taskKind)
                    put("workload", observation.workload.name)
                    put("node_id", observation.nodeId)
                    put("node_name", observation.nodeName)
                    put("node_kind", observation.nodeKind.name)
                    put("model", observation.model)
                    put("brain_profile", observation.brainProfile)
                    put("success", observation.success)
                    put("duration_ms", observation.durationMs)
                    put("output_chars", observation.outputChars)
                    put("tokens_per_second", observation.tokensPerSecond)
                    put("fallback_used", observation.fallbackUsed)
                    put("error", observation.error ?: "")
                    put("created_at", observation.createdAt)
                }
            )
        }
    }

    private fun decodeObservations(array: JSONArray?): List<RuntimeEvalObservation> {
        if (array == null) return emptyList()
        val limit = minOf(array.length(), SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES)
        return buildList {
            for (index in 0 until limit) {
                val item = array.getJSONObject(index)
                add(
                    RuntimeEvalObservation(
                        observationId = item.optString("observation_id"),
                        taskId = item.optString("task_id"),
                        taskKind = item.optString("task_kind"),
                        workload = parseWorkload(item.optString("workload")),
                        nodeId = item.optString("node_id"),
                        nodeName = item.optString("node_name"),
                        nodeKind = parseNodeKind(item.optString("node_kind")),
                        model = item.optString("model"),
                        brainProfile = item.optString("brain_profile"),
                        success = item.optBoolean("success", false),
                        durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0L),
                        outputChars = item.optInt("output_chars", 0).coerceAtLeast(0),
                        tokensPerSecond = item.optDouble("tokens_per_second", 0.0)
                            .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                        fallbackUsed = item.optBoolean("fallback_used", false),
                        error = item.optString("error").takeIf { it.isNotBlank() },
                        createdAt = item.optLong("created_at", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    private fun parseStatus(value: String): EvolutionCanaryStatus =
        runCatching { EvolutionCanaryStatus.valueOf(value.uppercase()) }
            .getOrDefault(EvolutionCanaryStatus.STOPPED)

    private fun parseWorkload(value: String): TaskWorkload =
        runCatching { TaskWorkload.valueOf(value.uppercase()) }
            .getOrDefault(TaskWorkload.MEDIUM)

    private fun parseNodeKind(value: String): NodeKind =
        runCatching { NodeKind.valueOf(value.uppercase()) }
            .getOrDefault(NodeKind.UNKNOWN)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val PREFS_NAME = "jade_evolution_canary_v0"
        private const val KEY_SESSION = "active_session_v1"
        private const val KEY_SESSION_BACKUP = "active_session_v1_backup"
    }
}
