package com.jadegenesis.mobile.state

import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

data class SharedStateEvent(
    val eventId: String,
    val originNode: String,
    val kind: String,
    val entityId: String,
    val payload: String,
    val createdAt: Long,
    val serverRevision: Long = 0L
)

data class SharedStateSyncResponse(
    val identityId: String,
    val replicaId: String,
    val serverRevision: Long,
    val acknowledgedEventIds: Set<String>,
    val events: List<SharedStateEvent>,
    val resetRequired: Boolean,
    val snapshot: List<SharedStateEvent>,
    val serverUpdatedAt: Long
)

data class SharedStateSyncResult(
    val nodeId: String,
    val nodeName: String,
    val serverRevision: Long,
    val uploadedEvents: Int,
    val receivedEvents: Int,
    val outboxRemaining: Int,
    val resetApplied: Boolean,
    val syncedAt: Long
)

object SharedGenesisStateProtocol {
    const val SCHEMA_VERSION = 1

    fun buildSyncRequest(
        identityId: String,
        replicaId: String,
        knownRevision: Long,
        events: List<SharedStateEvent>
    ): String {
        require(identityId.isNotBlank()) { "Identité Jade absente du Shared State." }
        require(replicaId.isNotBlank()) { "Identité du replica absente du Shared State." }
        require(knownRevision >= 0L) { "Révision Shared State invalide." }
        require(events.size <= SafetyPolicy.MAX_SHARED_STATE_SYNC_EVENTS) {
            "Trop d'événements Shared State dans un seul lot."
        }
        events.forEach(::validateEvent)

        val encoded = JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("identity_id", identityId)
            put("replica_id", replicaId)
            put("known_revision", knownRevision)
            put(
                "events",
                JSONArray().apply {
                    events.forEach { put(eventToJson(it, includeServerRevision = false)) }
                }
            )
        }.toString()

        require(encoded.length <= SafetyPolicy.MAX_SHARED_STATE_SYNC_PAYLOAD_CHARS) {
            "Le lot Shared State dépasse la limite autorisée."
        }
        return encoded
    }

    fun parseSyncResponse(
        raw: String,
        expectedIdentityId: String,
        expectedReplicaId: String
    ): SharedStateSyncResponse {
        require(raw.length <= SafetyPolicy.MAX_SHARED_STATE_SYNC_RESPONSE_CHARS) {
            "Réponse Shared State trop volumineuse."
        }
        val json = JSONObject(raw)
        require(json.optInt("schema_version", -1) == SCHEMA_VERSION) {
            "Schéma Shared State incompatible."
        }
        val identityId = json.optString("identity_id").trim()
        val replicaId = json.optString("replica_id").trim()
        require(identityId == expectedIdentityId) {
            "Le Shared State distant appartient à une autre identité Jade."
        }
        require(replicaId == expectedReplicaId) {
            "Le Shared State a répondu pour un autre replica."
        }
        val revision = json.optLong("server_revision", -1L)
        require(revision >= 0L) { "Révision serveur Shared State invalide." }

        val ack = parseStringSet(json.optJSONArray("ack_event_ids"))
        val events = parseEvents(json.optJSONArray("events"))
        val snapshot = parseEvents(json.optJSONArray("snapshot"))
        require(events.size <= SafetyPolicy.MAX_SHARED_STATE_SYNC_EVENTS) {
            "Le serveur a renvoyé trop d'événements Shared State."
        }
        require(snapshot.size <= SafetyPolicy.MAX_SHARED_STATE_CACHE_EVENTS) {
            "Le snapshot Shared State est trop volumineux."
        }

        return SharedStateSyncResponse(
            identityId = identityId,
            replicaId = replicaId,
            serverRevision = revision,
            acknowledgedEventIds = ack,
            events = events,
            resetRequired = json.optBoolean("reset_required", false),
            snapshot = snapshot,
            serverUpdatedAt = json.optLong("server_updated_at", 0L).coerceAtLeast(0L)
        )
    }

    fun encodeEvents(events: List<SharedStateEvent>): String =
        JSONArray().apply {
            events.forEach { put(eventToJson(it, includeServerRevision = true)) }
        }.toString()

    fun decodeEvents(raw: String?): List<SharedStateEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return parseEvents(JSONArray(raw))
    }

    fun eventToJson(
        event: SharedStateEvent,
        includeServerRevision: Boolean
    ): JSONObject {
        validateEvent(event)
        return JSONObject().apply {
            put("event_id", event.eventId)
            put("origin_node", event.originNode)
            put("kind", event.kind)
            put("entity_id", event.entityId)
            put("payload", event.payload)
            put("created_at", event.createdAt)
            if (includeServerRevision && event.serverRevision > 0L) {
                put("server_revision", event.serverRevision)
            }
        }
    }

    private fun parseEvents(array: JSONArray?): List<SharedStateEvent> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val event = SharedStateEvent(
                    eventId = json.getString("event_id").trim(),
                    originNode = json.getString("origin_node").trim(),
                    kind = json.getString("kind").trim(),
                    entityId = json.getString("entity_id").trim(),
                    payload = json.optString("payload"),
                    createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L),
                    serverRevision = json.optLong("server_revision", 0L).coerceAtLeast(0L)
                )
                validateEvent(event)
                add(event)
            }
        }
    }

    private fun parseStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun validateEvent(event: SharedStateEvent) {
        require(event.eventId.isNotBlank() && event.eventId.length <= 160)
        require(event.originNode.isNotBlank() && event.originNode.length <= 160)
        require(event.kind.isNotBlank() && event.kind.length <= 80)
        require(event.entityId.isNotBlank() && event.entityId.length <= 160)
        require(event.payload.length <= SafetyPolicy.MAX_SHARED_STATE_EVENT_PAYLOAD_CHARS)
        require(event.createdAt >= 0L)
        require(event.serverRevision >= 0L)
    }
}
