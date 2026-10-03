package com.jadegenesis.mobile.cognitive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RightHandLearningPolicyTest {
    @Test
    fun explicitPreferenceIsLearnedAsUserPreference() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Je préfère des réponses courtes avec les points importants en premier."
        ) ?: error("Préférence attendue")

        assertEquals(ExplicitPreferenceKind.PREFERENCE, signal.kind)
        assertTrue(signal.confidence >= 0.90)
        assertTrue(signal.statement.contains("réponses courtes"))
    }

    @Test
    fun ordinaryEmotionalStatementIsNotPromotedToPreference() {
        assertNull(
            RightHandLearningPolicy.explicitPreference(
                "Je suis fatigué aujourd'hui et j'ai moins le moral."
            )
        )
    }

    @Test
    fun lowMoodCreatesGenericHumanKnowledgeQuestion() {
        val need = RightHandLearningPolicy.humanKnowledgeNeed(
            "Je n'ai pas trop le moral aujourd'hui."
        ) ?: error("Besoin de connaissance attendu")

        assertEquals(HumanKnowledgeDomain.LOW_MOOD, need.domain)
        assertTrue(need.researchQuestion.contains("low mood"))
        assertFalse(need.researchQuestion.contains("Alexandre", ignoreCase = true))
        assertFalse(need.researchQuestion.contains("aujourd'hui", ignoreCase = true))
    }

    @Test
    fun explicitAvoidanceHasPriorityOverGenericPreference() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Je ne veux pas que tu me caches les mauvaises nouvelles, je préfère les voir clairement."
        ) ?: error("Préférence attendue")

        assertEquals(ExplicitPreferenceKind.AVOIDANCE, signal.kind)
        assertTrue(signal.confidence >= 0.94)
    }
}
