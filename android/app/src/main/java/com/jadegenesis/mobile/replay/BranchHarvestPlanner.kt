package com.jadegenesis.mobile.replay

data class BranchHarvestCandidate(
    val nodeId: String,
    val nodeName: String,
    val score: Double,
    val ramAvailableGb: Double,
    val cpuCores: Int
)

data class BranchHarvestPlan(
    val traceId: String,
    val primaryNodeId: String?,
    val primaryNodeName: String?,
    val candidates: List<BranchHarvestCandidate>
)

/**
 * V0 planner for counterfactual branch harvesting.
 *
 * It only selects replay-safe alternative nodes from an existing DecisionTrace.
 * It does not execute tasks, keep payloads, contact nodes, or mutate routing.
 */
class BranchHarvestPlanner {

    fun plan(
        trace: DecisionTrace,
        maxBranches: Int = 2
    ): BranchHarvestPlan {
        val safeMax = maxBranches.coerceIn(0, MAX_BRANCHES)
        if (safeMax == 0) {
            return BranchHarvestPlan(
                traceId = trace.traceId,
                primaryNodeId = trace.chosenNodeId,
                primaryNodeName = trace.chosenNodeName,
                candidates = emptyList()
            )
        }

        val candidates = trace.alternatives
            .mapIndexedNotNull { index, alternative ->
                val score = alternative.score
                val usable =
                    alternative.eligible &&
                        score != null &&
                        score.isFinite() &&
                        alternative.nodeId != trace.chosenNodeId

                if (!usable) {
                    null
                } else {
                    IndexedCandidate(
                        index = index,
                        candidate = BranchHarvestCandidate(
                            nodeId = alternative.nodeId,
                            nodeName = alternative.nodeName,
                            score = score,
                            ramAvailableGb = alternative.ramAvailableGb,
                            cpuCores = alternative.cpuCores
                        )
                    )
                }
            }
            .sortedWith(
                compareByDescending<IndexedCandidate> {
                    it.candidate.score
                }
                    .thenByDescending {
                        it.candidate.ramAvailableGb
                    }
                    .thenByDescending {
                        it.candidate.cpuCores
                    }
                    .thenBy { it.index }
            )
            .take(safeMax)
            .map { it.candidate }

        return BranchHarvestPlan(
            traceId = trace.traceId,
            primaryNodeId = trace.chosenNodeId,
            primaryNodeName = trace.chosenNodeName,
            candidates = candidates
        )
    }

    fun planBatch(
        traces: List<DecisionTrace>,
        maxBranchesPerTrace: Int = 2
    ): List<BranchHarvestPlan> =
        traces.map {
            plan(
                trace = it,
                maxBranches = maxBranchesPerTrace
            )
        }

    companion object {
        const val MAX_BRANCHES = 3
    }

    private data class IndexedCandidate(
        val index: Int,
        val candidate: BranchHarvestCandidate
    )
}
