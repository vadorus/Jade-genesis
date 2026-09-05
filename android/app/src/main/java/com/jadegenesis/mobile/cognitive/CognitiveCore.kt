package com.jadegenesis.mobile.cognitive

import com.jadegenesis.mobile.brain.BrainRouter
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
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
        record(
            executionId,
            CognitivePhase.OBSERVE,
            "Contexte observé : ${context.selfModel.knownNodes.size} nœud(s), mode ${context.selfModel.resourceBudget.mode}."
        )

        val verify = shouldVerify(context.userInput)
        record(
            executionId,
            CognitivePhase.PLAN,
            if (verify) {
                "Réponse générative suivie d'une vérification courte et mesurable."
            } else {
                "Réponse directe : vérification supplémentaire non nécessaire pour cette requête."
            }
        )

        val executionStarted = System.nanoTime()
        val first = brainRouter.think(
            context.copy(
                operation = "answer",
                draftResponse = null,
                reviewNote = null
            )
        )
        val executionDuration = elapsedMs(executionStarted)
        record(
            executionId,
            CognitivePhase.EXECUTE,
            "Réponse produite par ${first.backendDisplayName.ifBlank { first.backendId.ifBlank { "backend inconnu" } }}.",
            backendId = first.backendId.takeIf { it.isNotBlank() },
            durationMs = executionDuration,
            success = first.text.isNotBlank()
        )

        if (
            first.toolName != null ||
            !verify ||
            first.backendId.contains("prototype", ignoreCase = true)
        ) {
            recordComplete(executionId, startedAt, first)
            return first
        }

        val verificationStarted = System.nanoTime()
        val verification = runCatching {
            brainRouter.think(
                context.copy(
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
                "Vérification indisponible ; la réponse initiale est conservée.",
                durationMs = elapsedMs(verificationStarted),
                success = false
            )
            recordComplete(executionId, startedAt, first)
            return first
        }

        val review = parseReview(verified.text)
        record(
            executionId,
            CognitivePhase.VERIFY,
            "Verdict=${review.verdict}, confiance=${"%.2f".format(review.confidence)}. ${review.note.take(180)}",
            backendId = verified.backendId,
            durationMs = elapsedMs(verificationStarted),
            success = true
        )

        var finalResult = first
        if (review.verdict == "revise") {
            val revisionStarted = System.nanoTime()
            val revised = runCatching {
                brainRouter.think(
                    context.copy(
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
                    "Une révision a été produite après contrôle de la première réponse.",
                    backendId = revised.backendId,
                    durationMs = elapsedMs(revisionStarted),
                    success = true
                )
            } else {
                record(
                    executionId,
                    CognitivePhase.REVISE,
                    "Révision non disponible ; conservation de la première réponse.",
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
        recordComplete(executionId, startedAt, finalResult)
        return finalResult
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
                "duration_ms" to durationMs
            )
        )
    }

    private fun elapsedMs(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / 1_000_000L
}
