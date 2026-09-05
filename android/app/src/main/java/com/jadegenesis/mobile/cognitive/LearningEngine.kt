package com.jadegenesis.mobile.cognitive

import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.LearningCandidate
import java.security.MessageDigest

class LearningEngine {
    fun candidates(
        history: List<DistributedTaskResult>,
        limit: Int = 8
    ): List<LearningCandidate> {
        val attempts = history.flatMap { result ->
            result.attempts.map { attempt ->
                Triple(result.taskKind, result.completedAt, attempt)
            }
        }

        val grouped = attempts.groupBy { (taskKind, _, attempt) ->
            "${attempt.nodeId}|$taskKind"
        }

        return grouped.mapNotNull { (key, values) ->
            if (values.size < 3) return@mapNotNull null

            val taskKind = values.first().first
            val node = values.first().third
            val failures = values.count { !it.third.success }
            val successes = values.size - failures
            val failureRate = failures.toDouble() / values.size.toDouble()
            val successfulDurations = values
                .map { it.third }
                .filter { it.success && it.durationMs > 0L }
                .map { it.durationMs.toDouble() }
            val averageMs = successfulDurations.takeIf { it.isNotEmpty() }?.average()

            when {
                failureRate >= 0.34 -> LearningCandidate(
                    id = stableId(key + ":failure"),
                    title = "Fiabilité à améliorer — ${node.nodeName}",
                    description =
                        "Pour $taskKind, ce nœud échoue assez souvent pour justifier une stratégie candidate de dépriorisation ou de changement de route.",
                    evidence = "$failures échec(s) / ${values.size} tentative(s), $successes succès.",
                    confidence = (0.55 + failureRate * 0.4).coerceAtMost(0.95),
                    createdAt = values.maxOf { it.second }
                )

                averageMs != null && averageMs >= 5_000.0 -> LearningCandidate(
                    id = stableId(key + ":latency"),
                    title = "Latence élevée — ${node.nodeName}",
                    description =
                        "Pour $taskKind, Jade peut tester une autre route ou un autre nœud avant de modifier sa préférence stable.",
                    evidence = "Moyenne mesurée : ${averageMs.toLong()} ms sur ${successfulDurations.size} succès.",
                    confidence = 0.72,
                    createdAt = values.maxOf { it.second }
                )

                else -> null
            }
        }
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
