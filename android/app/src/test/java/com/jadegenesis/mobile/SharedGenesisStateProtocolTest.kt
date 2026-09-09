package com.jadegenesis.mobile

import com.jadegenesis.mobile.state.SharedGenesisStateProtocol
import com.jadegenesis.mobile.state.SharedStateEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedGenesisStateProtocolTest {
    @Test
    fun requestCarriesIdentityRevisionAndOutbox() {
        val event = SharedStateEvent(
            eventId = "state-1",
            originNode = "pixel-1",
            kind = "identity_presence",
            entityId = "jade-1",
            payload = "{\"version\":\"0.1.7.5\"}",
            createdAt = 1234L
        )

        val raw = SharedGenesisStateProtocol.buildSyncRequest(
            identityId = "jade-1",
            replicaId = "pixel-1",
            knownRevision = 7L,
            events = listOf(event)
        )
        val json = JSONObject(raw)

        assertEquals(1, json.getInt("schema_version"))
        assertEquals("jade-1", json.getString("identity_id"))
        assertEquals("pixel-1", json.getString("replica_id"))
        assertEquals(7L, json.getLong("known_revision"))
        assertEquals(1, json.getJSONArray("events").length())
    }

    @Test
    fun responseIsBoundToExpectedIdentityAndReplica() {
        val event = JSONObject().apply {
            put("event_id", "remote-1")
            put("origin_node", "vps-1")
            put("kind", "node_snapshot")
            put("entity_id", "vps-1")
            put("payload", "{}")
            put("created_at", 500L)
            put("server_revision", 8L)
        }
        val raw = JSONObject().apply {
            put("schema_version", 1)
            put("identity_id", "jade-1")
            put("replica_id", "pixel-1")
            put("server_revision", 8L)
            put("ack_event_ids", JSONArray().put("state-1"))
            put("events", JSONArray().put(event))
            put("reset_required", false)
            put("snapshot", JSONArray())
            put("server_updated_at", 600L)
        }.toString()

        val parsed = SharedGenesisStateProtocol.parseSyncResponse(
            raw = raw,
            expectedIdentityId = "jade-1",
            expectedReplicaId = "pixel-1"
        )

        assertEquals(8L, parsed.serverRevision)
        assertEquals(setOf("state-1"), parsed.acknowledgedEventIds)
        assertEquals(1, parsed.events.size)
        assertEquals(8L, parsed.events.single().serverRevision)
        assertFalse(parsed.resetRequired)
    }

    @Test
    fun resetResponseCanCarryCompactedSnapshot() {
        val snapshot = JSONObject().apply {
            put("event_id", "snapshot-1")
            put("origin_node", "pixel-1")
            put("kind", "config_snapshot")
            put("entity_id", "active")
            put("payload", "{\"revision\":3}")
            put("created_at", 700L)
            put("server_revision", 20L)
        }
        val raw = JSONObject().apply {
            put("schema_version", 1)
            put("identity_id", "jade-1")
            put("replica_id", "pixel-1")
            put("server_revision", 20L)
            put("ack_event_ids", JSONArray())
            put("events", JSONArray())
            put("reset_required", true)
            put("snapshot", JSONArray().put(snapshot))
            put("server_updated_at", 800L)
        }.toString()

        val parsed = SharedGenesisStateProtocol.parseSyncResponse(
            raw = raw,
            expectedIdentityId = "jade-1",
            expectedReplicaId = "pixel-1"
        )

        assertTrue(parsed.resetRequired)
        assertEquals(1, parsed.snapshot.size)
        assertEquals("config_snapshot", parsed.snapshot.single().kind)
    }

    @Test(expected = IllegalArgumentException::class)
    fun responseFromAnotherIdentityIsRejected() {
        val raw = JSONObject().apply {
            put("schema_version", 1)
            put("identity_id", "other-jade")
            put("replica_id", "pixel-1")
            put("server_revision", 1L)
            put("ack_event_ids", JSONArray())
            put("events", JSONArray())
            put("reset_required", false)
            put("snapshot", JSONArray())
        }.toString()

        SharedGenesisStateProtocol.parseSyncResponse(
            raw = raw,
            expectedIdentityId = "jade-1",
            expectedReplicaId = "pixel-1"
        )
    }
}
