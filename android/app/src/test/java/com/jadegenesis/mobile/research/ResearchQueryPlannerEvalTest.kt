package com.jadegenesis.mobile.research

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class ResearchQueryPlannerEvalTest {

    private data class EvalCase(
        val id: String,
        val category: String,
        val protected: Boolean = false,
        val observation: String,
        val focus: String = "",
        val requiredAll: List<String> = emptyList(),
        val forbiddenAny: List<String> = emptyList(),
        val maxQueries: Int = 3,
        val maxQueryLength: Int = 150
    )

    private data class CaseResult(
        val case: EvalCase,
        val queries: List<String>,
        val passed: Boolean,
        val failures: List<String>
    )

    @Test
    fun researchQueryPlannerMustNotRegressBelowVerifiedV014Baseline() {
        val engine = ResearchEngine()
        val cases = evaluationCases()

        val results = cases.map { case ->
            evaluate(case, engine.buildResearchQueries(case.observation, case.focus))
        }

        val passedCount = results.count { it.passed }
        val overallScore = passedCount * 100.0 / results.size
        val protectedFailures = results.filter { it.case.protected && !it.passed }

        val categoryScores = results
            .groupBy { it.case.category }
            .mapValues { (_, categoryResults) ->
                categoryResults.count { it.passed } * 100.0 / categoryResults.size
            }

        val report = buildReport(
            results = results,
            overallScore = overallScore,
            categoryScores = categoryScores
        )

        val reportFile = File(
            System.getProperty("user.dir"),
            "build/reports/jade-eval/research-query-planner-v1.txt"
        )
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)

        println(report)

        assertTrue(
            "Protected evaluation regression detected.\n$report",
            protectedFailures.isEmpty()
        )

        assertTrue(
            "Research query planner score regressed below the verified V0.1.4 baseline " +
                "$BASELINE_SCORE.\n$report",
            overallScore + EPSILON >= BASELINE_SCORE
        )
    }

    private fun evaluate(
        case: EvalCase,
        queries: List<String>
    ): CaseResult {
        val failures = mutableListOf<String>()
        val joined = queries.joinToString("\n").lowercase(Locale.ROOT)

        case.requiredAll.forEach { required ->
            if (!joined.contains(required.lowercase(Locale.ROOT))) {
                failures += "missing required token: $required"
            }
        }

        case.forbiddenAny.forEach { forbidden ->
            if (joined.contains(forbidden.lowercase(Locale.ROOT))) {
                failures += "forbidden token leaked: $forbidden"
            }
        }

        if (queries.size > case.maxQueries) {
            failures += "too many queries: ${queries.size} > ${case.maxQueries}"
        }

        queries.forEachIndexed { index, query ->
            if (query.length > case.maxQueryLength) {
                failures +=
                    "query ${index + 1} too long: ${query.length} > ${case.maxQueryLength}"
            }
        }

        if (queries.isEmpty()) {
            failures += "no query generated"
        }

        return CaseResult(
            case = case,
            queries = queries,
            passed = failures.isEmpty(),
            failures = failures
        )
    }

    private fun buildReport(
        results: List<CaseResult>,
        overallScore: Double,
        categoryScores: Map<String, Double>
    ): String = buildString {
        appendLine("suite=research_query_planner_v1")
        appendLine("source_baseline=Jade Genesis V0.1.4 verified repo-first")
        appendLine("baseline_score=${format(BASELINE_SCORE)}")
        appendLine("target_score=${format(TARGET_SCORE)}")
        appendLine("overall_score=${format(overallScore)}")
        appendLine("passed=${results.count { it.passed }}/${results.size}")

        categoryScores.toSortedMap().forEach { (category, score) ->
            appendLine("category.$category=${format(score)}")
        }

        appendLine("protected_failures=${results.count { it.case.protected && !it.passed }}")
        appendLine()
        appendLine("cases:")

        results.forEach { result ->
            appendLine(
                "- ${result.case.id} " +
                    "[${result.case.category}] " +
                    "${if (result.passed) "PASS" else "FAIL"}" +
                    if (result.case.protected) " PROTECTED" else ""
            )

            result.queries.forEachIndexed { index, query ->
                appendLine("  q${index + 1}=$query")
            }

            result.failures.forEach { failure ->
                appendLine("  reason=$failure")
            }
        }
    }.trimEnd()

    private fun format(value: Double): String =
        String.format(Locale.ROOT, "%.1f", value)

    private fun evaluationCases(): List<EvalCase> = listOf(
        EvalCase(
            id = "github_repo_visible",
            category = "quality",
            observation = """
                Analyse de l'image fournie
                Visible : dépôt GitHub vadorus/Jade-genesis
                Visible : dossiers .github/workflows et android
                Visible : Cognitive Core 0.1.4
            """.trimIndent(),
            focus = "Vérifie le dépôt public visible",
            requiredAll = listOf("vadorus/Jade-genesis")
        ),
        EvalCase(
            id = "github_url_visible",
            category = "quality",
            observation = """
                Visible : https://github.com/vadorus/Jade-genesis
                Visible : README.md et android
            """.trimIndent(),
            focus = "Retrouve cette ressource publique",
            requiredAll = listOf("https://github.com/vadorus/Jade-genesis")
        ),
        EvalCase(
            id = "android_compile_error",
            category = "quality",
            observation = """
                ERROR Gradle Kotlin compileDebugKotlin failed sur Android 17
                Runtime 0.1.4
            """.trimIndent(),
            focus = "Identifier la cause technique",
            requiredAll = listOf("compileDebugKotlin", "Gradle", "Kotlin")
        ),
        EvalCase(
            id = "privacy_redaction",
            category = "safety",
            protected = true,
            observation = """
                Utilisateur: alex@example.com
                IP 100.98.238.6
                token abcdefghijklmnopqrstuvwxyzABCDEF
                Visible : GitHub Jade Genesis
            """.trimIndent(),
            focus = "Recherche publique",
            forbiddenAny = listOf(
                "alex@example.com",
                "100.98.238.6",
                "abcdefghijklmnopqrstuvwxyzABCDEF"
            )
        ),
        EvalCase(
            id = "confidence_noise_filter",
            category = "hygiene",
            observation = """
                Confiance élevée : 95%
                Je vois l'image fournie
                Visible : Tour Eiffel Paris
            """.trimIndent(),
            focus = "Identifier le monument",
            forbiddenAny = listOf("95%", "image fournie")
        ),
        EvalCase(
            id = "duplicate_collapse",
            category = "hygiene",
            observation = """
                Visible : Android 17 Pixel
                Visible : Android 17 Pixel
                Visible : Android 17 Pixel
            """.trimIndent(),
            maxQueries = 2
        ),
        EvalCase(
            id = "version_retention",
            category = "quality",
            observation = """
                Jade Genesis Cognitive Core 0.1.4
                Runtime PC 0.1.1
                GitHub repository
            """.trimIndent(),
            focus = "Vérifier la version visible",
            requiredAll = listOf("0.1.4")
        ),
        EvalCase(
            id = "query_length_bound",
            category = "safety",
            protected = true,
            observation = "Visible : " + "Kotlin Android Gradle ".repeat(20),
            focus = "Analyser",
            maxQueryLength = 150
        ),
        EvalCase(
            id = "generic_public_entity",
            category = "quality",
            observation = """
                Visible : Tour Eiffel à Paris
                Visible : monument métallique
            """.trimIndent(),
            focus = "Identifier et vérifier",
            requiredAll = listOf("Tour Eiffel")
        ),
        EvalCase(
            id = "github_path_and_repository",
            category = "quality",
            observation = """
                Visible : .github/workflows/jade-android-ci.yml
                Visible : vadorus/Jade-genesis
            """.trimIndent(),
            focus = "Vérifie le dépôt",
            requiredAll = listOf("vadorus/Jade-genesis")
        )
    )

    private companion object {
        const val BASELINE_SCORE = 80.0
        const val TARGET_SCORE = 100.0
        const val EPSILON = 0.0001
    }
}
