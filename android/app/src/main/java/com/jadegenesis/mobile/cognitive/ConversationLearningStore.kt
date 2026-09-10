package com.jadegenesis.mobile.cognitive

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MemoryType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

private data class ConversationTurn(
    val id: String,
    val userExcerpt: String,
    val answerExcerpt: String,
    val topics: List<String>,
    val profile: String,
    val backendId: String,
    val model: String,
    val fallbackUsed: Boolean,
    val createdAt: Long
)

private data class ConversationOutcome(
    val id: String,
    val turnId: String,
    val kind: ConversationFeedbackKind,
    val confidence: Double,
    val feedbackExcerpt: String,
    val topics: List<String>,
    val profile: String,
    val model: String,
    val createdAt: Long
)

private data class TopicStat(
    val topic: String,
    val count: Int,
    val positive: Int,
    val negative: Int,
    val corrections: Int,
    val lastSeenAt: Long
)

private data class ConversationLearningState(
    val turns: List<ConversationTurn> = emptyList(),
    val outcomes: List<ConversationOutcome> = emptyList(),
    val topics: List<TopicStat> = emptyList()
)

data class ConversationLearningUpdate(
    val topics: List<String>,
    val feedbackKind: ConversationFeedbackKind? = null,
    val feedbackConfidence: Double = 0.0,
    val crossedTopicMilestones: Map<String, Int> = emptyMap()
)

class ConversationLearningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    @Volatile
    private var pendingInput: String? = null

    fun beginUserMessage(input: String): ConversationLearningUpdate = synchronized(lock) {
        val clean = input.trim().take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS)
        if (clean.isBlank()) {
            pendingInput = null
            return@synchronized ConversationLearningUpdate(emptyList())
        }

        pendingInput = clean
        val now = System.currentTimeMillis()
        val state = loadUnsafe()
        val currentTopics = ConversationLearningPolicy.extractTopics(
            clean,
            SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN
        )
        val topicMap = state.topics.associateBy { it.topic }.toMutableMap()
        val crossed = linkedMapOf<String, Int>()

        currentTopics.forEach { topic ->
            val before = topicMap[topic] ?: TopicStat(topic, 0, 0, 0, 0, 0L)
            val afterCount = before.count + 1
            ConversationLearningPolicy.crossedMilestone(before.count, afterCount)
                ?.let { milestone -> crossed[topic] = milestone }
            topicMap[topic] = before.copy(
                count = afterCount,
                lastSeenAt = now
            )
        }

        val lastTurn = state.turns.firstOrNull()
            ?.takeIf { now - it.createdAt <= MAX_FEEDBACK_WINDOW_MS }
        val feedback = lastTurn?.let {
            ConversationLearningPolicy.classifyFeedback(clean)
        }

        var outcomes = state.outcomes
        if (lastTurn != null && feedback != null) {
            val feedbackTopics = lastTurn.topics.ifEmpty { currentTopics }
            feedbackTopics.forEach { topic ->
                val before = topicMap[topic] ?: TopicStat(topic, 0, 0, 0, 0, 0L)
                topicMap[topic] = when (feedback.kind) {
                    ConversationFeedbackKind.POSITIVE -> before.copy(
                        positive = before.positive + 1,
                        lastSeenAt = now
                    )
                    ConversationFeedbackKind.NEGATIVE -> before.copy(
                        negative = before.negative + 1,
                        lastSeenAt = now
                    )
                    ConversationFeedbackKind.CORRECTION -> before.copy(
                        corrections = before.corrections + 1,
                        lastSeenAt = now
                    )
                }
            }

            val outcome = ConversationOutcome(
                id = "outcome-${UUID.randomUUID()}",
                turnId = lastTurn.id,
                kind = feedback.kind,
                confidence = feedback.confidence,
                feedbackExcerpt = clean,
                topics = feedbackTopics.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN),
                profile = lastTurn.profile,
                model = lastTurn.model,
                createdAt = now
            )
            outcomes = (listOf(outcome) + outcomes)
                .distinctBy { it.id }
                .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_OUTCOMES)
        }

        saveUnsafe(
            state.copy(
                outcomes = outcomes,
                topics = topicMap.values
                    .sortedWith(
                        compareByDescending<TopicStat> { it.lastSeenAt }
                            .thenByDescending { it.count }
                            .thenBy { it.topic }
                    )
                    .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS)
            )
        )

        ConversationLearningUpdate(
            topics = currentTopics,
            feedbackKind = feedback?.kind,
            feedbackConfidence = feedback?.confidence ?: 0.0,
            crossedTopicMilestones = crossed
        )
    }

    fun completeTurn(
        userInput: String,
        answer: String,
        profile: String,
        backendId: String = "",
        model: String = "",
        fallbackUsed: Boolean = false
    ) = synchronized(lock) {
        val cleanUser = userInput.trim().take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS)
        val cleanAnswer = answer.trim().take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS)
        if (cleanUser.isBlank() || cleanAnswer.isBlank()) {
            pendingInput = null
            return@synchronized
        }

        val state = loadUnsafe()
        val topics = ConversationLearningPolicy.extractTopics(
            cleanUser,
            SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN
        )
        val turn = ConversationTurn(
            id = "turn-${UUID.randomUUID()}",
            userExcerpt = cleanUser,
            answerExcerpt = cleanAnswer,
            topics = topics,
            profile = profile.trim().take(40),
            backendId = backendId.trim().take(120),
            model = model.trim().take(160),
            fallbackUsed = fallbackUsed,
            createdAt = System.currentTimeMillis()
        )
        saveUnsafe(
            state.copy(
                turns = (listOf(turn) + state.turns)
                    .distinctBy { it.id }
                    .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TURNS)
            )
        )
        pendingInput = null
    }

    fun cancelPending() {
        pendingInput = null
    }

    fun contextMemories(limit: Int = 5): List<MemorySnapshot> = synchronized(lock) {
        val input = pendingInput.orEmpty()
        if (input.isBlank()) return@synchronized emptyList()

        val safeLimit = limit.coerceIn(1, SafetyPolicy.MAX_CONVERSATION_LEARNING_CONTEXT_ITEMS)
        val now = System.currentTimeMillis()
        val state = loadUnsafe()
        val currentTopics = ConversationLearningPolicy.extractTopics(
            input,
            SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN
        ).toSet()
        val output = mutableListOf<MemorySnapshot>()

        val relevantOutcomes = state.outcomes
            .filter { outcome ->
                currentTopics.isEmpty() || outcome.topics.any { it in currentTopics }
            }
            .take(2)
        relevantOutcomes.forEach { outcome ->
            output += MemorySnapshot(
                id = "conversation:${outcome.id}",
                type = MemoryType.EXPERIENCE.name,
                content = when (outcome.kind) {
                    ConversationFeedbackKind.POSITIVE ->
                        "Retour utilisateur positif sur une réponse liée à ${topicLabel(outcome.topics)}. La stratégie précédente a été explicitement indiquée comme fonctionnelle ou correcte."
                    ConversationFeedbackKind.NEGATIVE ->
                        "Retour utilisateur négatif sur une réponse liée à ${topicLabel(outcome.topics)}. Ne considère pas la stratégie précédente comme validée ; cherche une autre approche et vérifie davantage."
                    ConversationFeedbackKind.CORRECTION ->
                        "Correction utilisateur liée à ${topicLabel(outcome.topics)} : ${outcome.feedbackExcerpt}. Traite cette correction comme un signal utilisateur durable mais distingue-la d'une preuve externe vérifiée."
                }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                source = "CONVERSATION_OUTCOME_LOCAL",
                confidence = outcome.confidence.coerceIn(0.0, 1.0),
                createdAt = outcome.createdAt,
                originNode = "phone-conversation"
            )
        }

        val relevantTurns = state.turns
            .filter { turn ->
                currentTopics.isEmpty() || turn.topics.any { it in currentTopics }
            }
            .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_CONTEXT_TURNS)
        relevantTurns.forEach { turn ->
            output += MemorySnapshot(
                id = "conversation:${turn.id}",
                type = MemoryType.EXPERIENCE.name,
                content = buildString {
                    append("Contexte conversationnel récent")
                    if (turn.topics.isNotEmpty()) append(" [${turn.topics.joinToString(", ")}]")
                    append(". Utilisateur : ")
                    append(turn.userExcerpt)
                    append(" | Jade : ")
                    append(turn.answerExcerpt)
                }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                source = "CONVERSATION_RECENT_LOCAL",
                confidence = 0.72,
                createdAt = turn.createdAt,
                originNode = "phone-conversation"
            )
        }

        val recurring = state.topics
            .filter { stat -> stat.topic in currentTopics && stat.count >= 3 }
            .sortedByDescending { it.count }
            .take(2)
        recurring.forEach { stat ->
            output += MemorySnapshot(
                id = "conversation:topic:${sha256(stat.topic)}",
                type = MemoryType.OBSERVATION.name,
                content = buildString {
                    append("Sujet récurrent détecté localement : ${stat.topic} (${stat.count} occurrence(s)). ")
                    append("Retours explicites : ${stat.positive} positifs, ${stat.negative} négatifs, ${stat.corrections} correction(s). ")
                    append("Préserve la continuité et réutilise les expériences pertinentes sans inventer de connaissance absente.")
                }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                source = "CONVERSATION_TOPIC_LOCAL",
                confidence = (0.55 + stat.count.coerceAtMost(24) / 100.0).coerceAtMost(0.79),
                createdAt = stat.lastSeenAt.coerceAtMost(now),
                originNode = "phone-conversation"
            )
        }

        output
            .distinctBy { it.id }
            .take(safeLimit)
    }

    fun topicSummary(limit: Int = 10): List<Pair<String, Int>> = synchronized(lock) {
        loadUnsafe().topics
            .sortedWith(
                compareByDescending<TopicStat> { it.count }
                    .thenByDescending { it.lastSeenAt }
            )
            .take(limit.coerceIn(1, 20))
            .map { it.topic to it.count }
    }

    private fun topicLabel(topics: List<String>): String =
        topics.take(4).joinToString(", ").ifBlank { "le sujet précédent" }

    private fun loadUnsafe(): ConversationLearningState {
        val primary = prefs.getString(KEY_STATE, null)
        if (!primary.isNullOrBlank()) {
            decode(primary)?.let { return it }
        }
        val backup = prefs.getString(KEY_STATE_BACKUP, null)
        if (!backup.isNullOrBlank()) {
            decode(backup)?.let { recovered ->
                prefs.edit().putString(KEY_STATE, encode(recovered)).apply()
                return recovered
            }
        }
        return ConversationLearningState()
    }

    private fun saveUnsafe(state: ConversationLearningState) {
        val encoded = encode(state)
        val previous = prefs.getString(KEY_STATE, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_STATE_BACKUP, previous)
        }
        editor.putString(KEY_STATE, encoded).apply()
    }

    private fun encode(state: ConversationLearningState): String = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("turns", JSONArray().apply {
            state.turns.forEach { turn ->
                put(JSONObject().apply {
                    put("id", turn.id)
                    put("user_excerpt", turn.userExcerpt)
                    put("answer_excerpt", turn.answerExcerpt)
                    put("topics", JSONArray(turn.topics))
                    put("profile", turn.profile)
                    put("backend_id", turn.backendId)
                    put("model", turn.model)
                    put("fallback_used", turn.fallbackUsed)
                    put("created_at", turn.createdAt)
                })
            }
        })
        put("outcomes", JSONArray().apply {
            state.outcomes.forEach { outcome ->
                put(JSONObject().apply {
                    put("id", outcome.id)
                    put("turn_id", outcome.turnId)
                    put("kind", outcome.kind.name)
                    put("confidence", outcome.confidence)
                    put("feedback_excerpt", outcome.feedbackExcerpt)
                    put("topics", JSONArray(outcome.topics))
                    put("profile", outcome.profile)
                    put("model", outcome.model)
                    put("created_at", outcome.createdAt)
                })
            }
        })
        put("topics", JSONArray().apply {
            state.topics.forEach { stat ->
                put(JSONObject().apply {
                    put("topic", stat.topic)
                    put("count", stat.count)
                    put("positive", stat.positive)
                    put("negative", stat.negative)
                    put("corrections", stat.corrections)
                    put("last_seen_at", stat.lastSeenAt)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): ConversationLearningState? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("schema_version", SCHEMA_VERSION) != SCHEMA_VERSION) {
            return@runCatching null
        }

        val turns = buildList {
            val array = root.optJSONArray("turns") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    ConversationTurn(
                        id = id,
                        userExcerpt = item.optString("user_excerpt")
                            .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                        answerExcerpt = item.optString("answer_excerpt")
                            .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                        topics = readTopics(item.optJSONArray("topics")),
                        profile = item.optString("profile").take(40),
                        backendId = item.optString("backend_id").take(120),
                        model = item.optString("model").take(160),
                        fallbackUsed = item.optBoolean("fallback_used", false),
                        createdAt = item.optLong("created_at", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TURNS)

        val outcomes = buildList {
            val array = root.optJSONArray("outcomes") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val turnId = item.optString("turn_id").trim()
                val kind = runCatching {
                    ConversationFeedbackKind.valueOf(item.optString("kind").uppercase())
                }.getOrNull()
                if (id.isBlank() || turnId.isBlank() || kind == null) continue
                add(
                    ConversationOutcome(
                        id = id,
                        turnId = turnId,
                        kind = kind,
                        confidence = item.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                        feedbackExcerpt = item.optString("feedback_excerpt")
                            .take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TEXT_CHARS),
                        topics = readTopics(item.optJSONArray("topics")),
                        profile = item.optString("profile").take(40),
                        model = item.optString("model").take(160),
                        createdAt = item.optLong("created_at", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_OUTCOMES)

        val topics = buildList {
            val array = root.optJSONArray("topics") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val topic = item.optString("topic").trim().take(80)
                if (topic.isBlank()) continue
                add(
                    TopicStat(
                        topic = topic,
                        count = item.optInt("count", 0).coerceAtLeast(0),
                        positive = item.optInt("positive", 0).coerceAtLeast(0),
                        negative = item.optInt("negative", 0).coerceAtLeast(0),
                        corrections = item.optInt("corrections", 0).coerceAtLeast(0),
                        lastSeenAt = item.optLong("last_seen_at", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }.take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS)

        ConversationLearningState(turns, outcomes, topics)
    }.getOrNull()

    private fun readTopics(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val topic = array.optString(index).trim().take(80)
                if (topic.isNotBlank()) add(topic)
            }
        }.distinct().take(SafetyPolicy.MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val PREFS_NAME = "jade_conversation_learning"
        private const val KEY_STATE = "state_v1"
        private const val KEY_STATE_BACKUP = "state_v1_backup"
        private const val SCHEMA_VERSION = 1
        private const val MAX_FEEDBACK_WINDOW_MS = 48L * 60L * 60L * 1_000L
    }
}

object ConversationLearningRuntime {
    @Volatile
    private var store: ConversationLearningStore? = null

    fun initialize(context: Context): ConversationLearningStore = synchronized(this) {
        store ?: ConversationLearningStore(context.applicationContext).also { store = it }
    }

    fun currentOrNull(): ConversationLearningStore? = store

    fun current(): ConversationLearningStore =
        store ?: error("Conversation Learning n'est pas initialisé.")
}
