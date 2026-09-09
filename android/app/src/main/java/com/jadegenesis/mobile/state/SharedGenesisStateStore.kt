package com.jadegenesis.mobile.state

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import java.util.UUID

class SharedGenesisStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun publish(
        originNode: String,
        kind: String,
        entityId: String,
        payload: String,
        createdAt: Long = System.currentTimeMillis()
    ): SharedStateEvent = synchronized(lock) {
        require(originNode.isNotBlank()) { "Nœud d'origine Shared State absent." }
        require(kind.isNotBlank()) { "Type Shared State absent." }
        require(entityId.isNotBlank()) { "Entité Shared State absente." }
        require(payload.length <= SafetyPolicy.MAX_SHARED_STATE_EVENT_PAYLOAD_CHARS) {
            "Payload Shared State trop volumineux."
        }

        val event = SharedStateEvent(
            eventId = "state-${UUID.randomUUID()}",
            originNode = originNode.trim(),
            kind = kind.trim().take(80),
            entityId = entityId.trim().take(160),
            payload = payload,
            createdAt = createdAt.coerceAtLeast(0L)
        )
        val existing = loadEvents(KEY_OUTBOX, KEY_OUTBOX_BACKUP)
        val current = if (event.kind in COALESCED_OPERATIONAL_KINDS) {
            existing.filterNot {
                it.originNode == event.originNode &&
                    it.kind == event.kind &&
                    it.entityId == event.entityId
            }.toMutableList()
        } else {
            existing.toMutableList()
        }
        current += event
        val bounded = current.takeLast(SafetyPolicy.MAX_SHARED_STATE_OUTBOX_EVENTS)
        saveEvents(KEY_OUTBOX, KEY_OUTBOX_BACKUP, bounded)
        event
    }

    fun knownRevision(): Long = synchronized(lock) {
        prefs.getLong(KEY_KNOWN_REVISION, 0L).coerceAtLeast(0L)
    }

    fun lastSyncAt(): Long = synchronized(lock) {
        prefs.getLong(KEY_LAST_SYNC_AT, 0L).coerceAtLeast(0L)
    }

    fun outbox(): List<SharedStateEvent> = synchronized(lock) {
        loadEvents(KEY_OUTBOX, KEY_OUTBOX_BACKUP)
    }

    fun cachedEvents(): List<SharedStateEvent> = synchronized(lock) {
        loadEvents(KEY_CACHE, KEY_CACHE_BACKUP)
    }

    fun buildSyncRequest(
        identityId: String,
        replicaId: String
    ): SharedStateSyncEnvelope = synchronized(lock) {
        val pending = loadEvents(KEY_OUTBOX, KEY_OUTBOX_BACKUP)
            .take(SafetyPolicy.MAX_SHARED_STATE_SYNC_EVENTS)
        val knownRevision = prefs.getLong(KEY_KNOWN_REVISION, 0L)
            .coerceAtLeast(0L)
        SharedStateSyncEnvelope(
            payload = SharedGenesisStateProtocol.buildSyncRequest(
                identityId = identityId,
                replicaId = replicaId,
                knownRevision = knownRevision,
                events = pending
            ),
            uploadedEventIds = pending.map { it.eventId }.toSet(),
            uploadedEventCount = pending.size,
            knownRevision = knownRevision
        )
    }

    fun applySyncResponse(
        raw: String,
        expectedIdentityId: String,
        expectedReplicaId: String,
        uploadedEventIds: Set<String>,
        nodeId: String,
        nodeName: String,
        syncedAt: Long = System.currentTimeMillis()
    ): SharedStateSyncResult = synchronized(lock) {
        val response = SharedGenesisStateProtocol.parseSyncResponse(
            raw = raw,
            expectedIdentityId = expectedIdentityId,
            expectedReplicaId = expectedReplicaId
        )
        val previousRevision = prefs.getLong(KEY_KNOWN_REVISION, 0L)
            .coerceAtLeast(0L)
        require(response.resetRequired || response.serverRevision >= previousRevision) {
            "Le serveur Shared State a reculé de révision sans demander de reset."
        }

        val acknowledged = response.acknowledgedEventIds intersect uploadedEventIds
        val pending = loadEvents(KEY_OUTBOX, KEY_OUTBOX_BACKUP)
            .filterNot { it.eventId in acknowledged }
        saveEvents(
            KEY_OUTBOX,
            KEY_OUTBOX_BACKUP,
            pending.takeLast(SafetyPolicy.MAX_SHARED_STATE_OUTBOX_EVENTS)
        )

        val currentCache = if (response.resetRequired) {
            response.snapshot
        } else {
            mergeCache(
                loadEvents(KEY_CACHE, KEY_CACHE_BACKUP),
                response.events
            )
        }
        saveEvents(
            KEY_CACHE,
            KEY_CACHE_BACKUP,
            currentCache.takeLast(SafetyPolicy.MAX_SHARED_STATE_CACHE_EVENTS)
        )

        prefs.edit()
            .putLong(KEY_KNOWN_REVISION, response.serverRevision)
            .putLong(KEY_LAST_SYNC_AT, syncedAt.coerceAtLeast(0L))
            .apply()

        SharedStateSyncResult(
            nodeId = nodeId,
            nodeName = nodeName,
            serverRevision = response.serverRevision,
            serverHeadRevision = response.serverHeadRevision,
            uploadedEvents = acknowledged.size,
            receivedEvents = if (response.resetRequired) {
                response.snapshot.size
            } else {
                response.events.size
            },
            outboxRemaining = pending.size,
            hasMore = response.hasMore,
            resetApplied = response.resetRequired,
            syncedAt = syncedAt.coerceAtLeast(0L)
        )
    }

    private fun mergeCache(
        current: List<SharedStateEvent>,
        incoming: List<SharedStateEvent>
    ): List<SharedStateEvent> {
        if (incoming.isEmpty()) return current
        val byId = linkedMapOf<String, SharedStateEvent>()
        (current + incoming).forEach { event ->
            val previous = byId[event.eventId]
            if (previous == null || event.serverRevision >= previous.serverRevision) {
                byId[event.eventId] = event
            }
        }
        return byId.values.sortedWith(
            compareBy<SharedStateEvent> { it.serverRevision }
                .thenBy { it.createdAt }
                .thenBy { it.eventId }
        )
    }

    private fun loadEvents(primaryKey: String, backupKey: String): List<SharedStateEvent> {
        val primary = prefs.getString(primaryKey, null)
        if (!primary.isNullOrBlank()) {
            runCatching { SharedGenesisStateProtocol.decodeEvents(primary) }
                .getOrNull()
                ?.let { return it }
        }
        val backup = prefs.getString(backupKey, null)
        if (!backup.isNullOrBlank()) {
            runCatching { SharedGenesisStateProtocol.decodeEvents(backup) }
                .getOrNull()
                ?.let { recovered ->
                    prefs.edit().putString(
                        primaryKey,
                        SharedGenesisStateProtocol.encodeEvents(recovered)
                    ).apply()
                    return recovered
                }
        }
        return emptyList()
    }

    private fun saveEvents(
        primaryKey: String,
        backupKey: String,
        events: List<SharedStateEvent>
    ) {
        val old = prefs.getString(primaryKey, null)
        val encoded = SharedGenesisStateProtocol.encodeEvents(events)
        val editor = prefs.edit()
        if (!old.isNullOrBlank()) editor.putString(backupKey, old)
        editor.putString(primaryKey, encoded).apply()
    }

    companion object {
        private const val PREFS_NAME = "jade_shared_genesis_state"
        private const val KEY_OUTBOX = "outbox_v1"
        private const val KEY_OUTBOX_BACKUP = "outbox_v1_backup"
        private const val KEY_CACHE = "cache_v1"
        private const val KEY_CACHE_BACKUP = "cache_v1_backup"
        private const val KEY_KNOWN_REVISION = "known_server_revision_v1"
        private const val KEY_LAST_SYNC_AT = "last_sync_at_v1"
        private val COALESCED_OPERATIONAL_KINDS = setOf(
            "identity_presence",
            "config_snapshot",
            "phone_node_snapshot",
            "memory_cursor",
            "resource_lease_snapshot"
        )
    }
}

data class SharedStateSyncEnvelope(
    val payload: String,
    val uploadedEventIds: Set<String>,
    val uploadedEventCount: Int,
    val knownRevision: Long
)
