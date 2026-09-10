package com.jadegenesis.mobile.cognitive

import com.jadegenesis.mobile.brain.BrainRouter
import com.jadegenesis.mobile.brain.CognitiveBrainPolicy
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.eval.RuntimeOutcomeKind
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.CognitivePhase
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import com.jadegenesis.mobile.model.DiagnosticLevel
import org.json.JSONObject
import java.util.UUID

class CognitiveCore(
    private val brainRouter: BrainRouter,
    private val ledger: CognitiveLedger,
    private val logger: DiagnosticLogger
) {
    private data class Review(
        val verdict: String,
        val note: String,
        val confidence: Double
    )

    suspend fun think(context: BrainContext): BrainResult {
        val executionId = "cog-${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val conversationLearning = ConversationLearningRuntime.currentOrNull()
        val learningUpdate = if (context.operation == "answer" && conversationLearning != null) {
            runCatching {
                conversationLearning.beginUserMessage(context.userInput)
            }.onFailure { error ->
                logger.log(
                    DiagnosticLevel.WARN,
                    "conversation_learning_unavailable",
                    "Conversation Learning ignoré pour ce tour afin de préserver un état illisible ou incompatible.",
                    mapOf("error" to (error.message ?: error::class.java.simpleName))
                )
            }.getOrNull()
        } else {
            null
        }

        recordRuntimeOutcomeQuality(learningUpdate)

        val conversationMemories = if (learningUpdate != null) {
            runCatching {
                conversationLearning
                    ?.contextMemories(SafetyPolicy.MAX_CONVERSATION_LEARNING_CONTEXT_ITEMS)
                    .orEmpty()
            }.onFailure { error ->
                logger.log(
                    DiagnosticLevel.WARN,
                    "conversation_learning_context_failed",
                    "Le contexte conversationnel local n'a pas été injecté ; les mémoires principales restent intactes.",
                    mapOf("error" to (error.message ?: error::class.java.simpleName))
                )
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val workingContext = if (conversationMemories.isEmpty()) {
            context
        } else {
            context.copy(
                memories = (context.memories + conversationMemories)
                    .distinctBy { it.id }
            )
        }

        record(
            executionId,
            CognitivePhase.OBSERVE,
            buildString {
                append("Contexte observé : ${workingContext.selfModel.knownNodes.size} nœud(s), mode ${workingContext.selfModel.resourceBudget.mode}.")
                if (conversationMemories.isNotEmpty()) {
                    append(" ${conversationMemories.size} élément(s) d'expérience conversationnelle locale ajouté(s) sans évincer la mémoire principale.")
                }
            }
        )

        val answerPlan = CognitiveBrainPolicy.plan(
            operation = "answer",
            userInput = workingContext.userInput
        )
        val verify = shouldVerify(workingContext.userInput)
        record(
            executionId,
            CognitivePhase.PLAN,
            buildString {
                append("Profil ${answerPlan.profile.name} sélectionné : ${answerPlan.reason}")
                if (verify) {
                    append(" Une passe CRITIC suivra la première réponse.")
                } else {
                    append(" Réponse directe sans seconde passe.")
                }
            }
        )

        val executionStarted = System.nanoTime()
        val first = try {
            brainRouter.think(
                workingContext.copy(
                    operation = "answer",
                    draftResponse = null,
                    reviewNote = null
                )
            )
        } catch (error: Exception) {
            if (learningUpdate != null) {
                conversationLearning?.cancelPending()
            }
            throw error
        }
        val executionDuration = elapsedMs(executionStarted)
        record(
            executionId,
            CognitivePhase.EXECUTE,
            "Réponse ${answerPlan.profile.name} produite par ${first.backendDisplayName.ifBlank { first.backendId.ifBlank { "backend inconnu" } }}${first.model.takeIf { it.isNotBlank() }?.let { " · modèle $it" } ?: ""}.",
            backendId = first.backendId.takeIf { it.isNotBlank() },
            nodeId = first.nodeId.takeIf { it.isNotBlank() },
            durationMs = executionDuration,
            success = first.text.isNotBlank()
        )

        if (
            first.toolName != null ||
            !verify ||
            first.backendId.contains("prototype", ignoreCase = true)
        ) {
            recordConversationLearning(executionId, learningUpdate)
            completeConversationLearning(
                store = conversationLearning,
                update = learningUpdate,
                input = context.userInput,
                profile = answerPlan.profile.name,
                result = first
            )
            recordComplete(executionId, startedAt, first)
            return first
        }

        val verificationStarted = System.nanoTime()
        val verification = runCatching {
            brainRouter.think(
                workingContext.copy(
                    operation = "verify",
                    draftResponse = first.text,
                    reviewNote = null
                )
            )
        }

        val verified = verification.getOrNull()
        if (verified == null || verified.backendId.contains("prototype", ignoreCase = true)) {
            record(
                executionId,
                CognitivePhase.VERIFY,
                "Vérification CRITIC indisponible ; la réponse initiale est conservée.",
                durationMs = elapsedMs(verificationStarted),
                success = false
            )
            recordConversationLearning(executionId, learningUpdate)
            completeConversationLearning(
                store = conversationLearning,
                update = learningUpdate,
                input = context.userInput,
                profile = answerPlan.profile.name,
                result = first
            )
            recordComplete(executionId, startedAt, first)
            return first
        }

        val review = parseReview(verified.text)
        record(
            executionId,
            CognitivePhase.VERIFY,
            "CRITIC verdict=${review.verdict}, confiance=${"%.2f".format(review.confidence)}. ${review.note.take(180)}",
            backendId = verified.backendId,
            nodeId = verified.nodeId.takeIf { it.isNotBlank() },
            durationMs = elapsedMs(verificationStarted),
            success = true
        )

        var finalResult = first
        if (review.verdict == "revise") {
            val revisionStarted = System.nanoTime()
            val revised = runCatching {
                brainRouter.think(
                    workingContext.copy(
                        operation = "revise",
                        draftResponse = first.text,
                        reviewNote = review.note
                    )
                )
            }.getOrNull()

            if (
                revised != null &&
                revised.text.isNotBlank() &&
                !revised.backendId.contains("prototype", ignoreCase = true)
            ) {
                finalResult = revised
                record(
                    executionId,
                    CognitivePhase.REVISE,
                    "Une révision REASONING a été produite après contrôle de la première réponse.",
                    backendId = revised.backendId,
                    nodeId = revised.nodeId.takeIf { it.isNotBlank() },
                    durationMs = elapsedMs(revisionStarted),
                    success = true
                )
            } else {
                record(
                    executionId,
                    CognitivePhase.REVISE,
                    "Révision REASONING non disponible ; conservation de la première réponse.",
                    durationMs = elapsedMs(revisionStarted),
                    success = false
                )
            }
        }

        record(
            executionId,
            CognitivePhase.LEARN,
            "L'issue de l'exécution est enregistrée comme expérience opérationnelle, sans modifier automatiquement le code ni les poids d'un modèle."
        )
        recordConversationLearning(executionId, learningUpdate)
        completeConversationLearning(
            store = conversationLearning,
            update = learningUpdate,
            input = context.userInput,
            profile = answerPlan.profile.name,
            result = finalResult
        )
        recordComplete(executionId, startedAt, finalResult)
        return finalResult
    }

    private fun recordRuntimeOutcomeQuality(update: ConversationLearningUpdate?) {
        val feedback = update?.feedbackKind ?: return
        val feedbackId = update.feedbackOutcomeId.trim()
        val observationId = update.feedbackTargetObservationId.trim()
        if (feedbackId.isBlank() || observationId.isBlank()) return

        val runtimeKind = when (feedback) {
            ConversationFeedbackKind.POSITIVE -> RuntimeOutcomeKind.POSITIVE
            ConversationFeedbackKind.NEGATIVE -> RuntimeOutcomeKind.NEGATIVE
            ConversationFeedbackKind.CORRECTION -> RuntimeOutcomeKind.CORRECTION
        }
        runCatching {
            RuntimeEvalRuntime.currentOrNull()?.recordOutcomeFeedback(
                feedbackId = feedbackId,
                targetObservationId = observationId,
                kind = runtimeKind,
                confidence = update.feedbackConfidence
            )
        }.onSuccess { recorded ->
            if (recorded != null) {
                logger.log(
                    DiagnosticLevel.INFO,
                    "runtime_outcome_recorded",
                    "Outcome utilisateur rattaché à l'exécution exacte de la réponse précédente.",
                    mapOf(
                        "kind" to recorded.kind.name,
                        "node_id" to recorded.nodeId,
                        "brain_profile" to recorded.brainProfile,
                        "model" to recorded.model,
                        "target_observation_id" to recorded.targetObservationId
                    )
                )
            }
        }.onFailure { error ->
            logger.log(
                DiagnosticLevel.WARN,
                "runtime_outcome_ignored",
                "Le retour conversationnel reste mémorisé, mais Runtime Eval n'a pas pu le rattacher sans risque.",
                mapOf("error" to (error.message ?: error::class.java.simpleName))
            )
        }
    }

    private fun completeConversationLearning(
        store: ConversationLearningStore?,
        update: ConversationLearningUpdate?,
        input: String,
        profile: String,
        result: BrainResult
    ) {
        if (store == null || update == null) return
        runCatching {
            store.completeTurn(
                userInput = input,
                answer = result.text,
                profile = result.brainProfile.ifBlank { profile },
                backendId = result.backendId,
                nodeId = result.nodeId,
                model = result.model,
                runtimeEvalObservationId = result.runtimeEvalObservationId,
                fallbackUsed = result.fallbackUsed
            )
        }.onFailure { error ->
            logger.log(
                DiagnosticLevel.WARN,
                "conversation_learning_complete_failed",
                "Le tour conversationnel n'a pas été persisté ; aucune donnée existante n'a été remplacée par un état vide.",
                mapOf("error" to (error.message ?: error::class.java.simpleName))
            )
        }
    }

    private fun recordConversationLearning(
        executionId: String,
        update: ConversationLearningUpdate?
    ) {
        if (update == null) return
        val feedback = update.feedbackKind
        val milestones = update.crossedTopicMilestones
        if (feedback == null && milestones.isEmpty()) return

        record(
            executionId,
            CognitivePhase.LEARN,
            buildString {
                if (feedback != null) {
                    append(
                        "Conversation Learning a enregistré un retour ${feedback.name} " +
                            "(confiance ${"%.2f".format(update.feedbackConfidence)})."
                    )
                    if (update.feedbackOnly) {
                        append(" Ce message a été traité comme feedback du tour précédent et n'a pas créé un nouveau sujet conversationnel.")
                    }
                    if (update.feedbackTargetObservationId.isNotBlank()) {
                        append(" Le signal possède un lien exact vers l'observation Runtime Eval de la réponse ciblée.")
                    }
                }
                if (milestones.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Sujets devenus récurrents : ")
                    append(
                        milestones.entries.joinToString { (topic, count) ->
                            "$topic ($count)"
                        }
                    )
                    append(".")
                }
                append(" Ces signaux restent des expériences utilisateur, pas des faits externes automatiquement vérifiés.")
            }
        )
    }

    private fun shouldVerify(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.length >= 240) return true
        val deliberateTerms = listOf(
            "analyse",
            "compare",
            "pourquoi",
            "architecture",
            "solution",
            "optimise",
            "diagnostic",
            "risque",
            "vérifie",
            "verifie",
            "plan",
            "code",
            "apprend",
            "évolu",
            "evolu"
        )
        return deliberateTerms.count { it in normalized } >= 1
    }

    private fun parseReview(raw: String): Review {
        val jsonText = raw.substringAfter('{', "")
            .takeIf { it.isNotBlank() }
            ?.let { "{" + it.substringBeforeLast('}', it) + "}" }

        if (jsonText != null) {
            runCatching {
                val json = JSONObject(jsonText)
                val verdict = json.optString("verdict", "caution")
                    .lowercase()
                    .takeIf { it in setOf("ok", "caution", "revise") }
                    ?: "caution"
                return Review(
                    verdict = verdict,
                    note = json.optString("note", "Contrôle effectué.").take(500),
                    confidence = json.optDouble("confidence", 0.5)
                        .coerceIn(0.0, 1.0)
                )
            }
        }

        return Review(
            verdict = "caution",
            note = "Le contrôle n'a pas renvoyé le format structuré attendu.",
            confidence = 0.35
        )
    }

    private fun recordComplete(
        executionId: String,
        startedAt: Long,
        result: BrainResult
    ) {
        val duration = System.currentTimeMillis() - startedAt
        record(
            executionId,
            CognitivePhase.COMPLETE,
            "Cycle cognitif terminé en ${duration} ms${if (result.fallbackUsed) " avec fallback" else ""}.",
            backendId = result.backendId.takeIf { it.isNotBlank() },
            nodeId = result.nodeId.takeIf { it.isNotBlank() },
            durationMs = duration,
            success = result.text.isNotBlank()
        )
    }

    private fun record(
        executionId: String,
        phase: CognitivePhase,
        summary: String,
        backendId: String? = null,
        nodeId: String? = null,
        durationMs: Long = 0L,
        success: Boolean = true
    ) {
        val event = CognitiveTraceEvent(
            id = "$executionId-${phase.name.lowercase()}-${System.nanoTime()}",
            phase = phase,
            summary = summary,
            backendId = backendId,
            nodeId = nodeId,
            durationMs = durationMs,
            success = success,
            createdAt = System.currentTimeMillis()
        )
        ledger.record(event)
        logger.log(
            level = if (success) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            event = "cognitive_${phase.name.lowercase()}",
            message = summary,
            metadata = mapOf(
                "execution_id" to executionId,
                "backend_id" to backendId,
                "node_id" to nodeId,
                "duration_ms" to durationMs
            )
        )
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
