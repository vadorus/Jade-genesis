package com.jadegenesis.mobile.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DiagnosticOrderingTest {
    @Test
    fun recentEntriesAreNewestFirstAcrossMultipleLines() {
        val file = File.createTempFile("jade-order-", ".log")
        try {
            file.writeText((1..5).joinToString("\n") {
                """{"created_at":$it,"event":"event_$it","message":"nœud"}"""
            } + "\n")
            val before = file.readBytes()
            assertEquals(listOf(5L, 4L, 3L), readRecentDiagnosticEntries(file, 3).map { it.createdAt })
            assertEquals(listOf(5L), readRecentDiagnosticEntries(file, 1).map { it.createdAt })
            assertEquals(5, readRecentDiagnosticEntries(file, 10).size)
            assertArrayEquals(before, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedAndBlankLinesDoNotReverseValidEntries() {
        val file = File.createTempFile("jade-order-", ".log")
        try {
            file.writeText("""{"created_at":1}
                |
                |broken-json
                |{"created_at":2}
            """.trimMargin())
            assertEquals(listOf(2L, 1L), readRecentDiagnosticEntries(file, 10).map { it.createdAt })
            assertEquals(emptyList<String>(), readUtf8TailLines(file, 0))
        } finally {
            file.delete()
        }
    }
}
