package com.jadegenesis.mobile

import com.jadegenesis.mobile.night.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightCycleDiagnosticsTest {
    @Test fun partialCycleIdentifiesFailedPhasesWithoutBlamingSuccessfulMemoryStep() {
        val details = nightCycleFailureMetadata(listOf(
            NightCycleStep(NightCyclePhase.PREPARE, true, "ready", 1),
            NightCycleStep(NightCyclePhase.SYNC_BEFORE, false, "connection failed", 2),
            NightCycleStep(NightCyclePhase.MEMORY_CONSOLIDATION, true, "cursor up to date", 3),
            NightCycleStep(NightCyclePhase.EVOLUTION_REVIEW, false, "review failed", 4)
        ))
        assertEquals("SYNC_BEFORE,EVOLUTION_REVIEW", details["failed_phases"])
        assertTrue(details.getValue("failure_details").contains("connection failed"))
        assertFalse(details.getValue("failure_details").contains("cursor up to date"))
    }

    @Test fun successfulCycleDoesNotReportFailures() {
        val details = nightCycleFailureMetadata(listOf(
            NightCycleStep(NightCyclePhase.PREPARE, true, "ready", 1)
        ))
        assertEquals("", details["failed_phases"])
        assertEquals("", details["failure_details"])
    }

    @Test fun lengthyFailureDetailsRemainBounded() {
        val details = nightCycleFailureMetadata(NightCyclePhase.entries.map {
            NightCycleStep(it, false, "x".repeat(1000), 1)
        })
        assertTrue(details.getValue("failure_details").length <= 500)
        assertFalse(details.getValue("failure_details").contains("RUNTIME_EVAL_REVIEW"))
    }
}
