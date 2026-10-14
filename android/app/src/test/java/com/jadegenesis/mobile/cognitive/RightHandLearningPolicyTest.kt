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
    fun generalReplyPreferenceFollowsUserAcrossTopics() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Je préfère des réponses courtes avec les points importants en premier."
        ) ?: error("Préférence attendue")
        assertTrue(RightHandLearningPolicy.isGlobalReplyPreference(signal.kind, signal.statement))
    }

    @Test
    fun topicSpecificReplyPreferenceIsNotGlobal() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Je préfère des réponses courtes pour mon projet Roblox."
        ) ?: error("Préférence attendue")
        assertFalse(RightHandLearningPolicy.isGlobalReplyPreference(signal.kind, signal.statement))
    }

    @Test
    fun aWishToCreateAProjectIsNotPersonalPreference() {
        assertNull(RightHandLearningPolicy.explicitPreference(
            "Je souhaite créer un nouveau jeu Roblox."
        ))
    }

    @Test
    fun oneOffActionRequestIsNotPersistedAsPreference() {
        assertNull(RightHandLearningPolicy.explicitPreference(
            "Je veux que tu corriges le code de mon jeu Roblox."
        ))
        assertFalse(RightHandLearningPolicy.isValidStoredStylePreference(
            ExplicitPreferenceKind.REQUEST_STYLE,
            "Je veux que tu corriges le code de mon jeu Roblox."
        ))
    }

    @Test
    fun explicitReplyStyleInstructionIsPersisted() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Quand tu me réponds, sois concis."
        ) ?: error("Style attendu")
        assertEquals(ExplicitPreferenceKind.REQUEST_STYLE, signal.kind)
        assertTrue(RightHandLearningPolicy.isGlobalReplyPreference(signal.kind, signal.statement))
    }

    @Test
    fun generalAvoidanceAppliesAcrossTopics() {
        val signal = RightHandLearningPolicy.explicitPreference(
            "Je ne veux pas que tu te répètes dans tes réponses."
        ) ?: error("Préférence attendue")
        assertTrue(RightHandLearningPolicy.isGlobalReplyPreference(signal.kind, signal.statement))
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
