package com.jadegenesis.mobile.night

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.state.SharedGenesisStateStore
import com.jadegenesis.mobile.state.SharedStateEvent
import org.json.JSONArray
import org.json.JSONObject

data class StrategyRegistryEntry(
    val strategyId: String,
    val semanticKey: String,
    val version: Int,
    val status: String,
    val target: String,
    val mode: String,
    val taskKind: String,
    val brainProfile: String,
    val nodeId: String,
    val model: String,
    val preferredNodeId: String,
    val preferredModel: String,
    val comparisonNodeId: String,
    val comparisonModel: String,
    val score: Double,
    val confidence: Double,
    val outcomeConfidence: Double,
    val sampleCount: Int,
    val sourceRevision: Long,
    val candidateId: String,
    val experimentId: String,
    val hypothesisId: String,
    val signalKind: String,
    val sandboxRequired: Boolean,
    val pairedScenariosRequired: Boolean,
    val frozenChampionRequired: Boolean,
    val explicitPromotionApprovalRequired: Boolean,
    val explicitPromotionApproved: Boolean,
    val runtimeApplicationEnabled: Boolean,
    val evaluationPassed: Boolean,
    val evaluationConfidence: Double,
    val evaluationSampleCount: Int,
    val replacesStrategyId: String
)

data class StrategyRegistrySnapshot(
    val runId: String,
    val registryRevision: Long,
    val sourceRevision: Long,
    val generatedAt: Long,
    val reviewedAt: Long,
    val entryCount: Int,
    val candidateCount: Int,
    val validatedCount: Int,
    val activeCount: Int,
    val rejectedCount: Int,
    val entries: List<StrategyRegistryEntry>
)

class StrategyRegistryInbox(context: Context) {
    private val store = SharedGenesisStateStore(context.applicationContext)

    fun latest(): StrategyRegistrySnapshot? = parseLatest(store.cachedEvents())

    companion object {
        private const val EVENT_KIND = "vps_strategy_registry_snapshot"
        private const val SCHEMA_VERSION = 1
        private const val STRATEGY_KIND = "ROUTING_STRATEGY"
        private const val STRATEGY_TARGET = "routing.profile_model_preference"
        private const val TASK_KIND = "brain_chat"

        private val allowedStatuses = setOf(
            "CANDIDATE",
            "VALIDATED",
            "ACTIVE",
            "SUPERSEDED",
            "ROLLED_BACK",
            "REJECTED"
        )
        private val allowedModes = setOf("prefer", "deprioritize")

        fun parseLatest(events: List<SharedStateEvent>): StrategyRegistrySnapshot? {
            val ordered = events
                .asSequence()
                .filter { it.kind == EVENT_KIND }
                .sortedWith(
                    compareByDescending<SharedStateEvent> { it.serverRevision }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.eventId }
                )
                .toList()

            for (event in ordered) {
                val parsed = runCatching { parse(event.payload) }.getOrNull()
                if (parsed != null) return parsed
            }
            return null
        }

        private fun parse(raw: String): StrategyRegistrySnapshot {
            require(raw.length <= SafetyPolicy.MAX_SHARED_STATE_EVENT_PAYLOAD_CHARS) {
                "Snapshot Strategy Registry trop volumineux."
            }
            val json = JSONObject(raw)
            require(json.optInt("schema_version", -1) == SCHEMA_VERSION) {
                "Schéma Strategy Registry incompatible."
            }

            // Nouveau contrat 0.1.18 : les garde-fous doivent être présents et
            // explicitement à false. Un champ manquant est rejeté fail-closed.
            requireSafetyFalse(json, "automatic_promotion")
            requireSafetyFalse(json, "automatic_runtime_application")
            requireSafetyFalse(json, "runtime_application_performed")
            requireSafetyFalse(json, "production_code_rewrite")
            requireSafetyFalse(json, "shell_execution")
            requireSafetyFalse(json, "raw_conversation_text_used")
            requireSafetyFalse(json, "user_feedback_promoted_to_external_fact")

            val entries = parseEntries(json.optJSONArray("entries"))
            return StrategyRegistrySnapshot(
                runId = clean(json.optString("run_id"), 160),
                registryRevision = json.optLong("registry_revision", 0L).coerceAtLeast(0L),
                sourceRevision = json.optLong("source_revision", 0L).coerceAtLeast(0L),
                generatedAt = json.optLong("generated_at", 0L).coerceAtLeast(0L),
                reviewedAt = json.optLong("reviewed_at", 0L).coerceAtLeast(0L),
                entryCount = boundedCount(json, "entry_count"),
                candidateCount = boundedCount(json, "candidate_count"),
                validatedCount = boundedCount(json, "validated_count"),
                activeCount = boundedCount(json, "active_count"),
                rejectedCount = boundedCount(json, "rejected_count"),
                entries = entries
            )
        }

        private fun parseEntries(array: JSONArray?): List<StrategyRegistryEntry> {
            if (array == null) return emptyList()
            require(array.length() <= SafetyPolicy.MAX_STRATEGY_REGISTRY_SNAPSHOT_ENTRIES) {
                "Strategy Registry dépasse la limite d'entrées du snapshot."
            }
            return buildList {
                for (index in 0 until array.length()) {
                    add(parseEntry(array.getJSONObject(index)))
                }
            }
        }

