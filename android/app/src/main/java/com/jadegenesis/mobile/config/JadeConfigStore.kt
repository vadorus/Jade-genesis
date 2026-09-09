package com.jadegenesis.mobile.config

import android.content.Context
import org.json.JSONObject

class JadeConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun loadOrCreate(): JadeConfig {
        val primary = prefs.getString(KEY_ACTIVE, null)
        parse(primary)?.let { return it }

        val backup = prefs.getString(KEY_BACKUP, null)
        parse(backup)?.let { recovered ->
            prefs.edit().putString(KEY_ACTIVE, recovered.toJson().toString()).apply()
            return recovered
        }

        return JadeConfig.defaults().validated().also { defaults ->
            prefs.edit()
                .putString(KEY_ACTIVE, defaults.toJson().toString())
                .apply()
        }
    }

    @Synchronized
    fun saveActive(config: JadeConfig) {
        val validated = config.validated()
        val current = prefs.getString(KEY_ACTIVE, null)
        val editor = prefs.edit()
        if (!current.isNullOrBlank()) {
            editor.putString(KEY_BACKUP, current)
        }
        editor.putString(KEY_ACTIVE, validated.toJson().toString()).apply()
    }

    private fun parse(raw: String?): JadeConfig? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            JadeConfig.fromJson(JSONObject(raw))
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "jade_genesis_config"
        private const val KEY_ACTIVE = "active_config_v1"
        private const val KEY_BACKUP = "backup_config_v1"
    }
}

object JadeConfigRuntime {
    @Volatile
    private var active: JadeConfig = JadeConfig.defaults()

    @Synchronized
    fun initialize(context: Context): JadeConfig {
        val loaded = JadeConfigStore(context).loadOrCreate()
        active = loaded
        return loaded
    }

    fun current(): JadeConfig = active

    @Synchronized
    fun promote(context: Context, config: JadeConfig): JadeConfig {
        val validated = config.validated()
        JadeConfigStore(context).saveActive(validated)
        active = validated
        return validated
    }
}
