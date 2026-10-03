package com.jadegenesis.mobile.cognitive

enum class ExplicitPreferenceKind {
    PREFERENCE,
    AVOIDANCE,
    PRIORITY,
    REQUEST_STYLE
}

data class ExplicitPreferenceSignal(
    val kind: ExplicitPreferenceKind,
    val statement: String,
    val topics: List<String>,
    val confidence: Double
)

enum class HumanKnowledgeDomain {
    STRESS,
    LOW_MOOD,
    MOTIVATION,
    CONFLICT,
    FATIGUE
}

data class HumanKnowledgeNeed(
    val domain: HumanKnowledgeDomain,
    val researchQuestion: String,
    val confidence: Double
)
object RightHandLearningPolicy {
    private val avoidanceMarkers = listOf(
        "je ne veux pas", "je veux pas", "je n'aime pas", "je n aime pas",
        "evite de", "évite de", "ne fais pas", "je refuse"
    )
    private val priorityMarkers = listOf(
        "ma priorite", "ma priorité", "c'est prioritaire", "c est prioritaire",
        "le plus important pour moi", "je tiens a", "je tiens à"
    )
    private val preferenceMarkers = listOf(
        "je prefere", "je préfère", "j'aime mieux", "j aime mieux",
        "je souhaite", "pour moi je prefere", "pour moi je préfère"
    )
    private val requestStyleMarkers = listOf(
        "je veux que tu", "j'aimerais que tu", "j aimerais que tu",
        "quand tu me reponds", "quand tu me réponds"
    )

    fun explicitPreference(input: String): ExplicitPreferenceSignal? {
        val clean = input.replace(Regex("\\s+"), " ").trim()
        if (clean.length < 8) return null
        val normalized = ConversationLearningPolicy.normalize(clean)
        val match = when {
            avoidanceMarkers.any { ConversationLearningPolicy.normalize(it) in normalized } ->
                ExplicitPreferenceKind.AVOIDANCE to 0.94
            priorityMarkers.any { ConversationLearningPolicy.normalize(it) in normalized } ->
                ExplicitPreferenceKind.PRIORITY to 0.94
            preferenceMarkers.any { ConversationLearningPolicy.normalize(it) in normalized } ->
                ExplicitPreferenceKind.PREFERENCE to 0.91
            requestStyleMarkers.any { ConversationLearningPolicy.normalize(it) in normalized } ->
                ExplicitPreferenceKind.REQUEST_STYLE to 0.86
            else -> return null
        }
        return ExplicitPreferenceSignal(
            kind = match.first,
            statement = clean.take(500),
            topics = ConversationLearningPolicy.extractTopics(clean, 6),
            confidence = match.second
        )
    }

    fun humanKnowledgeNeed(input: String): HumanKnowledgeNeed? {
        val normalized = ConversationLearningPolicy.normalize(input)
        val candidates = listOf(
            HumanKnowledgeDomain.STRESS to listOf("stress", "stresse", "pression", "angoisse", "anxieux", "deborde"),
            HumanKnowledgeDomain.LOW_MOOD to listOf("pas le moral", "pas trop le moral", "moral bas", "deprime", "triste", "abattu"),
            HumanKnowledgeDomain.MOTIVATION to listOf("motivation", "demotive", "procrastin", "pas envie", "manque d envie"),
            HumanKnowledgeDomain.CONFLICT to listOf("conflit", "dispute", "tension", "engueule", "desaccord"),
            HumanKnowledgeDomain.FATIGUE to listOf("fatigue", "epuise", "dors mal", "sommeil", "creve")
        )
        val domain = candidates.firstOrNull { (_, markers) -> markers.any { it in normalized } }?.first
            ?: return null
        return HumanKnowledgeNeed(domain, researchQuestion(domain), 0.82)
    }
    private fun researchQuestion(domain: HumanKnowledgeDomain): String = when (domain) {
        HumanKnowledgeDomain.STRESS ->
            "evidence based psychological support coping with stress in adults"
        HumanKnowledgeDomain.LOW_MOOD ->
            "evidence based supportive communication for low mood in adults"
        HumanKnowledgeDomain.MOTIVATION ->
            "evidence based motivation behavior change self determination adults"
        HumanKnowledgeDomain.CONFLICT ->
            "evidence based interpersonal conflict resolution communication adults"
        HumanKnowledgeDomain.FATIGUE ->
            "evidence based fatigue sleep support communication adults"
    }
}
