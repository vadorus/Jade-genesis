package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.night.NightCyclePhase
import com.jadegenesis.mobile.night.NightCyclePolicy
import com.jadegenesis.mobile.night.NightCycleStatus
import com.jadegenesis.mobile.night.NightCycleStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightCyclePolicyTest {
    @Test
    fun firstNightCycleIsAllowed() {
        assertTrue(NightCyclePolicy.shouldRun(0L, 1_000L))
    }

    @Test
    fun cadenceGuardBlocksRapidRepeat() {
        val last = 1_000L
        val tooSoon = last +
            (SafetyPolicy.MIN_NIGHT_CYCLE_INTERVAL_HOURS - 1L) * 60L * 60L * 1_000L
        assertFalse(NightCyclePolicy.shouldRun(last, tooSoon))
    }

    @Test
    fun cadenceGuardAllowsNextWindow() {
        val last = 1_000L
        val next = last +
            SafetyPolicy.MIN_NIGHT_CYCLE_INTERVAL_HOURS * 60L * 60L * 1_000L
        assertTrue(NightCyclePolicy.shouldRun(last, next))
    }

    @Test
    fun allSuccessfulStepsProduceSuccess() {
        val steps = listOf(
            NightCycleStep(NightCyclePhase.PREPARE, true, "ok", 1L),
            NightCycleStep(NightCyclePhase.MEMORY_CONSOLIDATION, true, "ok", 1L)
        )
        assertEquals(NightCycleStatus.SUCCESS, NightCyclePolicy.finalStatus(steps))
    }

    @Test
    fun nonFatalStepFailureProducesPartial() {
        val steps = listOf(
            NightCycleStep(NightCyclePhase.PREPARE, true, "ok", 1L),
            NightCycleStep(NightCyclePhase.SYNC_BEFORE, false, "offline", 1L)
        )
        assertEquals(NightCycleStatus.PARTIAL, NightCyclePolicy.finalStatus(steps))
    }

    @Test
    fun prepareFailureProducesFailure() {
        val steps = listOf(
            NightCycleStep(NightCyclePhase.PREPARE, false, "fail", 1L)
        )
        assertEquals(NightCycleStatus.FAILED, NightCyclePolicy.finalStatus(steps))
    }
}
