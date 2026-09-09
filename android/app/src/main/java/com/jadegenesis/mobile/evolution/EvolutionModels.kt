package com.jadegenesis.mobile.evolution

enum class EvolutionCandidateKind {
    CONFIG
}

enum class EvolutionCandidateStatus {
    CANDIDATE,
    PROPOSED,
    TESTING,
    VALIDATED,
    PROMOTED,
    REJECTED,
    ROLLED_BACK
}

data class EvolutionTransition(
    val status: EvolutionCandidateStatus,
    val at: Long,
    val note: String
)

data class EvolutionEvidenceSnapshot(
    val source: String,
    val scenarioSetId: String,
    val observationCount: Int,
    val overallSuccessRate: Double,
    val score: Double,
    val confidence: Double,
    val evidenceSha256: String,
    val generatedAt: Long
)

data class EvolutionComparison(
    val baselineScore: Double,
    val challengerScore: Double,
    val scoreDelta: Double,
    val baselineSuccessRate: Double,
    val challengerSuccessRate: Double,
    val successRateDelta: Double,
    val enoughEvidence: Boolean,
    val confidenceSatisfied: Boolean,
    val pairedScenarioCompatible: Boolean,
    val successRateProtected: Boolean,
    val scoreImproved: Boolean,
    val promotionEligible: Boolean,
    val reason: String
)

data class EvolutionSandboxResult(
    val passed: Boolean,
    val changedFields: Int,
    val checks: List<String>,
    val errors: List<String>,
    val championConfigSha256: String,
    val challengerConfigSha256: String
)

data class EvolutionSandboxPlan(
    val candidateId: String,
    val scenarioSetId: String,
    val championConfigJson: String,
    val challengerConfigJson: String,
    val championConfigSha256: String,
    val challengerConfigSha256: String,
    val baselineRuntimeEvidence: EvolutionEvidenceSnapshot?,
    val createdAt: Long
)

data class EvolutionCandidate(
    val candidateId: String,
    val kind: EvolutionCandidateKind,
    val title: String,
    val rationale: String,
    val status: EvolutionCandidateStatus,
    val championConfigId: String,
    val challengerConfigId: String,
    val championConfigJson: String,
    val challengerConfigJson: String,
    val baselineRuntimeEvidence: EvolutionEvidenceSnapshot?,
    val pairedBaselineEvidence: EvolutionEvidenceSnapshot? = null,
    val pairedChallengerEvidence: EvolutionEvidenceSnapshot? = null,
    val comparison: EvolutionComparison? = null,
    val sandbox: EvolutionSandboxResult? = null,
    val transitions: List<EvolutionTransition> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val promotedAt: Long? = null,
    val rolledBackAt: Long? = null,
    val lastError: String? = null
)