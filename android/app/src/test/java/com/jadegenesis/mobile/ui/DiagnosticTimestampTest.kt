package com.jadegenesis.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticTimestampTest {
    @Test
    fun timestampUsesExplicitZoneAndIncludesDateAndTime() {
        val epochMs = Instant.parse("2026-10-03T22:49:12Z").toEpochMilli()

        assertEquals(
            "03/10/2026 22:49:12",
            formatDiagnosticTimestamp(epochMs, ZoneId.of("UTC"))
        )
    }

    @Test
    fun invalidTimestampIsMarkedUnknown() {
        assertEquals(
            "date inconnue",
            formatDiagnosticTimestamp(0L, ZoneId.of("UTC"))
        )
    }
}
