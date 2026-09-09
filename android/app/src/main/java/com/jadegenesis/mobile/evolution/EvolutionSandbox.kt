package com.jadegenesis.mobile.evolution

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.ResourceMode
import org.json.JSONArray
import org.json.JSONObject

object EvolutionSandbox {
    fun validateConfigCandidate(
        champion: JadeConfig,
        challenger: JadeConfig
    ): EvolutionSandboxResult {
        val checks = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val championValidated = runCatching { champion.validated() }
        val challengerValidated = runCatching { challenger.validated() }
        if (championValidated.isFailure) {
            errors += "Le champion actif est invalide : ${championValidated.exceptionOrNull()?.message.orEmpty()}"
        } else {
            checks += "Champion JadeConfig valide."
        }
        if (challengerValidated.isFailure) {
            errors += "Le challenger JadeConfig est invalide : ${challengerValidated.exceptionOrNull()?.message.orEmpty()}"
        } else {
            checks += "Challenger JadeConfig valide."
        }

        if (challenger.schemaVersion != champion.schemaVersion) {
            errors += "Le challenger ne peut pas changer le schéma JadeConfig."
        } else {
            checks += "Schéma JadeConfig inchangé."
        }
        if (challenger.configId.isBlank() || challenger.configId == champion.configId) {
            errors += "Le challenger doit posséder un nouvel identifiant de configuration."
        } else {
            checks += "Identifiant challenger distinct."
        }
        if (challenger.parentConfigId != champion.configId) {
            errors += "Le parent du challenger doit être le champion actif."
        } else {
            checks += "Lignée champion → challenger vérifiée."
        }
        if (challenger.revision != champion.revision + 1L) {
            errors += "Une expérience doit avancer exactement d'une révision."
        } else {
            checks += "Révision challenger monotone."
        }

        val championJson = champion.toJson().toString()
        val challengerJson = challenger.toJson().toString()
        if (challengerJson.length > SafetyPolicy.MAX_EVOLUTION_CONFIG_JSON_CHARS) {
            errors += "Configuration challenger trop volumineuse."
        } else {
            checks += "Taille de configuration bornée."
        }

        ResourceMode.values().forEach { mode ->
            val budget = challenger.resource.mode(mode)
            if (budget.systemFraction > SafetyPolicy.MAX_SYSTEM_BUDGET_FRACTION) {
                errors += "$mode demande trop de budget RAM système."
            }
            if (budget.heapFraction > SafetyPolicy.MAX_HEAP_BUDGET_FRACTION) {
                errors += "$mode demande trop de heap."
            }
            if (budget.appClassFraction > SafetyPolicy.MAX_APP_CLASS_BUDGET_FRACTION) {
                errors += "$mode demande trop de mémoire de classe d'application."
            }
            if (budget.maxParallelTasks > SafetyPolicy.MAX_PARALLEL_TASKS) {
                errors += "$mode demande trop de tâches parallèles."
            }
            if (budget.maxTaskSliceSeconds > SafetyPolicy.MAX_TASK_SLICE_SECONDS) {
                errors += "$mode demande une tranche d'exécution trop longue."
            }
        }
        if (errors.none { it.contains("demande") }) {
            checks += "Budgets évolutifs contenus dans les plafonds SafetyPolicy."
        }

        val retention = challenger.retention
        if (
            retention.ephemeralMemoryRetentionDays <
            SafetyPolicy.MIN_EPHEMERAL_MEMORY_RETENTION_DAYS
        ) {
            errors += "La rétention éphémère descend sous le plancher SafetyPolicy."
        }
        if (
            retention.supersededMemoryRetentionDays <
            SafetyPolicy.MIN_SUPERSEDED_MEMORY_RETENTION_DAYS
        ) {
            errors += "La rétention des mémoires remplacées descend sous le plancher SafetyPolicy."
        }
        if (
            retention.memoryRecallProtectionCount <
            SafetyPolicy.MIN_RECALL_PROTECTION_COUNT
        ) {
            errors += "La protection par rappel descend sous le plancher SafetyPolicy."
        }
        if (
            retention.memoryLowConfidenceThreshold >
            SafetyPolicy.MAX_AUTO_DELETE_CONFIDENCE
        ) {
            errors += "Le seuil de suppression automatique dépasse SafetyPolicy."
        }
        if (
            retention.memoryPurgeBatchSize >
            SafetyPolicy.MAX_MEMORY_PURGE_BATCH_SIZE
        ) {
            errors += "Le lot de purge dépasse SafetyPolicy."
        }
        if (errors.none { it.contains("rétention", ignoreCase = true) || it.contains("suppression", ignoreCase = true) || it.contains("purge", ignoreCase = true) || it.contains("rappel", ignoreCase = true) }) {
            checks += "Politique de rétention compatible avec SafetyPolicy."
        }

        val changedFields = changedTuningFields(
            champion.toJson(),
            challenger.toJson()
        )
        if (changedFields == 0) {
            errors += "Le challenger ne modifie aucun paramètre évolutif."
        } else if (changedFields > SafetyPolicy.MAX_EVOLUTION_CONFIG_CHANGED_FIELDS) {
            errors += "Trop de paramètres changent dans une seule expérience : $changedFields."
        } else {
            checks += "$changedFields paramètre(s) évolutif(s) modifié(s)."
        }

        val championHash = EvolutionPolicy.sha256(championJson)
        val challengerHash = EvolutionPolicy.sha256(challengerJson)
        if (championHash == challengerHash) {
            errors += "Champion et challenger ont la même empreinte."
        } else {
            checks += "Empreintes champion/challenger distinctes."
        }

        return EvolutionSandboxResult(
            passed = errors.isEmpty(),
            changedFields = changedFields,
            checks = checks.take(SafetyPolicy.MAX_EVOLUTION_SANDBOX_NOTES),
            errors = errors.take(SafetyPolicy.MAX_EVOLUTION_SANDBOX_NOTES),
            championConfigSha256 = championHash,
            challengerConfigSha256 = challengerHash
        )
    }

    private fun changedTuningFields(
        champion: JSONObject,
        challenger: JSONObject
    ): Int {
        val ignored = setOf(
            "schema_version",
            "revision",
            "config_id",
            "parent_config_id"
        )
        val championFlat = flatten(champion)
            .filterKeys { key -> key.substringBefore('.') !in ignored }
        val challengerFlat = flatten(challenger)
            .filterKeys { key -> key.substringBefore('.') !in ignored }
        return (championFlat.keys + challengerFlat.keys)
            .toSet()
            .count { key -> championFlat[key] != challengerFlat[key] }
    }

    private fun flatten(
        json: JSONObject,
        prefix: String = ""
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val keys = json.keys().asSequence().toList().sorted()
        keys.forEach { key ->
            val path = if (prefix.isBlank()) key else "$prefix.$key"
            when (val value = json.opt(key)) {
                is JSONObject -> result.putAll(flatten(value, path))
                is JSONArray -> result[path] = value.toString()
                JSONObject.NULL, null -> result[path] = "null"
                else -> result[path] = value.toString()
            }
        }
        return result
    }
}