        private fun parseEntry(item: JSONObject): StrategyRegistryEntry {
            require(item.optString("kind") == STRATEGY_KIND) {
                "Type de stratégie non autorisé."
            }
            require(item.optString("target") == STRATEGY_TARGET) {
                "Cible de stratégie non autorisée."
            }
            val mode = required(item, "mode", 40)
            require(mode in allowedModes) { "Mode de stratégie non autorisé." }
            require(item.optString("task_kind") == TASK_KIND) {
                "Type de tâche de stratégie non autorisé."
            }
            val status = required(item, "status", 40)
            require(status in allowedStatuses) { "Statut de stratégie non autorisé." }
            val brainProfile = required(item, "brain_profile", 100).lowercase()

            requireSafetyTrue(item, "sandbox_required")
            requireSafetyTrue(item, "paired_scenarios_required")
            requireSafetyTrue(item, "frozen_champion_required")
            requireSafetyTrue(item, "explicit_promotion_approval_required")
            requireSafetyFalse(item, "automatic_activation")
            requireSafetyFalse(item, "automatic_promotion")
            requireSafetyFalse(item, "production_code_change")
            requireSafetyFalse(item, "runtime_application_enabled")

            val evaluationPassed = item.optBoolean("evaluation_passed", false)
            val evaluationConfidence = finiteDouble(item, "evaluation_confidence")
                .coerceIn(0.0, 1.0)
            val evaluationSampleCount = item.optInt("evaluation_sample_count", 0)
                .coerceIn(0, 1_000)
            val explicitApproved = item.optBoolean(
                "explicit_promotion_approved",
                false
            )

            if (status == "VALIDATED" || status == "ACTIVE") {
                require(evaluationPassed) {
                    "Une stratégie validée n'a pas réussi son évaluation sandbox."
                }
                require(
                    evaluationSampleCount >= SafetyPolicy.MIN_STRATEGY_PROMOTION_SAMPLES
                ) {
                    "Une stratégie validée manque d'échantillons sandbox."
                }
                require(
                    evaluationConfidence >= SafetyPolicy.MIN_STRATEGY_PROMOTION_CONFIDENCE
                ) {
                    "Une stratégie validée manque de confiance sandbox."
                }
            }
            if (status == "ACTIVE") {
                require(item.has("explicit_promotion_approved") && explicitApproved) {
                    "Une stratégie ACTIVE n'a pas d'approbation explicite."
                }
            }

            val nodeId = clean(item.optString("node_id"), 120)
            val model = clean(item.optString("model"), 160)
            val preferredNodeId = clean(item.optString("preferred_node_id"), 120)
            val preferredModel = clean(item.optString("preferred_model"), 160)
            if (mode == "prefer") {
                require(preferredNodeId.isNotBlank() || preferredModel.isNotBlank()) {
                    "Une stratégie prefer n'a aucun backend préféré."
                }
            } else {
                require(nodeId.isNotBlank() || model.isNotBlank()) {
                    "Une stratégie deprioritize n'a aucun backend ciblé."
                }
            }

            return StrategyRegistryEntry(
                strategyId = required(item, "strategy_id", 160),
                semanticKey = required(item, "semantic_key", 160),
                version = item.optInt("version", 1).coerceIn(1, 10_000),
                status = status,
                target = STRATEGY_TARGET,
                mode = mode,
                taskKind = TASK_KIND,
                brainProfile = brainProfile,
                nodeId = nodeId,
                model = model,
                preferredNodeId = preferredNodeId,
                preferredModel = preferredModel,
                comparisonNodeId = clean(item.optString("comparison_node_id"), 120),
                comparisonModel = clean(item.optString("comparison_model"), 160),
                score = finiteDouble(item, "score").coerceIn(-2.0, 2.0),
                confidence = finiteDouble(item, "confidence").coerceIn(0.0, 1.0),
                outcomeConfidence = finiteDouble(item, "outcome_confidence")
                    .coerceIn(0.0, 1.0),
                sampleCount = item.optInt("sample_count", 0).coerceIn(0, 1_000),
                sourceRevision = item.optLong("source_revision", 0L).coerceAtLeast(0L),
                candidateId = required(item, "candidate_id", 160),
                experimentId = required(item, "experiment_id", 160),
                hypothesisId = required(item, "hypothesis_id", 160),
                signalKind = required(item, "signal_kind", 120),
                sandboxRequired = true,
                pairedScenariosRequired = true,
                frozenChampionRequired = true,
                explicitPromotionApprovalRequired = true,
                explicitPromotionApproved = explicitApproved,
                runtimeApplicationEnabled = false,
                evaluationPassed = evaluationPassed,
                evaluationConfidence = evaluationConfidence,
                evaluationSampleCount = evaluationSampleCount,
                replacesStrategyId = clean(item.optString("replaces_strategy_id"), 160)
            )
        }

        private fun requireSafetyTrue(json: JSONObject, key: String) {
            require(json.has(key) && json.optBoolean(key, false)) {
                "Garde-fou Strategy Registry absent ou faux: $key"
            }
        }

        private fun requireSafetyFalse(json: JSONObject, key: String) {
            require(json.has(key) && !json.optBoolean(key, true)) {
                "Garde-fou Strategy Registry absent ou actif: $key"
            }
        }

        private fun boundedCount(json: JSONObject, key: String): Int =
            json.optInt(key, 0).coerceIn(0, SafetyPolicy.MAX_STRATEGY_REGISTRY_ENTRIES)

        private fun required(
            json: JSONObject,
            key: String,
            maxChars: Int = SafetyPolicy.MAX_STRATEGY_REGISTRY_TEXT_CHARS
        ): String {
            val value = clean(json.optString(key), maxChars)
            require(value.isNotBlank()) { "Champ Strategy Registry manquant: $key" }
            return value
        }

        private fun clean(
            value: String,
            maxChars: Int = SafetyPolicy.MAX_STRATEGY_REGISTRY_TEXT_CHARS
        ): String = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(maxChars)

        private fun finiteDouble(json: JSONObject, key: String): Double {
            val value = json.optDouble(key, 0.0)
            return if (value.isFinite()) value else 0.0
        }
    }
}
