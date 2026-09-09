package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalEngine
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import org.json.JSONObject
import java.util.UUID

class EvolutionEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = EvolutionStore(appContext)

    fun candidates(limit: Int = 20): List<EvolutionCandidate> =
        store.recent(limit)

    fun candidate(candidateId: String): EvolutionCandidate? =
        store.get(candidateId)

    fun count(): Int = store.count()

    @Synchronized
    fun proposeConfigCandidate(
        title: String,
        rationale: String,
        proposedConfig: JadeConfig
    ): EvolutionCandidate {
        val champion = JadeConfigRuntime.current().validated()
        val now = System.currentTimeMillis()
        val candidateId = "evo-${UUID.randomUUID()}"
        val challenger = proposedConfig.copy(
            schemaVersion = champion.schemaVersion,
            revision = champion.revision + 1L,
            configId = "cfg-$candidateId",
            parentConfigId = champion.configId
        )
        val championJson = champion.toJson().toString()
        val challengerJson = challenger.toJson().toString()
        val initial = EvolutionCandidate(
            candidateId = candidateId,
            kind = EvolutionCandidateKind.CONFIG,
            title = title.trim().ifBlank { "Configuration candidate" }.take(240),
            rationale = rationale.trim().ifBlank {
                "Expérience de configuration proposée sans justification détaillée."
            }.take(2_000),
            status = EvolutionCandidateStatus.CANDIDATE,
            championConfigId = champion.configId,
            challengerConfigId = challenger.configId,
            championConfigJson = championJson,
            challengerConfigJson = challengerJson,
            baselineRuntimeEvidence = currentRuntimeEvidence(champion),
            transitions = listOf(
                EvolutionTransition(
                    status = EvolutionCandidateStatus.CANDIDATE,
                    at = now,
                    note = "Candidat créé ; aucune configuration active n'a été modifiée."
                )
            ),
            createdAt = now,
            updatedAt = now
        )
        store.upsert(initial)

        val sandbox = EvolutionSandbox.validateConfigCandidate(champion, challenger)
        val nextStatus = if (sandbox.passed) {
            EvolutionCandidateStatus.PROPOSED
        } else {
            EvolutionCandidateStatus.REJECTED
        }
        val note = if (sandbox.passed) {
            "Contrôles statiques réussis ; le challenger peut entrer en sandbox."
        } else {
            "Candidat rejeté par la sandbox statique : ${sandbox.errors.joinToString(" | ").take(500)}"
        }
        return transition(
            initial.copy(sandbox = sandbox),
            nextStatus,
            note,
            lastError = sandbox.errors.joinToString(" | ")
                .takeIf { !sandbox.passed }
                ?.take(500)
        )
    }

    @Synchronized
    fun beginSandboxTrial(candidateId: String): EvolutionSandboxPlan {
        val candidate = requireCandidate(candidateId)
        require(candidate.status == EvolutionCandidateStatus.PROPOSED) {
            "Le candidat doit être PROPOSED avant un essai sandbox."
        }
        val champion = parseConfig(candidate.championConfigJson)
        val challenger = parseConfig(candidate.challengerConfigJson)
        val sandbox = EvolutionSandbox.validateConfigCandidate(champion, challenger)
        if (!sandbox.passed) {
            transition(
                candidate.copy(sandbox = sandbox),
                EvolutionCandidateStatus.REJECTED,
                "La revalidation sandbox a échoué.",
                lastError = sandbox.errors.joinToString(" | ").take(500)
            )
            error("Le challenger ne passe plus les garde-fous sandbox.")
        }

        val now = System.currentTimeMillis()
        val testing = transition(
            candidate.copy(sandbox = sandbox),
            EvolutionCandidateStatus.TESTING,
            "Essai sandbox préparé ; le champion actif reste inchangé."
        )
        val scenarioSetId =
            "sandbox:${testing.candidateId}:${now}:${UUID.randomUUID().toString().take(8)}"
        return EvolutionSandboxPlan(
            candidateId = testing.candidateId,
            scenarioSetId = scenarioSetId,
            championConfigJson = testing.championConfigJson,
            challengerConfigJson = testing.challengerConfigJson,
            championConfigSha256 = sandbox.championConfigSha256,
            challengerConfigSha256 = sandbox.challengerConfigSha256,
            baselineRuntimeEvidence = testing.baselineRuntimeEvidence,
            createdAt = now
        )
    }

    @Synchronized
    fun recordPairedSandboxEvaluation(
        candidateId: String,
        scenarioSetId: String,
        baselineObservations: List<RuntimeEvalObservation>,
        challengerObservations: List<RuntimeEvalObservation>
    ): EvolutionCandidate {
        val candidate = requireCandidate(candidateId)
        require(candidate.status == EvolutionCandidateStatus.TESTING) {
            "Le candidat doit être TESTING pour recevoir des résultats sandbox."
        }
        require(scenarioSetId.isNotBlank()) { "scenarioSetId vide." }
        val champion = parseConfig(candidate.championConfigJson)
        val challenger = parseConfig(candidate.challengerConfigJson)
        val paired = EvolutionPolicy.pairedScenarioCompatible(
            baselineObservations,
            challengerObservations
        )

        val baselineReport = RuntimeEvalEngine.report(
            observations = baselineObservations,
            routing = champion.routing
        )
        val challengerReport = RuntimeEvalEngine.report(
            observations = challengerObservations,
            routing = challenger.routing
        )
        val baselineEvidence = EvolutionPolicy.evidence(
            report = baselineReport,
            source = "SANDBOX_CHAMPION",
            scenarioSetId = scenarioSetId,
            evidenceSha256 = EvolutionPolicy.evidenceSha256(baselineObservations)
        )
        val challengerEvidence = EvolutionPolicy.evidence(
            report = challengerReport,
            source = "SANDBOX_CHALLENGER",
            scenarioSetId = scenarioSetId,
            evidenceSha256 = EvolutionPolicy.evidenceSha256(challengerObservations)
        )
        val comparison = EvolutionPolicy.compare(
            baseline = baselineEvidence,
            challenger = challengerEvidence,
            pairedScenarioCompatible = paired
        )

        val withEvidence = candidate.copy(
            pairedBaselineEvidence = baselineEvidence,
            pairedChallengerEvidence = challengerEvidence,
            comparison = comparison,
            lastError = if (comparison.promotionEligible) null else comparison.reason
        )

        return when {
            !paired -> transition(
                withEvidence,
                EvolutionCandidateStatus.REJECTED,
                comparison.reason,
                lastError = comparison.reason
            )

            !comparison.enoughEvidence || !comparison.confidenceSatisfied ->
                transition(
                    withEvidence,
                    EvolutionCandidateStatus.TESTING,
                    "Résultats enregistrés mais preuves encore insuffisantes ; aucun changement actif.",
                    lastError = comparison.reason
                )

            comparison.promotionEligible -> transition(
                withEvidence,
                EvolutionCandidateStatus.VALIDATED,
                comparison.reason,
                lastError = null
            )

            else -> transition(
                withEvidence,
                EvolutionCandidateStatus.REJECTED,
                comparison.reason,
                lastError = comparison.reason
            )
        }
    }

    @Synchronized
    fun promote(
        candidateId: String,
        userApproved: Boolean
    ): EvolutionCandidate {
        require(userApproved) {
            "Une promotion Evolution Engine exige une approbation utilisateur explicite."
        }
        val candidate = requireCandidate(candidateId)
        require(candidate.status == EvolutionCandidateStatus.VALIDATED) {
            "Seul un challenger VALIDATED peut être promu."
        }
        require(candidate.comparison?.promotionEligible == true) {
            "Le challenger ne satisfait pas la politique objective de promotion."
        }

        val activeChampion = JadeConfigRuntime.current().validated()
        require(activeChampion.configId == candidate.championConfigId) {
            "Le champion actif a changé depuis l'expérience ; candidat devenu obsolète."
        }
        val frozenChampion = parseConfig(candidate.championConfigJson)
        require(
            EvolutionPolicy.sha256(activeChampion.toJson().toString()) ==
                EvolutionPolicy.sha256(frozenChampion.toJson().toString())
        ) {
            "Le champion actif ne correspond plus à l'instantané gelé."
        }
        val challenger = parseConfig(candidate.challengerConfigJson)
        val sandbox = EvolutionSandbox.validateConfigCandidate(
            activeChampion,
            challenger
        )
        require(sandbox.passed) {
            "Le challenger ne passe plus SafetyPolicy."
        }

        JadeConfigRuntime.promote(appContext, challenger)
        val now = System.currentTimeMillis()
        return transition(
            candidate.copy(
                sandbox = sandbox,
                promotedAt = now,
                lastError = null
            ),
            EvolutionCandidateStatus.PROMOTED,
            "Challenger promu après validation appariée et approbation utilisateur.",
            lastError = null,
            at = now
        )
    }

    @Synchronized
    fun rollback(
        candidateId: String,
        reason: String
    ): EvolutionCandidate {
        val candidate = requireCandidate(candidateId)
        require(candidate.status == EvolutionCandidateStatus.PROMOTED) {
            "Seul le challenger actuellement PROMOTED peut être rollback."
        }
        val active = JadeConfigRuntime.current().validated()
        require(active.configId == candidate.challengerConfigId) {
            "Le challenger n'est plus la configuration active ; rollback direct refusé."
        }
        require(
            EvolutionPolicy.sha256(active.toJson().toString()) ==
                EvolutionPolicy.sha256(candidate.challengerConfigJson)
        ) {
            "La configuration active diffère du challenger gelé ; rollback direct refusé."
        }

        val previousChampion = parseConfig(candidate.championConfigJson)
        val rollbackConfig = previousChampion.copy(
            revision = active.revision + 1L,
            configId = "rollback-${candidate.candidateId}-${UUID.randomUUID().toString().take(8)}",
            parentConfigId = active.configId
        ).validated()
        JadeConfigRuntime.promote(appContext, rollbackConfig)
        val now = System.currentTimeMillis()
        return transition(
            candidate.copy(
                rolledBackAt = now,
                lastError = reason.trim().take(500).takeIf { it.isNotBlank() }
            ),
            EvolutionCandidateStatus.ROLLED_BACK,
            "Rollback vers le comportement du champion précédent : ${reason.trim().take(350)}",
            lastError = reason.trim().take(500).takeIf { it.isNotBlank() },
            at = now
        )
    }

    @Synchronized
    fun reject(candidateId: String, reason: String): EvolutionCandidate {
        val candidate = requireCandidate(candidateId)
        require(
            candidate.status !in setOf(
                EvolutionCandidateStatus.PROMOTED,
                EvolutionCandidateStatus.ROLLED_BACK
            )
        ) {
            "Un candidat déjà promu/rollback ne peut pas être simplement rejeté."
        }
        val cleanReason = reason.trim().ifBlank { "Rejet manuel." }.take(500)
        return transition(
            candidate,
            EvolutionCandidateStatus.REJECTED,
            cleanReason,
            lastError = cleanReason
        )
    }

    private fun currentRuntimeEvidence(
        champion: JadeConfig
    ): EvolutionEvidenceSnapshot? {
        val runtime = RuntimeEvalRuntime.currentOrNull() ?: return null
        val observations = runtime.recent(SafetyPolicy.MAX_RUNTIME_EVAL_REPORT_WINDOW)
        if (observations.isEmpty()) return null
        val report = RuntimeEvalEngine.report(
            observations = observations,
            routing = champion.routing
        )
        val evidenceHash = EvolutionPolicy.evidenceSha256(observations)
        return EvolutionPolicy.evidence(
            report = report,
            source = "LIVE_CHAMPION_SNAPSHOT",
            scenarioSetId = "runtime:${evidenceHash.take(16)}",
            evidenceSha256 = evidenceHash
        )
    }

    private fun parseConfig(raw: String): JadeConfig =
        JadeConfig.fromJson(JSONObject(raw)).validated()

    private fun requireCandidate(candidateId: String): EvolutionCandidate =
        store.get(candidateId) ?: error("Candidat Evolution inconnu : $candidateId")

    private fun transition(
        candidate: EvolutionCandidate,
        status: EvolutionCandidateStatus,
        note: String,
        lastError: String? = candidate.lastError,
        at: Long = System.currentTimeMillis()
    ): EvolutionCandidate {
        val event = EvolutionTransition(
            status = status,
            at = at,
            note = note.take(500)
        )
        val updated = candidate.copy(
            status = status,
            transitions = (candidate.transitions + event)
                .takeLast(SafetyPolicy.MAX_EVOLUTION_TRANSITIONS_PER_CANDIDATE),
            updatedAt = at,
            lastError = lastError
        )
        store.upsert(updated)
        return updated
    }
}

object EvolutionRuntime {
    @Volatile
    private var engine: EvolutionEngine? = null

    fun initialize(context: Context): EvolutionEngine = synchronized(this) {
        engine ?: EvolutionEngine(context.applicationContext).also { engine = it }
    }

    fun currentOrNull(): EvolutionEngine? = engine

    fun current(): EvolutionEngine =
        engine ?: error("Evolution Engine n'est pas initialisé.")
}