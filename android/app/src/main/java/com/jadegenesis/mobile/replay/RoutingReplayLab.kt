package com.jadegenesis.mobile.replay

data class RoutingReplayObservation(
    val traceId: String,
    val recordedNodeId: String?,
    val replayedNodeId: String?,
    val matched: Boolean,
    val reason: String
)

data class RoutingAAReplayReport(
    val total: Int,
    val matched: Int,
    val mismatched: Int,
    val withoutCandidate: Int,
    val observations: List<RoutingReplayObservation>
) {
    val matchRate: Double
        get() = if (total == 0) 1.0 else matched.toDouble() / total.toDouble()

    val passed: Boolean
        get() = total > 0 && mismatched == 0 && withoutCandidate == 0
}

/**
 * Replay Lab V0 A/A harness.
 *
 * Replays the exact routing ordering from a DecisionTrace without executing
 * tasks or touching production state. This validates that the captured
 * decision context can reproduce the recorded winner before challenger
 * policies or Branch Harvester are introduced.
 */
class RoutingReplayLab {

    fun replay(trace: DecisionTrace): RoutingReplayObservation {
        val candidates = trace.alternatives
            .mapIndexedNotNull { index, alternative ->
                val score = alternative.score
                if (!alternative.eligible || score == null || !score.isFinite()) {
                    null
                } else {
                    IndexedCandidate(
                        index = index,
                        alternative = alternative,
                        score = score
                    )
                }
            }
            .sortedWith(
                compareByDescending<IndexedCandidate> { it.score }
                    .thenByDescending { it.alternative.ramAvailableGb }
                    .thenByDescending { it.alternative.cpuCores }
                    .thenBy { it.index }
            )

        val replayed = candidates.firstOrNull()?.alternative
        val recorded = trace.chosenNodeId

        if (replayed == null) {
            return RoutingReplayObservation(
                traceId = trace.traceId,
                recordedNodeId = recorded,
                replayedNodeId = null,
                matched = recorded == null,
                reason = if (recorded == null) {
                    "No eligible candidate in trace and no recorded winner."
                } else {
                    "Trace recorded a winner but contains no replayable candidate."
                }
            )
        }

        val matched = replayed.nodeId == recorded
        return RoutingReplayObservation(
            traceId = trace.traceId,
            recordedNodeId = recorded,
            replayedNodeId = replayed.nodeId,
            matched = matched,
            reason = if (matched) {
                "A/A replay reproduced the recorded routing winner."
            } else {
                "A/A replay selected a different winner from the recorded decision."
            }
        )
    }

    fun evaluate(traces: List<DecisionTrace>): RoutingAAReplayReport {
        val observations = traces.map(::replay)
        val matched = observations.count { it.matched }
        val withoutCandidate = observations.count {
            it.replayedNodeId == null && it.recordedNodeId != null
        }
        val mismatched = observations.count {
            !it.matched && it.replayedNodeId != null
        }

        return RoutingAAReplayReport(
            total = observations.size,
            matched = matched,
            mismatched = mismatched,
            withoutCandidate = withoutCandidate,
            observations = observations
        )
    }

    private data class IndexedCandidate(
        val index: Int,
        val alternative: DecisionAlternativeTrace,
        val score: Double
    )
}
