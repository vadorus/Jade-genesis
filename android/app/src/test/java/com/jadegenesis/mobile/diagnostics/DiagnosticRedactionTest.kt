package com.jadegenesis.mobile.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
