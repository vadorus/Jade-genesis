package com.jadegenesis.mobile.night

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inbox of captured public documentation metadata, not evidence of truth.
 * The Android validation app cannot fetch Internet sources. This inbox imports
 * a bounded manifest from the isolated PC collector and NEVER sets 'inspected'.
 *
 * A digest here is received metadata; it is not an authenticated attestation.
 * It must not be passed as supporting or refuting ReviewEvidence automatically.
 */
internal data class CapturedOfficialSource(
    val sourceKey: String,
    val sourceUrl: String,
    val sha256: String,
    val receivedBytes: Int,
    val retrievedAtMs: Long
)

internal object OfficialCaptureManifestParser {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_ITEMS = 3
    private const val MAX_JSON_CHARS = 12_000
    private val ALLOWED = mapOf(
        "roblox_export" to
            "https://create.roblox.com/docs/art/modeling/export-requirements",
        "roblox_blender" to "https://create.roblox.com/docs/art/blender",
        "blender_fbx" to
            "https://docs.blender.org/manual/en/latest/files/import_export/fbx.html"
    )

    fun parse(text: String, nowMs: Long = System.currentTimeMillis()): List<CapturedOfficialSource> {
        require(text.length <= MAX_JSON_CHARS) { "Oversized source manifest refused" }
        val root = JSONObject(text)
        require(root.optString("schema") == "jade.official_source_capture.v1")
        require(root.opt("automatic_verification") == false)
        val records = root.optJSONArray("records") ?: error("Missing records array")
        require(records.length() in 1..MAX_ITEMS)
        val parsed = buildList {
            for (i in 0 until records.length()) {
                val row = records.getJSONObject(i)
                val key = row.optString("source_key")
                val url = row.optString("source_url")
                require(ALLOWED[key] == url && url.isNotBlank()) { "Unapproved source" }
                require(row.opt("inspected") == false)
                require(row.optString("assessment") == "UNASSESSED")
                require(row.opt("experiment_performed") == false)
                require(row.optInt("http_status") == 200)
                require(row.optString("mime") in setOf("text/html", "text/plain"))
                val count = row.optInt("bytes")
                require(count in 50..MAX_BYTES) { "Source byte limit violation" }
                val sha = row.optString("sha256")
                require(sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid content digest" }
                val instant = row.optLong("retrieved_at_ms")
                require(instant > 0 && instant in (nowMs - 7 * 86_400_000L)..(nowMs + 60_000L)) {
                    "Stale or future capture"
                }
                add(CapturedOfficialSource(key, url, sha, count, instant))
            }
        }
        require(parsed.map { it.sourceKey }.distinct().size == parsed.size) {
            "Duplicate source keys"
        }
        return parsed
    }
}

internal class OfficialSourceCaptureInbox(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_official_source_capture_inbox_v1", Context.MODE_PRIVATE
    )

    fun importManifest(source: String): List<CapturedOfficialSource> {
        // Validate completely before performing any write.
        val parsed = OfficialCaptureManifestParser.parse(source)
        val payload = JSONArray().apply {
            parsed.forEach { captured ->
                put(JSONObject().apply {
                    put("source_key", captured.sourceKey)
                    put("source_url", captured.sourceUrl)
                    put("sha256", captured.sha256)
                    put("received_bytes", captured.receivedBytes)
                    put("retrieved_at_ms", captured.retrievedAtMs)
                    put("inspected", false)
                    put("assessment", "UNASSESSED")
                })
            }
        }
        check(prefs.edit().putString("captures_v1", payload.toString()).commit()) {
            "Failed to persist metadata inbox"
        }
        return parsed
    }

    fun latest(): List<CapturedOfficialSource> = runCatching {
        val arr = JSONArray(prefs.getString("captures_v1", "[]") ?: "[]")
        buildList {
            for (i in 0 until minOf(arr.length(), 3)) {
                val row = arr.optJSONObject(i) ?: continue
                if (row.opt("inspected") != false || row.optString("assessment") != "UNASSESSED") {
                    continue
                }
                val key = row.optString("source_key")
                val url = row.optString("source_url")
                val sha = row.optString("sha256")
                val count = row.optInt("received_bytes")
                val time = row.optLong("retrieved_at_ms")
                if (key.isNotBlank() && url.startsWith("https://") &&
                    sha.matches(Regex("[a-f0-9]{64}")) && count in 50..1024*1024 && time > 0
                ) {
                    add(CapturedOfficialSource(key, url, sha, count, time))
                }
            }
        }
    }.getOrDefault(emptyList())

    fun clear() {
        check(prefs.edit().remove("captures_v1").commit()) {
            "Failed to clear source capture inbox"
        }
    }
}
