package com.jadegenesis.mobile

import com.jadegenesis.mobile.cognitive.ConversationFeedbackKind
import com.jadegenesis.mobile.cognitive.ConversationLearningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLearningTest {

    @Test
    fun explicitFailureWinsOverPositiveSubstring() {
        val signal = ConversationLearningPolicy.classifyFeedback(
            "Non, ça marche pas encore avec ce shader Android."
        ) ?: error("Signal attendu")

        assertEquals(ConversationFeedbackKind.NEGATIVE, signal.kind)
        assertTrue(signal.confidence >= 0.9)
    }

    @Test
    fun explicitCorrectionIsRecordedAsCorrection() {
        val signal = ConversationLearningPolicy.classifyFeedback(
            "Non c'est Vulkan qui pose le problème ici, pas OpenGL."
        ) ?: error("Signal attendu")

        assertEquals(ConversationFeedbackKind.CORRECTION, signal.kind)
    }

    @Test
    fun explicitSuccessAcceptsCommonFrenchFormsAndPunctuation() {
        listOf(
            "Parfait, ça marche maintenant.",
            "Ça marche.",
            "ça a marché !",
            "c'est résolu",
            "ça marche :)",
            "nickel",
            "super"
        ).forEach { text ->
            val signal = ConversationLearningPolicy.classifyFeedback(text)
                ?: error("Signal positif attendu pour: $text")
            assertEquals(ConversationFeedbackKind.POSITIVE, signal.kind)
        }
    }

    @Test
    fun questionsContainingFeedbackWordsAreNeverClassified() {
        listOf(
            "est-ce que ça marche pas sur Android 12 ?",
            "si ça marche pas, qu'est-ce que je dois regarder ?",
            "Comment ça marche Vulkan sur Android ?",
            "Pourquoi ce n'est pas disponible sur Android ?",
            "résolu ? pas vraiment"
        ).forEach { text ->
            assertNull(text, ConversationLearningPolicy.classifyFeedback(text))
        }
    }

    @Test
    fun embeddedOrAmbiguousPhrasesDoNotPretendToBeFeedback() {
        listOf(
            "ok",
            "d'accord",
            "je ne connais pas du tout ce framework",
            "ça ne me dérange pas du tout",
            "en fait, j'ai une autre question sur Godot",
            "il fallait que je te demande un truc",
            "explique-moi pourquoi tu te trompes parfois",
            "Quelle est la valeur exacte de ce paramètre ?",
            "parfait, sauf que le build casse",
            "c'est bon à savoir mais je crois que c'est faux",
            "super bizarre ce comportement",
            "nickel chrome ton truc",
            "super Mario Bros"
        ).forEach { text ->
            assertNull(text, ConversationLearningPolicy.classifyFeedback(text))
        }
    }

    @Test
    fun explicitNegativeTailOverridesPositiveLeadInWhenTailIsDirect() {
        val signal = ConversationLearningPolicy.classifyFeedback(
            "super, mais ça marche pas"
        ) ?: error("Signal négatif attendu")

        assertEquals(ConversationFeedbackKind.NEGATIVE, signal.kind)
    }

    @Test
    fun feedbackWordsDoNotBecomeRecurringTopics() {
        assertTrue(ConversationLearningPolicy.extractTopics("ça marche pas").isEmpty())
        assertTrue(ConversationLearningPolicy.extractTopics("c'est faux").isEmpty())
        assertTrue(ConversationLearningPolicy.extractTopics("tu te trompes").isEmpty())
        assertTrue(ConversationLearningPolicy.extractTopics("toujours pas").isEmpty())
    }

    @Test
    fun topicsKeepTechnicalSubjectsAndDropGenericWords() {
        val topics = ConversationLearningPolicy.extractTopics(
            "Comment optimiser les shaders Godot pour Android avec Vulkan sur notre projet ?"
        )

        assertTrue("godot" in topics)
        assertTrue("android" in topics)
        assertTrue("shaders" in topics)
        assertTrue("vulkan" in topics)
        assertTrue("comment" !in topics)
        assertTrue("notre" !in topics)
    }

    @Test
    fun recurringTopicMilestonesAreDiscreteAndStable() {
        assertNull(ConversationLearningPolicy.crossedMilestone(1, 2))
        assertEquals(3, ConversationLearningPolicy.crossedMilestone(2, 3))
        assertNull(ConversationLearningPolicy.crossedMilestone(3, 5))
        assertEquals(6, ConversationLearningPolicy.crossedMilestone(5, 6))
        assertEquals(12, ConversationLearningPolicy.crossedMilestone(11, 12))
        assertEquals(24, ConversationLearningPolicy.crossedMilestone(23, 24))
    }
}
