package com.jadegenesis.mobile.night

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileResearchJournalTest {
    private val snapshot = "a".repeat(64)
    private val source = "b".repeat(64)
    private fun imported(hash: String = snapshot) = JSONObject().put("kind", "DOSSIER_IMPORTED")
        .put("snapshot_sha256", hash).put("at_ms", 1000)
    private fun grant() = MobileResearchAuthorization.grant(snapshot, "Synthetic reviewer", "TEST local only", true, 1001)
    private fun transition(kind: String, id: String, time: Long = 1002) = JSONObject().put("kind", kind)
        .put("authorization_id", id).put("at_ms", time)
    private fun journal(vararg events: JSONObject): String {
        var previous = "0".repeat(64)
        val rows = events.mapIndexed { index, event ->
            val row = JSONObject(event.toString()).apply { remove("sha256") }
            row.put("sequence", index + 1).put("previous_sha256", previous)
            previous = researchSha(canonical(row).toByteArray(Charsets.UTF_8))
            row.put("sha256", previous)
        }
        return JSONObject().put("schema", "jade.mobile_research_audit.v1").put("events", JSONArray(rows)).toString()
    }
    private fun rejected(vararg events: JSONObject) = assertTrue(runCatching { MobileResearchJournal.parse(journal(*events)) }.isFailure)

    @Test fun inspectionPersistsAcrossReloadAndNeverGrantsPermission() {
        val event = MobileResearchJournal.inspection(snapshot, source, "Synthetic reviewer", "TEST uncertain", "UNCERTAIN", 1001)
        val raw = journal(imported(), event)
        val loaded = MobileResearchJournal.parse(raw)
        assertEquals("UNCERTAIN", loaded.last().getString("stance"))
        assertFalse(loaded.last().getBoolean("knowledge_verified"))
        assertFalse(loaded.last().getBoolean("automatic_promotion"))
        assertEquals(loaded.last().toString(), MobileResearchJournal.parse(raw).last().toString())
        assertTrue(runCatching { MobileResearchAuthorization.pending(loaded, "invented", snapshot, 1002) }.isFailure)
    }
    @Test fun inspectionDoesNotConsumeAnExistingPermission() {
        val permission = grant()
        val rows = MobileResearchJournal.parse(journal(imported(), permission,
            MobileResearchJournal.inspection(snapshot, source, "Synthetic reviewer", "TEST contrary", "CONTRADICTS", 1002)))
        assertEquals(permission.getString("authorization_id"), MobileResearchAuthorization.pending(rows,
            permission.getString("authorization_id"), snapshot, 1003).getString("authorization_id"))
    }
    @Test fun inspectionRequiresSnapshotIdentityReasonAndKnownStance() {
        for ((name, note, stance) in listOf(Triple("", "TEST reason", "UNCERTAIN"),
            Triple("Test", "", "SUPPORTS"), Triple("Test", "TEST reason", "VERIFIED"))) {
            assertTrue(runCatching { MobileResearchJournal.inspection(snapshot, source, name, note, stance, 1001) }.isFailure)
        }
        val event = MobileResearchJournal.inspection(snapshot, source, "Test", "TEST reason", "SUPPORTS", 1001)
        rejected(imported("c".repeat(64)), event)
        rejected(imported(), JSONObject(event.toString()).put("automatic_promotion", true))
    }
    @Test fun ordinaryJournalTamperingBlocksAllReads() {
        val raw = JSONObject(journal(imported(), grant()))
        raw.getJSONArray("events").getJSONObject(1).put("note", "altered")
        assertTrue(runCatching { MobileResearchJournal.parse(raw.toString()) }.isFailure)
    }
    @Test fun evenRecomputedChainsRejectImpossibleOrUnconfirmedGrants() {
        rejected(grant())
        rejected(imported("c".repeat(64)), grant())
        rejected(imported(), grant().put("confirmed", false))
        rejected(imported(), grant().put("action_id", "RUN_BLENDER"))
    }
    @Test fun replacedDossierAndExpiredPermissionCannotStart() {
        val permission = grant(); val id = permission.getString("authorization_id")
        rejected(imported(), permission, imported("c".repeat(64)), transition("STARTED", id))
        rejected(imported(), permission, transition("STARTED", id, permission.getLong("expires_at_ms")))
    }
    @Test fun revokedPermissionCannotStartAndStartedPermissionCannotReplay() {
        val permission = grant(); val id = permission.getString("authorization_id")
        rejected(imported(), permission, transition("REVOKED", id), transition("STARTED", id))
        val loaded = MobileResearchJournal.parse(journal(imported(), permission, transition("STARTED", id)))
        assertTrue(runCatching { MobileResearchAuthorization.pending(loaded, id, snapshot, 1003) }.isFailure)
        rejected(imported(), permission, transition("STARTED", id), transition("STARTED", id))
    }
    @Test fun completionRequiresStartedSameSnapshotAndNoPromotion() {
        val permission = grant(); val id = permission.getString("authorization_id")
        val result = transition("COMPLETED", id, 1003).put("snapshot_sha256", snapshot)
            .put("knowledge_verified", false).put("automatic_promotion", false).put("scope", "PIXEL_LOCAL_IMPORTED_BYTES_ONLY")
        val loaded = MobileResearchJournal.parse(journal(imported(), permission, transition("STARTED", id), result))
        assertEquals("COMPLETED", loaded.last().getString("kind"))
        rejected(imported(), permission, result)
        rejected(imported(), permission, transition("STARTED", id), JSONObject(result.toString()).put("snapshot_sha256", source))
        rejected(imported(), permission, transition("STARTED", id), JSONObject(result.toString()).put("knowledge_verified", true))
    }
}
