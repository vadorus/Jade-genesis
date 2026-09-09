package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext

enum class CognitiveBrainProfile {
    FAST,
    GENERAL,
    REASONING,
    CODE,
    CRITIC
}

data class CognitiveBrainPlan(
    val profile: CognitiveBrainProfile,
    val reason: String,
    val desiredContextTokens: Int,
    val temperature: Double
)

object CognitiveBrainPolicy {
    fun plan(context: BrainContext): CognitiveBrainPlan {
        val operation = context.operation.trim().lowercase()
        return when (operation) {
            "verify" -> plan(
                CognitiveBrainProfile.CRITIC,
                "Passe de vérification : priorité à la critique et à la stabilité.",
                8_192,
                0.05
            )

            "revise" -> plan(
                CognitiveBrainProfile.REASONING,
                "Passe de révision : raisonnement renforcé sur une réponse existante.",
                12_288,
                0.15
            )

            "tool_build" -> plan(
                CognitiveBrainProfile.CODE,
                "Tool Lab : privilégier un modèle spécialisé code.",
                12_288,
                0.10
            )

            else -> planForAnswer(context.userInput)
        }
    }

    private fun planForAnswer(input: String): CognitiveBrainPlan {
        val normalized = input.lowercase()
        val codeTerms = listOf(
            "code",
            "kotlin",
            "python",
            "java",
            "gradle",
            "github",
            "fonction",
            "classe",
            "script",
            "compile",
            "bug"
        )
        val reasoningTerms = listOf(
            "analyse",
            "architecture",
            "compare",
            "diagnostic",
            "optimise",
            "pourquoi",
            "plan",
            "stratégie",
            "strategie",
            "risque",
            "raisonnement",
            "vérifie",
            "verifie"
        )

        if (codeTerms.any { it in normalized }) {
            return plan(
                CognitiveBrainProfile.CODE,
                "La requête est principalement technique ou liée au code.",
                12_288,
                0.10
            )
        }
        if (input.length >= 480 || reasoningTerms.any { it in normalized }) {
            return plan(
                CognitiveBrainProfile.REASONING,
                "La requête demande une analyse plus profonde ou comporte beaucoup de contraintes.",
                12_288,
                0.15
            )
        }
        if (input.length <= 90 && !input.contains('\n')) {
            return plan(
                CognitiveBrainProfile.FAST,
                "Requête courte pouvant être traitée par un cerveau rapide et économique.",
                4_096,
                0.20
            )
        }
        return plan(
            CognitiveBrainProfile.GENERAL,
            "Requête conversationnelle générale : équilibre qualité, contexte et ressources.",
            8_192,
            0.30
        )
    }

    private fun plan(
        profile: CognitiveBrainProfile,
        reason: String,
        desiredContextTokens: Int,
        temperature: Double
    ) = CognitiveBrainPlan(
        profile = profile,
        reason = reason,
        desiredContextTokens = desiredContextTokens,
        temperature = temperature
    )
}
