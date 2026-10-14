package com.jadegenesis.mobile.night

/**
 * Audit-only evidence decisions. A source's claim is not proof that it is
 * correct, and a generated experiment proposal is not a completed experiment.
 *
 * This engine never executes code, calls the network, or marks memory verified.
 * READY_FOR_REVIEW always requires a separate human/validator decision.
 */
internal enum class EvidenceDirection { SUPPORTS, REFUTES }
internal enum class EvidenceType { PRIMARY_DOCUMENT, SECONDARY_DOCUMENT, REPLAYED_TEST }

internal data class ReviewEvidence(
    val evidenceId: String,
    val memoryId: String,
    val sourceId: String,
    val type: EvidenceType,
    val direction: EvidenceDirection,
    val observedAt: Long,
    val inspected: Boolean = false,
    val fingerprint: String = ""
)

internal enum class ReviewDecision {
    NEEDS_EVIDENCE,
    NEEDS_INDEPENDENT_SOURCES,
    NEEDS_EXPERIMENT,
    CONTESTED,
    READY_FOR_REVIEW
}

internal data class EvidenceReview(
    val memoryId: String,
    val decision: ReviewDecision,
    val supportCount: Int,
    val refuteCount: Int,
    val primarySourceCount: Int,
    val replayedTestCount: Int,
    val reason: String,
    val automaticPromotion: Boolean = false
)

internal object NightEvidenceReviewer {
    private const val MAX_EVIDENCE_PER_CANDIDATE = 24
    private const val VALIDITY_DAYS = 180L
    private const val DAY_MS = 86_400_000L

    fun assess(
        candidate: NightReviewCandidate,
        evidence: List<ReviewEvidence>,
        now: Long
    ): EvidenceReview {
        val unique = evidence.asSequence()
            .filter {
                it.memoryId == candidate.memoryId &&
                    it.inspected &&
                    it.evidenceId.isNotBlank() &&
                    it.sourceId.isNotBlank() &&
                    it.fingerprint.matches(Regex("[a-fA-F0-9]{64}")) &&
                    it.observedAt in (now - VALIDITY_DAYS * DAY_MS)..now
            }
            .distinctBy { it.evidenceId }
            .take(MAX_EVIDENCE_PER_CANDIDATE)
            .toList()
        val supporting = unique.filter { it.direction == EvidenceDirection.SUPPORTS }
        val refuting = unique.filter { it.direction == EvidenceDirection.REFUTES }
        val primarySources = supporting
            .filter { it.type == EvidenceType.PRIMARY_DOCUMENT }
            .map { it.sourceId }.distinct()
        val documents = supporting
            .filter { it.type != EvidenceType.REPLAYED_TEST }
            .map { it.sourceId }.distinct()
        val replays = supporting
            .filter { it.type == EvidenceType.REPLAYED_TEST }
            .map { it.sourceId }.distinct()

        // Even a single inspected counterexample prevents an unchallenged verdict.
        val decision = when {
            refuting.isNotEmpty() -> ReviewDecision.CONTESTED
            documents.isEmpty() -> ReviewDecision.NEEDS_EVIDENCE
            primarySources.isEmpty() || documents.size < 2 ->
                ReviewDecision.NEEDS_INDEPENDENT_SOURCES
            replays.isEmpty() -> ReviewDecision.NEEDS_EXPERIMENT
            else -> ReviewDecision.READY_FOR_REVIEW
        }
        val reason = when (decision) {
            ReviewDecision.CONTESTED -> "Contradiction documentee: investigation requise"
            ReviewDecision.NEEDS_EVIDENCE -> "Aucune source inspectee avec empreinte valide"
            ReviewDecision.NEEDS_INDEPENDENT_SOURCES ->
                "Deux sources independantes, dont une primaire, sont requises"
            ReviewDecision.NEEDS_EXPERIMENT -> "Test reproductible inspecte requis"
            ReviewDecision.READY_FOR_REVIEW ->
                "Sources concordantes et test presents: controle humain encore requis"
        }
        return EvidenceReview(
            memoryId = candidate.memoryId,
            decision = decision,
            supportCount = supporting.size,
            refuteCount = refuting.size,
            primarySourceCount = primarySources.size,
            replayedTestCount = replays.size,
            reason = reason
        )
    }

    fun reviewAgenda(
        agenda: List<NightReviewCandidate>,
        evidence: List<ReviewEvidence>,
        now: Long
    ): List<EvidenceReview> = agenda
        .take(NightMemoryReviewPlanner.MAX_ITEMS)
        .map { assess(it, evidence, now) }
}
