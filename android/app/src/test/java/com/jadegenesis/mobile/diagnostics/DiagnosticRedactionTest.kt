package com.jadegenesis.mobile.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticRedactionTest {
    @Test
    fun bearerAndNamedSecretsAreMaskedInFreeText() {
        val raw =
            "HTTP 401 Authorization=very-secret-value token: abcdefghijklmnopqrstuvwxyz123456 Bearer abcdefghijklmnopqrstuvwxyz123456"

        val redacted = redactDiagnosticSecrets(raw)

        assertTrue(redacted.contains("Authorization=***"))
        assertTrue(redacted.contains("token=***"))
        assertTrue(redacted.contains("Bearer ***"))
        assertFalse(redacted.contains("very-secret-value"))
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz123456"))
    }

    @Test
    fun ordinaryDiagnosticDetailsRemainReadable() {
        val raw = "home timeout on 100.64.12.34:8765"

        assertEquals(raw, redactDiagnosticSecrets(raw))
    }


    @Test
    fun utf8TailReadPreservesAccentsAndDoesNotMutateLogFile() {
        val phrase = "nœud sélectionné — réponse observée"
        val file = File.createTempFile("jade-diagnostic-", ".log")
        try {
            val line =
                """{"created_at":1,"level":"INFO","event":"utf8_probe","message":"$phrase","metadata":{}}"""
            file.writeText(line + "\n", Charsets.UTF_8)
            val before = file.readBytes()

            val rawLine = readUtf8TailLines(file, 1).single()
            val model = parseDiagnosticLogLine(rawLine)

            assertTrue(String(before, Charsets.UTF_8).contains(phrase))
            assertEquals(phrase, model?.message)
            assertArrayEquals(before, file.readBytes())
        } finally {
            file.delete()
        }
    }
}
