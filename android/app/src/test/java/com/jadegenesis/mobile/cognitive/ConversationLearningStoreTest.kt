package com.jadegenesis.mobile.cognitive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLearningStoreTest {

    @Test
    fun unknownSchemaDoesNotReplaceExistingState() {
        val raw = """{"schema_version":99,"turns":[],"outcomes":[],"topics":[]}"""
        val storage = FakeConversationLearningStorage(
            mutableMapOf("state_v1" to raw)
        )
        val store = ConversationLearningStore(storage) { 1_000L }

        assertThrows(IllegalStateException::class.java) {
            store.beginUserMessage("Question Godot Vulkan")
        }

        assertEquals(raw, storage.getString("state_v1"))
        assertFalse(storage.values.containsKey("state_v1_backup"))
    }

    @Test
    fun feedbackDoesNotCreateANewTurnOrFeedbackTopic() {
        var now = 1_000_000L
        val storage = FakeConversationLearningStorage()
        val store = ConversationLearningStore(storage) { now }

        val originalQuestion = "Comment configurer Vulkan sur Android"
        store.beginUserMessage(originalQuestion)
        store.completeTurn(
            userInput = originalQuestion,
            answer = "Active Vulkan et vérifie la configuration du projet.",
            profile = "REASONING",
            backendId = "distributed-local-brain",
            nodeId = "pc-a",
            model = "test-model",
            runtimeEvalObservationId = "eval-response-123"
        )

        var state = JSONObject(storage.getString("state_v1") ?: error("État attendu"))
        assertEquals(1, state.getJSONArray("turns").length())
        assertEquals(0, state.getJSONArray("outcomes").length())

        now += 1_000L
        val update = store.beginUserMessage("ça marche pas")
        assertTrue(update.feedbackOnly)
        assertEquals(ConversationFeedbackKind.NEGATIVE, update.feedbackKind)
        assertTrue(update.feedbackOutcomeId.isNotBlank())
        assertEquals("eval-response-123", update.feedbackTargetObservationId)
        assertEquals("pc-a", update.feedbackTargetNodeId)
        assertEquals("REASONING", update.feedbackTargetProfile)
        assertEquals("test-model", update.feedbackTargetModel)

        store.completeTurn(
            userInput = "ça marche pas",
            answer = "Je vais chercher une autre approche.",
            profile = "GENERAL"
        )

        state = JSONObject(storage.getString("state_v1") ?: error("État attendu"))
        assertEquals(1, state.getJSONArray("turns").length())
        assertEquals(1, state.getJSONArray("outcomes").length())
        val savedOutcome = state.getJSONArray("outcomes").getJSONObject(0)
        assertEquals("eval-response-123", savedOutcome.getString("runtime_eval_observation_id"))
        assertEquals("pc-a", savedOutcome.getString("node_id"))

        val topics = state.getJSONArray("topics")
        val names = buildSet {
            for (index in 0 until topics.length()) {
                add(topics.getJSONObject(index).getString("topic"))
            }
        }
        assertFalse("marche" in names)
        assertFalse("faux" in names)
        assertTrue("vulkan" in names)
        assertTrue("android" in names)
    }

    @Test
    fun explicitFeedbackWithoutRecentTurnDoesNotBecomeStandaloneExperience() {
        val storage = FakeConversationLearningStorage()
        val store = ConversationLearningStore(storage) { 5_000_000L }

        val update = store.beginUserMessage("ça marche pas")
        assertTrue(update.feedbackOnly)
        assertNull(update.feedbackKind)

        store.completeTurn(
            userInput = "ça marche pas",
            answer = "Dis-moi ce qui échoue et je vais le diagnostiquer.",
            profile = "GENERAL",
            nodeId = "pc-a",
            runtimeEvalObservationId = "eval-current-feedback-answer"
        )

        assertNull(storage.getString("state_v1"))
    }

    @Test
    fun messageWithoutTopicInjectsNoConversationMemory() {
        var now = 2_000_000L
        val storage = FakeConversationLearningStorage()
        val store = ConversationLearningStore(storage) { now }

        val question = "Optimiser shaders Godot Vulkan Android"
        store.beginUserMessage(question)
        store.completeTurn(
            userInput = question,
            answer = "Utilise un shader plus simple et mesure le rendu.",
            profile = "REASONING"
        )

        now += 1_000L
        store.beginUserMessage("ok")

        assertTrue(store.contextMemories().isEmpty())
    }

    private class FakeConversationLearningStorage(
        val values: MutableMap<String, String> = linkedMapOf()
    ) : ConversationLearningStorage {
        override fun getString(key: String): String? = values[key]

        override fun putStrings(values: Map<String, String>) {
            this.values.putAll(values)
        }
    }
}
