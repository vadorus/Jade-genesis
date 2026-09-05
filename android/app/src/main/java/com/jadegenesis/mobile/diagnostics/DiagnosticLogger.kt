package com.jadegenesis.mobile.diagnostics

import android.content.Context
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DiagnosticLogEntry
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticLogger(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "jade_genesis_diagnostics",
        Context.MODE_PRIVATE
    )
    private val directory = File(appContext.filesDir, "diagnostics").apply {
        mkdirs()
    }
    private val currentLog = File(directory, "jade.log")

    companion object {
        private const val MAX_LOG_BYTES = 1_500_000L
        private const val MAX_ROTATED_FILES = 3
        private const val KEY_DEBUG = "debug_enabled"
    }

    fun setDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG, enabled).apply()
        log(
            DiagnosticLevel.INFO,
            "diagnostics_debug_mode",
            if (enabled) "Mode DEBUG activé." else "Mode DEBUG désactivé."
        )
    }

    fun isDebugEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG, false)

    @Synchronized
    fun log(
        level: DiagnosticLevel,
        event: String,
        message: String,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        if (level == DiagnosticLevel.DEBUG && !isDebugEnabled()) return

        runCatching {
            rotateIfNeeded()
            val safeMetadata = metadata.mapValues { (key, value) ->
                if (isSecretKey(key)) "***" else sanitizeValue(value)
            }
            val json = JSONObject().apply {
                put("created_at", System.currentTimeMillis())
                put("level", level.name)
                put("event", event.take(80))
                put("message", message.take(800))
                put(
                    "metadata",
                    JSONObject().apply {
                        safeMetadata.forEach { (key, value) ->
                            put(key.take(80), value)
                        }
                    }
                )
            }
            currentLog.appendText(json.toString() + "\n", Charsets.UTF_8)
        }
    }

    @Synchronized
    fun recent(limit: Int = 120): List<DiagnosticLogEntry> {
        val safeLimit = limit.coerceIn(1, 500)
        if (!currentLog.exists()) return emptyList()

        return runCatching {
            currentLog.readLines(Charsets.UTF_8)
                .takeLast(safeLimit)
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        val metadataJson = json.optJSONObject("metadata")
                        val metadata = buildMap {
                            if (metadataJson != null) {
                                val keys = metadataJson.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    put(key, metadataJson.optString(key))
                                }
                            }
                        }
                        DiagnosticLogEntry(
                            level = runCatching {
                                DiagnosticLevel.valueOf(
                                    json.optString("level", DiagnosticLevel.INFO.name)
                                )
                            }.getOrDefault(DiagnosticLevel.INFO),
                            event = json.optString("event"),
                            message = json.optString("message"),
                            metadata = metadata,
                            createdAt = json.optLong("created_at")
                        )
                    }.getOrNull()
                }
                .reversed()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun exportBundle(summaryJson: String): String {
        val output = File(
            directory,
            "Jade-Diagnostic-${System.currentTimeMillis()}.zip"
        )
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val logs = buildList {
                if (currentLog.exists()) add(currentLog)
                for (index in 1..MAX_ROTATED_FILES) {
                    File(directory, "jade.log.$index")
                        .takeIf { it.exists() }
                        ?.let { add(it) }
                }
            }
            logs.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("summary.json"))
            zip.write(summaryJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.absolutePath
    }

    private fun rotateIfNeeded() {
        if (!currentLog.exists() || currentLog.length() < MAX_LOG_BYTES) return

        File(directory, "jade.log.$MAX_ROTATED_FILES").delete()
        for (index in MAX_ROTATED_FILES - 1 downTo 1) {
            val source = File(directory, "jade.log.$index")
            if (source.exists()) {
                source.renameTo(File(directory, "jade.log.${index + 1}"))
            }
        }
        currentLog.renameTo(File(directory, "jade.log.1"))
    }

    private fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase()
        return listOf(
            "token",
            "secret",
            "password",
            "authorization",
            "credential",
            "private_key"
        ).any { it in normalized }
    }

    private fun sanitizeValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> value.toString().take(500)
    }
}
