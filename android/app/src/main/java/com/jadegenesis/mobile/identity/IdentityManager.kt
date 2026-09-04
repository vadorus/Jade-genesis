package com.jadegenesis.mobile.identity

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jadegenesis.mobile.model.JadeIdentity
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.identityDataStore by preferencesDataStore(name = "jade_identity")

class IdentityManager(private val context: Context) {

    private object Keys {
        val ID = stringPreferencesKey("jade_id")
        val NAME = stringPreferencesKey("jade_name")
        val VERSION = stringPreferencesKey("jade_version")
        val CREATED_AT = longPreferencesKey("jade_created_at")
    }

    suspend fun loadOrCreate(): JadeIdentity {
        val current = context.identityDataStore.data.first()
        val existingId = current[Keys.ID]

        if (existingId != null) {
            return JadeIdentity(
                jadeId = existingId,
                name = current[Keys.NAME] ?: "Jade Genesis",
                version = "0.0.1",
                createdAt = current[Keys.CREATED_AT] ?: System.currentTimeMillis()
            )
        }

        val identity = JadeIdentity(
            jadeId = "JG-${UUID.randomUUID()}",
            createdAt = System.currentTimeMillis()
        )

        context.identityDataStore.edit { prefs ->
            prefs[Keys.ID] = identity.jadeId
            prefs[Keys.NAME] = identity.name
            prefs[Keys.VERSION] = identity.version
            prefs[Keys.CREATED_AT] = identity.createdAt
        }

        return identity
    }
}
