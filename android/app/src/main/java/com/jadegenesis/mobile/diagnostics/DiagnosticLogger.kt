package com.jadegenesis.mobile.diagnostics

import android.content.Context
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DiagnosticLogEntry
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun redactDiagnosticSecrets(value: String): String {
    var text = value.replace(
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
        "Bearer ***"
    )
    text = text.replace(
        Regex(
            """(?i)\b(token|secret|password|authorization|credential|private[_-]?key)\b\s*[:=]\s*([^\s,;]+)"""
        )
    ) { match ->
        "${match.groupValues[1]}=***"
    }
    return text
}


internal fun parseDiagnosticLogLine(line: String): DiagnosticLogEntry? =
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

internal fun readUtf8TailLines(file: File, limit: Int): List<String> {
    if (limit <= 0 || !file.isFile || file.length() == 0L) return emptyList()

    val tail = ArrayDeque<String>(limit)
    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
            if (line.isNotEmpty()) {
                if (tail.size == limit) tail.removeFirst()
                tail.addLast(line)
            }
        }
    }
    return tail.toList().asReversed()
}

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
        private const val MAX_EXPORTED_BUNDLES = 4
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
                if (isSecretKey(key)) {
                    "***"
                } else {
                    redactDiagnosticSecrets(sanitizeValue(value))
                }
            }
            val json = JSONObject().apply {
                put("created_at", System.currentTimeMillis())
                put("level", level.name)
                put("event", event.take(80))
                put("message", redactDiagnosticSecrets(message).take(800))
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
            readUtf8TailLines(currentLog, safeLimit)
                .mapNotNull(::parseDiagnosticLogLine)
                .reversed()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun exportBundle(summaryJson: String): String {
        pruneOldBundles()
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

    private fun pruneOldBundles() {
        val bundles = directory.listFiles { file ->
            file.isFile &&
                file.name.startsWith("Jade-Diagnostic-") &&
                file.name.endsWith(".zip")
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        bundles.drop(MAX_EXPORTED_BUNDLES - 1).forEach { file ->
            runCatching { file.delete() }
        }
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
