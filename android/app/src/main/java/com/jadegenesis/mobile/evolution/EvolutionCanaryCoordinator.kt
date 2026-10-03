package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.replay.DecisionTraceStore
import com.jadegenesis.mobile.replay.RoutingShadowLab
import org.json.JSONObject

enum class EvolutionCanaryRunState {
    IDLE,
    WAITING_FOR_SHADOW,
    RUNNING,
    STOPPED,
    COMPLETE
}

data class EvolutionCanaryRunReport(
    val state: EvolutionCanaryRunState,
    val candidateId: String? = null,
    val pairCount: Int = 0,
    val shadowExactContexts: Int = 0,
    val shadowChangedContexts: Int = 0,
    val reason: String
)

class EvolutionCanaryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val evolution = EvolutionRuntime.initialize(appContext)
    private val store = EvolutionCanaryStore(appContext)
    private val traces = DecisionTraceStore(appContext)
    private val shadowLab = RoutingShadowLab()
    private var lastShadowGate: ShadowGate? = null

    suspend fun runStep(
        core: JadeCore,
        maxPairsThisRun: Int = SafetyPolicy.MAX_EVOLUTION_CANARY_PAIRS_PER_CYCLE
    ): EvolutionCanaryRunReport {
        val pairBudget = maxPairsThisRun.coerceIn(
            1, SafetyPolicy.MAX_EVOLUTION_CANARY_PAIRS_PER_CYCLE
        )
        var session = store.load()
        if (session == null || session.status != EvolutionCanaryStatus.RUNNING) {
            val start = startEligibleSession() ?: return waitingOrIdleReport()
            session = start.session
            store.save(session)
        }
        var activeSession = requireNotNull(session)

        val candidate = evolution.candidate(activeSession.candidateId)
            ?: return stopOrphanSession(activeSession, "Candidat Evolution introuvable.")
        if (candidate.status != EvolutionCandidateStatus.TESTING) {
            return stopOrphanSession(
                activeSession,
                "Le candidat n'est plus en état TESTING."
            )
        }
        if (JadeConfigRuntime.current().validated().configId != candidate.championConfigId) {
            evolution.reject(
                candidate.candidateId,
                "Champion actif modifié pendant le canary.",
                EvolutionFailureKind.CHAMPION_CHANGED
            )
            return stopOrphanSession(
                activeSession,
                "Le champion actif a changé; canary interrompu."
            )
        }

        val taskKind = candidate.experimentTaskKind ?: run {
            evolution.reject(
                candidate.candidateId,
                "Candidat canary sans type de tâche sûr.",
                EvolutionFailureKind.OTHER
            )
            return stopOrphanSession(
                activeSession,
                "Type de tâche canary absent; expérience interrompue."
            )
        }
        val champion = parseConfig(activeSession.championConfigJson)
        val challenger = parseConfig(activeSession.challengerConfigJson)
        repeat(pairBudget) {
            if (activeSession.pairCount >= SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES) {
                return finalizeSession(activeSession)
            }
            val pairIndex = activeSession.pairCount + 1
            val (baselineResult, challengerResult) = core.runEvolutionCanaryPair(
                taskKind = taskKind,
                championConfig = champion,
                challengerConfig = challenger,
                challengerFirst = pairIndex % 2 == 0
            )
            val baselineObservation = observation(
                result = baselineResult,
                id = "canary-champion-${activeSession.candidateId}-$pairIndex"
            )
            val challengerObservation = observation(
                result = challengerResult,
                id = "canary-challenger-${activeSession.candidateId}-$pairIndex"
            )
            activeSession = activeSession.copy(
                baseline = activeSession.baseline + baselineObservation,
                challenger = activeSession.challenger + challengerObservation,
                updatedAt = System.currentTimeMillis()
            )
            val assessment = EvolutionCanaryPolicy.assess(
                activeSession.baseline,
                activeSession.challenger
            )
            activeSession = activeSession.copy(reason = assessment.reason)
            store.save(activeSession)

            when (assessment.decision) {
                EvolutionCanaryDecision.STOP_REGRESSION,
                EvolutionCanaryDecision.ABORT_INVALID_EVIDENCE -> {
                    evolution.reject(
                        candidateId = candidate.candidateId,
                        reason = assessment.reason,
                        failureKind = assessment.failureKind,
                        failureMetrics = EvolutionFailureMetrics(
                            baselineSamples = assessment.pairCount,
                            challengerSamples = assessment.pairCount,
                            baselineSuccessRate = assessment.baselineSuccessRate,
                            challengerSuccessRate = assessment.challengerSuccessRate,
                            baselineAverageDurationMs = assessment.baselineAverageDurationMs,
                            challengerAverageDurationMs = assessment.challengerAverageDurationMs
                        )
                    )
                    activeSession = activeSession.copy(
                        status = EvolutionCanaryStatus.STOPPED,
                        reason = assessment.reason,
                        updatedAt = System.currentTimeMillis()
                    )
                    store.save(activeSession)
                    return report(activeSession, EvolutionCanaryRunState.STOPPED)
                }
                EvolutionCanaryDecision.COMPLETE ->
                    return finalizeSession(activeSession)
                EvolutionCanaryDecision.CONTINUE -> Unit
            }
        }

        return report(activeSession, EvolutionCanaryRunState.RUNNING)
    }

    private fun startEligibleSession(): SessionStart? {
        val candidate = evolution.candidates(SafetyPolicy.MAX_EVOLUTION_CANDIDATES)
            .firstOrNull { candidate ->
                candidate.status == EvolutionCandidateStatus.PROPOSED &&
                    candidate.experimentTaskKind in CANARY_TASK_KINDS
            }
            ?: return null
        val activeChampion = JadeConfigRuntime.current().validated()
        if (activeChampion.configId != candidate.championConfigId) {
            evolution.reject(candidate.candidateId, "Candidat obsolète : champion actif différent.", EvolutionFailureKind.CHAMPION_CHANGED)
            return null
        }

        val taskKind = requireNotNull(candidate.experimentTaskKind)
        val champion = parseConfig(candidate.championConfigJson)
        val challenger = parseConfig(candidate.challengerConfigJson)
        val comparisons = traces.recentByDecisionKind(
            decisionKind = "task_routing",
            limit = SHADOW_TRACE_WINDOW
        ).filter { trace ->
            trace.taskKind == taskKind &&
                trace.workload == TaskWorkload.MEDIUM
        }.map { trace ->
            shadowLab.compare(trace, champion.routing, challenger.routing)
        }
        val exact = comparisons.count {
            it.exactContext && it.championScoresReproduced && it.championReproduced
        }
        val changed = comparisons.count {
            it.exactContext && it.championReproduced && it.challengerChangedDecision
        }
        if (exact < SafetyPolicy.MIN_EVOLUTION_CANARY_SHADOW_CONTEXTS || changed < SafetyPolicy.MIN_EVOLUTION_CANARY_SHADOW_DIVERGENCES) {
            lastShadowGate = ShadowGate(candidate.candidateId, exact, changed)
            return null
        }

        val plan = evolution.beginSandboxTrial(candidate.candidateId)
        val now = System.currentTimeMillis()
        lastShadowGate = ShadowGate(candidate.candidateId, exact, changed)
        return SessionStart(
            session = EvolutionCanarySession(
                candidateId = candidate.candidateId,
                scenarioSetId = plan.scenarioSetId,
                championConfigJson = plan.championConfigJson,
                challengerConfigJson = plan.challengerConfigJson,
                status = EvolutionCanaryStatus.RUNNING,
                reason = "Shadow exact validé : $exact contexte(s), $changed divergence(s).",
                startedAt = now,
                updatedAt = now
            )
        )
    }

    private fun waitingOrIdleReport(): EvolutionCanaryRunReport {
        val gate = lastShadowGate
        return if (gate == null) {
            EvolutionCanaryRunReport(
                state = EvolutionCanaryRunState.IDLE,
                reason = "Aucun challenger PROPOSED disponible pour un canary."
            )
        } else {
            EvolutionCanaryRunReport(
                state = EvolutionCanaryRunState.WAITING_FOR_SHADOW,
                candidateId = gate.candidateId,
                shadowExactContexts = gate.exact,
                shadowChangedContexts = gate.changed,
                reason = "Shadow insuffisant : ${gate.exact}/${SafetyPolicy.MIN_EVOLUTION_CANARY_SHADOW_CONTEXTS} contexte(s) exact(s), ${gate.changed}/${SafetyPolicy.MIN_EVOLUTION_CANARY_SHADOW_DIVERGENCES} divergence(s)."
            )
        }
    }

    private fun finalizeSession(
        session: EvolutionCanarySession
    ): EvolutionCanaryRunReport {
        val result = evolution.recordPairedSandboxEvaluation(
            candidateId = session.candidateId,
            scenarioSetId = session.scenarioSetId,
            baselineObservations = session.baseline,
            challengerObservations = session.challenger
        )
        val complete = session.copy(
            status = EvolutionCanaryStatus.COMPLETE,
            reason = result.comparison?.reason ?: "Canary terminé.",
            updatedAt = System.currentTimeMillis()
        )
        store.save(complete)
        return report(complete, EvolutionCanaryRunState.COMPLETE)
    }

    private fun stopOrphanSession(
        session: EvolutionCanarySession,
        reason: String
    ): EvolutionCanaryRunReport {
        val stopped = session.copy(
            status = EvolutionCanaryStatus.STOPPED,
            reason = reason,
            updatedAt = System.currentTimeMillis()
        )
        store.save(stopped)
        return report(stopped, EvolutionCanaryRunState.STOPPED)
    }

    private fun report(
        session: EvolutionCanarySession,
        state: EvolutionCanaryRunState
    ): EvolutionCanaryRunReport {
        val gate = lastShadowGate
        return EvolutionCanaryRunReport(
            state = state,
            candidateId = session.candidateId,
            pairCount = session.pairCount,
            shadowExactContexts = gate?.exact ?: 0,
            shadowChangedContexts = gate?.changed ?: 0,
            reason = session.reason
        )
    }

    private fun observation(
        result: DistributedTaskResult,
        id: String
    ): RuntimeEvalObservation = RuntimeEvalObservation(
        observationId = id,
        taskId = result.taskId,
        taskKind = result.taskKind,
        workload = TaskWorkload.MEDIUM,
        nodeId = result.executedNodeId,
        nodeName = result.executedNodeName,
        nodeKind = if (result.executionLocation == TaskExecutionLocation.LOCAL) {
            NodeKind.PHONE
        } else {
            NodeKind.UNKNOWN
        },
        success = result.success,
        durationMs = result.durationMs.coerceAtLeast(0L),
        outputChars = result.output.length,
        fallbackUsed = result.fallbackUsed,
        error = if (result.success) null
        else result.fallbackReason ?: result.routeReason,
        createdAt = result.completedAt
    )

    private fun parseConfig(raw: String): JadeConfig =
        JadeConfig.fromJson(JSONObject(raw)).validated()

    private data class SessionStart(
        val session: EvolutionCanarySession
    )

    private data class ShadowGate(
        val candidateId: String,
        val exact: Int,
        val changed: Int
    )

    companion object {
        const val SHADOW_TRACE_WINDOW = 32
        private val CANARY_TASK_KINDS = setOf("genesis_probe", "text_analysis")
    }
}
