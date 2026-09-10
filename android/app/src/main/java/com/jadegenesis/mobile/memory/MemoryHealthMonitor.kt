package com.jadegenesis.mobile.memory

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * État purement observatoire du stockage persistant de Jade sur le Pixel.
 * Aucun seuil de ce module ne déclenche une suppression automatique.
 */
enum class MemoryHealthStatus {
    NORMAL,
    WATCH,
    HIGH
}

data class MemoryHealthSnapshot(
    val totalBytes: Long,
    val coreDatabaseBytes: Long,
    val conversationLearningBytes: Long,
    val runtimeEvalBytes: Long,
    val sharedStateBytes: Long,
    val otherJadeStateBytes: Long,
    val growth7dBytes: Long?,
    val growth30dBytes: Long?,
    val status: MemoryHealthStatus,
    val sampledAt: Long
)

internal data class MemoryHealthSample(
    val totalBytes: Long,
    val sampledAt: Long
)

internal object MemoryHealthMath {
    const val DAY_MS = 24L * 60L * 60L * 1_000L

    fun growth(
        current: MemoryHealthSample,
        history: List<MemoryHealthSample>,
        horizonMs: Long
    ): Long? {
        val target = current.sampledAt - horizonMs.coerceAtLeast(0L)
        val baseline = history
            .asSequence()
            .filter { it.sampledAt <= target }
            .maxByOrNull { it.sampledAt }
            ?: return null
        return current.totalBytes - baseline.totalBytes
    }

    fun status(totalBytes: Long, growth30dBytes: Long?): MemoryHealthStatus = when {
        totalBytes >= HIGH_TOTAL_BYTES ||
            (growth30dBytes ?: Long.MIN_VALUE) >= HIGH_30D_GROWTH_BYTES ->
            MemoryHealthStatus.HIGH

        totalBytes >= WATCH_TOTAL_BYTES ||
            (growth30dBytes ?: Long.MIN_VALUE) >= WATCH_30D_GROWTH_BYTES ->
            MemoryHealthStatus.WATCH

        else -> MemoryHealthStatus.NORMAL
    }

    private const val WATCH_TOTAL_BYTES = 256L * 1024L * 1024L
    private const val HIGH_TOTAL_BYTES = 1L * 1024L * 1024L * 1024L
    private const val WATCH_30D_GROWTH_BYTES = 128L * 1024L * 1024L
    private const val HIGH_30D_GROWTH_BYTES = 512L * 1024L * 1024L
}

class MemoryHealthMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun snapshot(now: Long = System.currentTimeMillis()): MemoryHealthSnapshot =
        synchronized(lock) {
            val safeNow = now.coerceAtLeast(0L)
            val measured = measurePhysicalStorage()
            val current = MemoryHealthSample(
                totalBytes = measured.totalBytes,
                sampledAt = safeNow
            )
            val history = loadHistory()
            val growth7d = MemoryHealthMath.growth(
                current,
                history,
                7L * MemoryHealthMath.DAY_MS
            )
            val growth30d = MemoryHealthMath.growth(
                current,
                history,
                30L * MemoryHealthMath.DAY_MS
            )

            maybePersist(current, history)

            MemoryHealthSnapshot(
                totalBytes = measured.totalBytes,
                coreDatabaseBytes = measured.coreDatabaseBytes,
                conversationLearningBytes = measured.conversationLearningBytes,
                runtimeEvalBytes = measured.runtimeEvalBytes,
                sharedStateBytes = measured.sharedStateBytes,
                otherJadeStateBytes = measured.otherJadeStateBytes,
                growth7dBytes = growth7d,
                growth30dBytes = growth30d,
                status = MemoryHealthMath.status(measured.totalBytes, growth30d),
                sampledAt = safeNow
            )
        }

    private data class PhysicalStorage(
        val totalBytes: Long,
        val coreDatabaseBytes: Long,
        val conversationLearningBytes: Long,
        val runtimeEvalBytes: Long,
        val sharedStateBytes: Long,
        val otherJadeStateBytes: Long
    )

    private fun measurePhysicalStorage(): PhysicalStorage {
        val database = appContext.getDatabasePath(DATABASE_NAME)
        val databaseBytes = listOf(
            database,
            File(database.parentFile, "${database.name}-wal"),
            File(database.parentFile, "${database.name}-shm")
        ).sumOf(::safeLength)

        val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val jadePrefs = sharedPrefsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith("jade") &&
                    file.name.endsWith(".xml") &&
                    file.name != HEALTH_PREFS_FILE
            }

        fun named(name: String): Long =
            jadePrefs.firstOrNull { it.name == name }?.let(::safeLength) ?: 0L

        val conversation = named(CONVERSATION_PREFS_FILE)
        val runtime = named(RUNTIME_EVAL_PREFS_FILE)
        val shared = named(SHARED_STATE_PREFS_FILE)
        val explicitlyCounted = setOf(
            CONVERSATION_PREFS_FILE,
            RUNTIME_EVAL_PREFS_FILE,
            SHARED_STATE_PREFS_FILE
        )
        val other = jadePrefs
            .filterNot { it.name in explicitlyCounted }
            .sumOf(::safeLength)
        val total = databaseBytes + conversation + runtime + shared + other

        return PhysicalStorage(
            totalBytes = total,
            coreDatabaseBytes = databaseBytes,
            conversationLearningBytes = conversation,
            runtimeEvalBytes = runtime,
            sharedStateBytes = shared,
            otherJadeStateBytes = other
        )
    }

    private fun maybePersist(
        current: MemoryHealthSample,
        history: List<MemoryHealthSample>
    ) {
        val latest = history.maxByOrNull { it.sampledAt }
        val minimumIntervalMs =
            SafetyPolicy.MIN_MEMORY_HEALTH_SAMPLE_INTERVAL_HOURS * 60L * 60L * 1_000L
        if (latest != null && current.sampledAt - latest.sampledAt < minimumIntervalMs) {
            return
        }

        val updated = (listOf(current) + history)
            .filter { it.sampledAt >= 0L && it.totalBytes >= 0L }
            .distinctBy { it.sampledAt }
            .sortedByDescending { it.sampledAt }
            .take(SafetyPolicy.MAX_MEMORY_HEALTH_SAMPLES)
        prefs.edit().putString(KEY_HISTORY, encodeHistory(updated)).apply()
    }

    private fun loadHistory(): List<MemoryHealthSample> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("schema_version", -1) != SCHEMA_VERSION) {
                return@runCatching emptyList()
            }
            val items = root.optJSONArray("samples") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val total = item.optLong("total_bytes", -1L)
                    val sampledAt = item.optLong("sampled_at", -1L)
                    if (total >= 0L && sampledAt >= 0L) {
                        add(MemoryHealthSample(total, sampledAt))
                    }
                }
            }
                .sortedByDescending { it.sampledAt }
                .take(SafetyPolicy.MAX_MEMORY_HEALTH_SAMPLES)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(samples: List<MemoryHealthSample>): String =
        JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put(
                "samples",
                JSONArray().apply {
                    samples.forEach { sample ->
                        put(
                            JSONObject().apply {
                                put("total_bytes", sample.totalBytes)
                                put("sampled_at", sample.sampledAt)
                            }
                        )
                    }
                }
            )
        }.toString()

    private fun safeLength(file: File): Long =
        runCatching {
            if (file.isFile) file.length().coerceAtLeast(0L) else 0L
        }.getOrDefault(0L)

    companion object {
        private const val DATABASE_NAME = "jade_memory.db"
        private const val PREFS_NAME = "jade_memory_health"
        private const val HEALTH_PREFS_FILE = "$PREFS_NAME.xml"
        private const val CONVERSATION_PREFS_FILE = "jade_conversation_learning.xml"
        private const val RUNTIME_EVAL_PREFS_FILE = "jade_runtime_eval.xml"
        private const val SHARED_STATE_PREFS_FILE = "jade_shared_genesis_state.xml"
        private const val KEY_HISTORY = "samples_v1"
        private const val SCHEMA_VERSION = 1
    }
}
