package com.jadegenesis.mobile.night

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.evolution.EvolutionCandidateStatus
import com.jadegenesis.mobile.evolution.EvolutionRuntime
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.state.SharedGenesisStateBootstrapper
import java.util.UUID

class NightCycleEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = NightCycleStore(appContext)
    private val diagnostics = DiagnosticLogger(appContext)
    private val core = JadeCore(appContext)
    private val sharedState = SharedGenesisStateBootstrapper(appContext)
    private val runtimeEval = RuntimeEvalRuntime.initialize(appContext)
    private val evolution = EvolutionRuntime.initialize(appContext)

    suspend fun runOnce(
        force: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): NightCycleRun {
        val lastProtected = store.lastProtectedCompletedAt()
        if (!force && !NightCyclePolicy.shouldRun(lastProtected, now)) {
            val report = runtimeEval.report()
            val skipped = NightCycleRun(
                runId = "night-${UUID.randomUUID()}",
                status = NightCycleStatus.SKIPPED,
                startedAt = now,
                completedAt = now,
                memoryBatchesProcessed = 0,
                runtimeObservationCount = runtimeEval.count(),
                runtimeScore = report.score,
                runtimeConfidence = report.confidence,
                evolutionCandidateCount = evolution.count(),
                evolutionValidatedCount = evolution.candidates(
                    SafetyPolicy.MAX_EVOLUTION_CANDIDATES
                ).count { it.status == EvolutionCandidateStatus.VALIDATED },
                steps = listOf(
                    NightCycleStep(
                        phase = NightCyclePhase.COMPLETE,
                        success = true,
                        summary = "Cycle nocturne ignoré : un cycle complet ou partiel est encore dans la fenêtre de protection anti-répétition.",
                        durationMs = 0L
                    )
                )
            )
            diagnostics.log(
                DiagnosticLevel.INFO,
                "night_cycle_skipped",
                "Night Cycle ignoré par la garde de cadence.",
                mapOf("last_protected_at" to lastProtected)
            )
            return skipped
        }

        val runId = "night-${UUID.randomUUID()}"
        val startedAt = now
        val steps = mutableListOf<NightCycleStep>()
        var memoryBatches = 0
        var runtimeObservationCount = 0
        var runtimeScore = 0.0
        var runtimeConfidence = 0.0
        var evolutionCandidateCount = 0
        var evolutionValidatedCount = 0
        var fatalError: String? = null

        val prepareStarted = System.currentTimeMillis()
        val prepared = runCatching { core.initialize() }
        steps += NightCycleStep(
            phase = NightCyclePhase.PREPARE,
            success = prepared.isSuccess,
            summary = prepared.fold(
                onSuccess = { self ->
                    "Jade initialisée pour le cycle nocturne sur ${self.nodeId}; mode ressources ${self.resourceBudget.mode.name}."
                },
                onFailure = { error ->
                    "Initialisation impossible : ${safeError(error)}"
                }
            ),
            durationMs = elapsedSince(prepareStarted)
        )

        if (prepared.isFailure) {
            fatalError = safeError(prepared.exceptionOrNull())
        } else {
            steps += syncStep(NightCyclePhase.SYNC_BEFORE)

            val memoryStarted = System.currentTimeMillis()
            var memorySuccess = true
            var memorySummary = "Aucune consolidation nécessaire."
            for (index in 0 until SafetyPolicy.MAX_NIGHT_MEMORY_BATCHES) {
                val result = runCatching { core.runMemoryConsolidation() }
                if (result.isFailure) {
                    memorySuccess = false
                    memorySummary =
                        "Consolidation interrompue après $memoryBatches lot(s) : ${safeError(result.exceptionOrNull())}"
                    break
                }

                val taskResult = result.getOrThrow()
                if (!taskResult.success) {
                    memorySuccess = false
                    memorySummary =
                        "Le lot ${index + 1} a échoué sur ${taskResult.executedNodeName}."
                    break
                }
                if (taskResult.taskKind == "memory_lifecycle_noop") {
                    memorySummary = if (memoryBatches == 0) {
                        "Mémoire déjà consolidée jusqu'au curseur courant."
                    } else {
                        "$memoryBatches lot(s) consolidé(s), puis curseur arrivé à jour."
                    }
                    break
                }

                memoryBatches += 1
                memorySummary = "$memoryBatches lot(s) mémoire consolidé(s) avec succès."
            }
            if (
                memorySuccess &&
                memoryBatches == SafetyPolicy.MAX_NIGHT_MEMORY_BATCHES
            ) {
                memorySummary =
                    "$memoryBatches lot(s) consolidé(s); plafond nocturne atteint, suite reportée au prochain cycle."
            }
            steps += NightCycleStep(
                phase = NightCyclePhase.MEMORY_CONSOLIDATION,
                success = memorySuccess,
                summary = memorySummary,
                durationMs = elapsedSince(memoryStarted)
            )

            val runtimeStarted = System.currentTimeMillis()
            val runtimeReview = runCatching { runtimeEval.report() }
            runtimeReview.onSuccess { report ->
                runtimeObservationCount = report.observationCount
                runtimeScore = report.score
                runtimeConfidence = report.confidence
            }
            steps += NightCycleStep(
                phase = NightCyclePhase.RUNTIME_EVAL_REVIEW,
                success = runtimeReview.isSuccess,
                summary = runtimeReview.fold(
                    onSuccess = { report ->
                        "Runtime Eval : ${report.observationCount} observation(s), score ${format(report.score)}/100, confiance ${format(report.confidence * 100.0)} %."
                    },
                    onFailure = { error ->
                        "Lecture Runtime Eval impossible : ${safeError(error)}"
                    }
                ),
                durationMs = elapsedSince(runtimeStarted)
            )

            val evolutionStarted = System.currentTimeMillis()
            val evolutionReview = runCatching {
                evolution.candidates(SafetyPolicy.MAX_EVOLUTION_CANDIDATES)
            }
            evolutionReview.onSuccess { candidates ->
                evolutionCandidateCount = candidates.size
                evolutionValidatedCount = candidates.count {
                    it.status == EvolutionCandidateStatus.VALIDATED
                }
            }
            steps += NightCycleStep(
                phase = NightCyclePhase.EVOLUTION_REVIEW,
                success = evolutionReview.isSuccess,
                summary = evolutionReview.fold(
                    onSuccess = { candidates ->
                        val proposed = candidates.count {
                            it.status == EvolutionCandidateStatus.PROPOSED
                        }
                        val testing = candidates.count {
                            it.status == EvolutionCandidateStatus.TESTING
                        }
                        val validated = candidates.count {
                            it.status == EvolutionCandidateStatus.VALIDATED
                        }
                        "Evolution Engine : ${candidates.size} candidat(s), $proposed proposé(s), $testing en test, $validated validé(s) en attente d'approbation. Aucune promotion automatique."
                    },
                    onFailure = { error ->
                        "Revue Evolution Engine impossible : ${safeError(error)}"
                    }
                ),
                durationMs = elapsedSince(evolutionStarted)
            )

            steps += syncStep(NightCyclePhase.SYNC_AFTER)
        }

        val completedAt = System.currentTimeMillis().coerceAtLeast(startedAt)
        val status = if (fatalError != null) {
            NightCycleStatus.FAILED
        } else {
            NightCyclePolicy.finalStatus(steps)
        }
        val completeSummary = when (status) {
            NightCycleStatus.SUCCESS ->
                "Cycle nocturne terminé sans erreur; aucune promotion Evolution n'a été effectuée automatiquement."
            NightCycleStatus.PARTIAL ->
                "Cycle nocturne terminé partiellement; les étapes en échec seront retentées lors d'un prochain cycle."
            NightCycleStatus.FAILED ->
                "Cycle nocturne interrompu avant exécution complète."
            NightCycleStatus.SKIPPED ->
                "Cycle nocturne ignoré."
        }
        steps += NightCycleStep(
            phase = NightCyclePhase.COMPLETE,
            success = status != NightCycleStatus.FAILED,
            summary = completeSummary,
            durationMs = completedAt - startedAt
        )

        val run = NightCycleRun(
            runId = runId,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt,
            memoryBatchesProcessed = memoryBatches,
            runtimeObservationCount = runtimeObservationCount,
            runtimeScore = runtimeScore.coerceIn(0.0, 100.0),
            runtimeConfidence = runtimeConfidence.coerceIn(0.0, 1.0),
            evolutionCandidateCount = evolutionCandidateCount,
            evolutionValidatedCount = evolutionValidatedCount,
            steps = steps.take(SafetyPolicy.MAX_NIGHT_CYCLE_STEPS),
            error = fatalError
        )
        store.save(run)

        diagnostics.log(
            if (status == NightCycleStatus.FAILED) {
                DiagnosticLevel.ERROR
            } else if (status == NightCycleStatus.PARTIAL) {
                DiagnosticLevel.WARN
            } else {
                DiagnosticLevel.INFO
            },
            "night_cycle_complete",
            "Night Cycle ${status.name.lowercase()}.",
            mapOf(
                "run_id" to runId,
                "memory_batches" to memoryBatches,
                "runtime_observations" to runtimeObservationCount,
                "runtime_score" to runtimeScore,
                "runtime_confidence" to runtimeConfidence,
                "evolution_candidates" to evolutionCandidateCount,
                "evolution_validated" to evolutionValidatedCount,
                "duration_ms" to (completedAt - startedAt)
            )
        )
        return run
    }

    fun recentRuns(limit: Int = 10): List<NightCycleRun> = store.recent(limit)

    private suspend fun syncStep(phase: NightCyclePhase): NightCycleStep {
        val started = System.currentTimeMillis()
        val result = runCatching { sharedState.syncCurrentState() }
        return NightCycleStep(
            phase = phase,
            success = result.isSuccess,
            summary = result.fold(
                onSuccess = { sync ->
                    "Shared Genesis State : ${sync.uploadedEvents} envoyé(s), ${sync.receivedEvents} reçu(s), outbox ${sync.outboxRemaining}."
                },
                onFailure = { error ->
                    "Synchronisation distante indisponible; état local conservé : ${safeError(error)}"
                }
            ),
            durationMs = elapsedSince(started)
        )
    }

    private fun elapsedSince(startedAt: Long): Long =
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

    private fun safeError(error: Throwable?): String =
        error?.message
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.trim()
            ?.take(220)
            ?.takeIf { it.isNotBlank() }
            ?: error?.javaClass?.simpleName
            ?: "erreur inconnue"

    private fun format(value: Double): String =
        "%.1f".format(java.util.Locale.US, value)
}
