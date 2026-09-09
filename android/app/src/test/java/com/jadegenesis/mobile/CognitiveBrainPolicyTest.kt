package com.jadegenesis.mobile

import com.jadegenesis.mobile.brain.CognitiveBrainPolicy
import com.jadegenesis.mobile.brain.CognitiveBrainProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveBrainPolicyTest {
    @Test
    fun shortQuestionUsesFastProfile() {
        val plan = CognitiveBrainPolicy.plan("answer", "Quelle heure est-il demain ?")
        assertEquals(CognitiveBrainProfile.FAST, plan.profile)
        assertEquals(4_096, plan.desiredContextTokens)
    }

    @Test
    fun architectureQuestionUsesReasoningProfile() {
        val plan = CognitiveBrainPolicy.plan(
            "answer",
            "Analyse l'architecture distribuée de Jade et propose un plan fiable."
        )
        assertEquals(CognitiveBrainProfile.REASONING, plan.profile)
        assertTrue(plan.desiredContextTokens >= 12_000)
    }

    @Test
    fun codingQuestionUsesCodeProfile() {
        val plan = CognitiveBrainPolicy.plan(
            "answer",
            "Corrige ce code Kotlin et vérifie la compilation."
        )
        assertEquals(CognitiveBrainProfile.CODE, plan.profile)
    }

    @Test
    fun verifyAlwaysUsesCritic() {
        val plan = CognitiveBrainPolicy.plan("verify", "ok")
        assertEquals(CognitiveBrainProfile.CRITIC, plan.profile)
        assertTrue(plan.temperature < 0.10)
    }

    @Test
    fun reviseAlwaysUsesReasoning() {
        val plan = CognitiveBrainPolicy.plan("revise", "réécris")
        assertEquals(CognitiveBrainProfile.REASONING, plan.profile)
    }

    @Test
    fun toolBuildAlwaysUsesCode() {
        val plan = CognitiveBrainPolicy.plan("tool_build", "outil")
        assertEquals(CognitiveBrainProfile.CODE, plan.profile)
    }
}
