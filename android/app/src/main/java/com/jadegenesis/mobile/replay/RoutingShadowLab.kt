package com.jadegenesis.mobile.replay

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload

data class RoutingShadowComparison(
    val traceId: String,
    val exactContext: Boolean,
    val recordedChampionNodeId: String?,
    val replayedChampionNodeId: String?,
    val challengerNodeId: String?,
    val championReproduced: Boolean,
    val challengerChangedDecision: Boolean,
    val reason: String
)

/**
 * Exact counterfactual routing replay.
 *
 * It never executes a task and never mutates production state. It only accepts
 * DecisionTrace entries whose scoringContextVersion proves that every input
 * consumed by TaskRouter.rankNode() was captured at decision time.
 */
class RoutingShadowLab {

    fun compare(
        trace: DecisionTrace,
        champion: RoutingTuning,
        challenger: RoutingTuning
    ): RoutingShadowComparison {
        if (trace.scoringContextVersion != SCORING_CONTEXT_VERSION) {
            return RoutingShadowComparison(
                traceId = trace.traceId,
                exactContext = false,
                recordedChampionNodeId = trace.chosenNodeId,
                replayedChampionNodeId = null,
                challengerNodeId = null,
                championReproduced = false,
                challengerChangedDecision = false,
                reason = "Trace antérieure : contexte de score incomplet pour un replay challenger exact."
            )
        }

        val championWinner = winner(trace, champion)
        val challengerWinner = winner(trace, challenger)
        val reproduced = championWinner?.nodeId == trace.chosenNodeId
        val changed = reproduced && challengerWinner?.nodeId != championWinner?.nodeId

        val reason = when {
            !reproduced ->
                "Le replay champion ne reproduit pas la décision enregistrée; la trace est refusée pour l'expérience."
            challengerWinner == null ->
                "Le challenger ne trouve aucun nœud éligible dans le contexte enregistré."
            changed ->
                "Le challenger change la décision sur un contexte exactement reproductible."
            else ->
                "Le challenger conserve la même décision que le champion sur ce contexte."
        }

        return RoutingShadowComparison(
            traceId = trace.traceId,
            exactContext = true,
            recordedChampionNodeId = trace.chosenNodeId,
            replayedChampionNodeId = championWinner?.nodeId,
            challengerNodeId = challengerWinner?.nodeId,
            championReproduced = reproduced,
            challengerChangedDecision = changed,
            reason = reason
        )
    }

    private fun winner(
        trace: DecisionTrace,
        routing: RoutingTuning
    ): DecisionAlternativeTrace? = trace.alternatives
        .asSequence()
        .filter { it.eligible }
        .mapIndexed { index, alternative ->
            RankedAlternative(
                index = index,
                alternative = alternative,
                score = score(trace, alternative, routing)
            )
        }
        .sortedWith(
            compareByDescending<RankedAlternative> { it.score }
                .thenByDescending { it.alternative.ramAvailableGb }
                .thenByDescending { it.alternative.cpuCores }
                .thenBy { it.index }
        )
        .firstOrNull()
        ?.alternative

    private fun score(
        trace: DecisionTrace,
        node: DecisionAlternativeTrace,
        routing: RoutingTuning
    ): Double {
        val remote = node.nodeKind != NodeKind.PHONE.name
        var score = 0.0

        score += node.cpuCores.coerceAtMost(32) * routing.cpuCoreWeight
        score += node.ramAvailableGb.coerceAtMost(32.0) * routing.ramAvailableGbWeight
        score += node.storageFreeGb.coerceAtMost(250.0) * routing.storageFreeGbWeight

        if (trace.preferRemoteCompute && remote) score += routing.preferRemoteRemoteBonus
        if (trace.preferRemoteCompute && !remote) score += routing.preferRemoteLocalPenalty
        if (!trace.preferRemoteCompute && !remote) score += routing.localAllowedLocalBonus
        if (!trace.preferRemoteCompute && remote) score += routing.localAllowedRemoteBonus

        when (trace.workload) {
            TaskWorkload.LIGHT -> score += if (remote) {
                routing.lightRemoteBonus
            } else {
                routing.lightLocalBonus
            }
            TaskWorkload.MEDIUM -> score += if (remote) {
                routing.mediumRemoteBonus
            } else {
                routing.mediumLocalBonus
            }
            TaskWorkload.HEAVY -> score += if (remote) {
                routing.heavyRemoteBonus
            } else {
                routing.heavyLocalBonus
            }
        }

        if (
            trace.taskKind == "memory_consolidation" &&
            remote &&
            node.ramAvailableGb >= routing.consolidationRemoteMinRamGb
        ) {
            score += routing.consolidationRemoteBonus
        }

        if (node.historyAttempts > 0) {
            val successes = node.historySuccesses.coerceIn(0, node.historyAttempts)
            val failures = node.historyAttempts - successes
            val successRate = successes.toDouble() / node.historyAttempts.toDouble()
            score += successRate * routing.historySuccessRateWeight
            score -= failures * routing.historyFailurePenalty
            node.averageDurationMs
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { average ->
                    score += routing.historyDurationBonus /
                        (1.0 + average / routing.historyDurationScaleMs)
                }
        }

        return score
    }

    private data class RankedAlternative(
        val index: Int,
        val alternative: DecisionAlternativeTrace,
        val score: Double
    )

    companion object {
        const val SCORING_CONTEXT_VERSION = 1
    }
}
