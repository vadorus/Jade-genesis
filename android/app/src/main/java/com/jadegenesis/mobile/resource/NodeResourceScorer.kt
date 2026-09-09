package com.jadegenesis.mobile.resource

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.model.GenesisNode

object NodeResourceScorer {

    fun genericScore(
        node: GenesisNode,
        routing: RoutingTuning
    ): Double {
        var score = 0.0

        score += node.cpuCores.coerceIn(0, 32) * routing.cpuCoreWeight
        score += node.ramAvailableGb.coerceIn(0.0, 32.0) * routing.ramAvailableGbWeight
        score += node.storageFreeGb.coerceIn(0.0, 250.0) * routing.storageFreeGbWeight

        if (node.cpuLoadPercent >= 0.0) {
            val cpuHeadroom = 100.0 - node.cpuLoadPercent.coerceIn(0.0, 100.0)
            score += cpuHeadroom * routing.cpuHeadroomPercentWeight
        }

        score -= node.activeTaskCount.coerceIn(0, 16) * routing.activeTaskPenalty
        return score
    }

    fun generativeScore(
        node: GenesisNode,
        routing: RoutingTuning,
        preferredNodeId: String? = null
    ): Double {
        var score = genericScore(node, routing)

        score += node.gpuVramFreeGb.coerceIn(0.0, 48.0) * routing.gpuVramFreeGbWeight
        if (node.gpuUtilizationPercent >= 0.0) {
            val gpuHeadroom = 100.0 - node.gpuUtilizationPercent.coerceIn(0.0, 100.0)
            score += gpuHeadroom * routing.gpuHeadroomPercentWeight
        }
        if (node.brainReady) {
            score += routing.brainReadyBonus
        }
        if (node.brainLoaded) {
            score += routing.brainLoadedBonus
        }
        if (node.brainTokensPerSecond > 0.0) {
            score += node.brainTokensPerSecond.coerceAtMost(200.0) *
                routing.brainTokensPerSecondWeight
        }
        if (preferredNodeId != null && node.nodeId == preferredNodeId) {
            score += routing.preferredNodeHintBonus
        }

        return score
    }
}
