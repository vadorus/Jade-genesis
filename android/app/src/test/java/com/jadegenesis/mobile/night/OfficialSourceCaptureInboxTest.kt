package com.jadegenesis.mobile.night

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSourceCaptureInboxTest {
    private val now = 1_800_000_000_000L
    private val official = mapOf(
        "roblox_export" to "https://create.roblox.com/docs/art/modeling/export-requirements",
        "roblox_blender" to "https://create.roblox.com/docs/art/blender",
        "blender_fbx" to "https://docs.blender.org/manual/en/latest/files/import_export/fbx.html"
    )

    private fun row(key: String = "roblox_blender"): JSONObject = JSONObject().apply {
        put("source_key", key)
        put("source_url", official.getValue(key))
        put("sha256", "a".repeat(64))
        put("retrieved_at_ms", now)
        put("bytes", 3_000)
        put("mime", "text/html")
        put("http_status", 200)
        put("inspected", false)
        put("assessment", "UNASSESSED")
        put("experiment_performed", false)
    }

    private fun manifest(vararg rows: JSONObject): String = JSONObject().apply {
        put("schema", "jade.official_source_capture.v1")
        put("created_at_ms", now)
        put("automatic_verification", false)
        put("records", JSONArray().apply { rows.forEach(::put) })
        put("errors", JSONArray())
    }.toString()

    @Test
    fun validSourceCaptureIsAlwaysOnlyUnassessedMetadata() {
        val result = OfficialCaptureManifestParser.parse(
            manifest(row("roblox_blender"), row("blender_fbx")), now
        )
        assertEquals(2, result.size)
        assertEquals("a".repeat(64), result.first().sha256)
        assertEquals(3_000, result.first().receivedBytes)
        assertFalse(result.first().sourceUrl.contains("private"))
    }

    @Test
    fun rejectsUnapprovedDomainOrPath() {
        val bad = row().put("source_url", "https://create.roblox.com.evil.invalid/docs/art/blender")
        expectRefused(manifest(bad))
    }

    @Test
    fun rejectsClaimsOfInspectionOrVerification() {
        expectRefused(manifest(row().put("inspected", true)))
        expectRefused(manifest(row().put("assessment", "VERIFIED")))
        expectRefused(manifest(row().put("automatic_promotion", true)
            .put("experiment_performed", true)))
        expectRefused(JSONObject(manifest(row()))
            .put("automatic_verification", true).toString())
    }

    @Test
    fun rejectsFakeDigestAndStaleOrFutureData() {
        expectRefused(manifest(row().put("sha256", "not-a-real-sha")))
        expectRefused(manifest(row().put("retrieved_at_ms", now - 8L * 86_400_000L)))
        expectRefused(manifest(row().put("retrieved_at_ms", now + 61_000L)))
    }

    @Test
    fun rejectsBadMimeOversizedAndRepeatedKeys() {
        expectRefused(manifest(row().put("mime", "application/octet-stream")))
        expectRefused(manifest(row().put("bytes", 2_000_000)))
        expectRefused(manifest(row(), row()))
    }

    @Test
    fun rejectsOversizedManifestOrWrongSchema() {
        expectRefused(manifest(row()) + " ".repeat(12_001))
        expectRefused(JSONObject(manifest(row()))
            .put("schema", "untrusted.v2").toString())
    }

    private fun expectRefused(text: String) {
        val result = runCatching { OfficialCaptureManifestParser.parse(text, now) }
        assertTrue("Expected rejection: $text", result.isFailure)
    }
}
