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
    fun explicitSuccessIsPositiveOutcome() {
        val signal = ConversationLearningPolicy.classifyFeedback(
            "Parfait, ça marche maintenant."
        ) ?: error("Signal attendu")

        assertEquals(ConversationFeedbackKind.POSITIVE, signal.kind)
    }

    @Test
    fun weakOrAmbiguousPhrasesDoNotPretendToValidateAnswer() {
        assertNull(ConversationLearningPolicy.classifyFeedback("ok"))
        assertNull(ConversationLearningPolicy.classifyFeedback("d'accord"))
        assertNull(
            ConversationLearningPolicy.classifyFeedback(
                "Quelle est la valeur exacte de ce paramètre ?"
            )
        )
        assertNull(
            ConversationLearningPolicy.classifyFeedback(
                "Pourquoi ce n'est pas disponible sur Android ?"
            )
        )
        assertNull(
            ConversationLearningPolicy.classifyFeedback(
                "Comment ça marche Vulkan sur Android ?"
            )
        )
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
