package com.jadegenesis.mobile.replay

/**
 * Fail-closed protocol rules shared by repeated capability measurements.
 *
 * Measured rounds must be even so each node executes first the same number of
 * times. Warm-ups must also be verified before any measured round starts.
 */
object CapabilityMeasurementProtocol {

    const val DEFAULT_PAIRED_ROUNDS = 6
    const val MIN_PAIRED_ROUNDS = 2
    const val MAX_PAIRED_ROUNDS = 8
    const val MIN_CANARY_ROUNDS = 6

    fun isBalancedRoundCount(rounds: Int): Boolean =
        rounds in MIN_PAIRED_ROUNDS..MAX_PAIRED_ROUNDS && rounds % 2 == 0

    fun requireBalancedRounds(rounds: Int) {
        require(isBalancedRoundCount(rounds)) {
            "Le nombre de tours doit être pair et compris entre " +
                "$MIN_PAIRED_ROUNDS et $MAX_PAIRED_ROUNDS."
        }
    }

    fun requireVerifiedWarmup(
        expectedNodeId: String,
        evidence: CapabilityExecutionEvidence
    ) {
        require(evidence.nodeId == expectedNodeId) {
            "L'échauffement FFmpeg ne provient pas du nœud attendu."
        }
        require(evidence.success && evidence.verificationPassed) {
            "L'échauffement FFmpeg du nœud $expectedNodeId n'est pas vérifié."
        }
    }
}
