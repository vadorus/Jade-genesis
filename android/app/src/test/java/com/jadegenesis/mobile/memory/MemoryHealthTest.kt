package com.jadegenesis.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryHealthTest {

    @Test
    fun growthUsesMostRecentSampleAtOrBeforeRequestedHorizon() {
        val day = MemoryHealthMath.DAY_MS
        val now = 40L * day
        val mib = 1024L * 1024L
        val current = MemoryHealthSample(130L * mib, now)
        val history = listOf(
            MemoryHealthSample(120L * mib, now - 2L * day),
            MemoryHealthSample(100L * mib, now - 8L * day),
            MemoryHealthSample(50L * mib, now - 31L * day)
        )

        assertEquals(
            30L * mib,
            MemoryHealthMath.growth(current, history, 7L * day)
        )
        assertEquals(
            80L * mib,
            MemoryHealthMath.growth(current, history, 30L * day)
        )
    }

    @Test
    fun growthIsUnknownUntilHistoryReachesTheHorizon() {
        val day = MemoryHealthMath.DAY_MS
        val current = MemoryHealthSample(20_000L, 10L * day)
        val history = listOf(
            MemoryHealthSample(10_000L, 9L * day)
        )

        assertNull(MemoryHealthMath.growth(current, history, 7L * day))
        assertNull(MemoryHealthMath.growth(current, history, 30L * day))
    }

    @Test
    fun statusIsInformationalAndEscalatesOnSizeOrRapidGrowth() {
        val mib = 1024L * 1024L
        val gib = 1024L * mib

        assertEquals(
            MemoryHealthStatus.NORMAL,
            MemoryHealthMath.status(40L * mib, 10L * mib)
        )
        assertEquals(
            MemoryHealthStatus.WATCH,
            MemoryHealthMath.status(300L * mib, 10L * mib)
        )
        assertEquals(
            MemoryHealthStatus.WATCH,
            MemoryHealthMath.status(40L * mib, 200L * mib)
        )
        assertEquals(
            MemoryHealthStatus.HIGH,
            MemoryHealthMath.status(2L * gib, 0L)
        )
    }
}
