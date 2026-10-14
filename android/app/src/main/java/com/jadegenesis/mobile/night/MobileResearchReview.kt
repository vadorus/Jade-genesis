package com.jadegenesis.mobile.night

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal fun researchSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal data class ResearchPassage(val block: Int, val hash: String, val text: String, val caveat: Boolean)
internal data class ResearchSource(val key: String, val url: String, val hash: String, val passages: List<ResearchPassage>, val title: String = key)
internal data class ResearchArtifact(val fbxHash: String, val reportHash: String, val bytes: Int, val version: String)
internal data class MobileResearchDossier(
    val raw: String, val snapshotHash: String, val goal: String,
    val sources: List<ResearchSource>, val artifacts: List<ResearchArtifact>, val packet: String,
    val experiments: List<NightProposedExperiment>, val scope: String = "LEGACY_TEST_DOSSIER"
)

internal object MobileResearchDossierParser {
    const val MAX_BYTES = 300_000

    fun parse(raw: String): MobileResearchDossier {
        require(raw.toByteArray(Charsets.UTF_8).size in 10..MAX_BYTES) { "Dossier trop volumineux" }
        val root = JSONObject(raw)
        if (root.optString("schema") == "jade.mobile_learning_review.v1") return JadeLearningReviewParser.parse(raw, root)
        require(root.getString("schema") == "jade.mobile_research_dossier.v1")
        require(root.opt("verified") == false && root.opt("action_authorized") == false &&
            root.opt("automatic_promotion") == false) { "Le dossier ne peut pas accorder une autorisation" }
        val goal = root.getString("goal")
        require(goal.isNotBlank() && goal.length <= 320)
        val packet = root.getJSONObject("review_packet")
        val proposal = NightResearchPacketParser.parse(packet.toString())
        require(researchSha(goal.toByteArray(Charsets.UTF_8)) == proposal.goalSha256) { "Objectif modifié" }
        val refs = packet.getJSONArray("source_references")
        val texts = root.getJSONArray("source_texts")
        require(texts.length() == refs.length())
        val sources = (0 until refs.length()).map { index ->
            val ref = refs.getJSONObject(index)
            val text = texts.getJSONObject(index)
            require(text.getString("source_key") == ref.getString("source_key"))
            val excerpts = ref.getJSONArray("excerpts")
            val passages = text.getJSONArray("passages")
            require(excerpts.length() == passages.length())
            val caveats = ref.getJSONArray("caveat_excerpt_hashes")
            val caveatHashes = (0 until caveats.length()).map { caveats.getString(it) }.toSet()
            ResearchSource(ref.getString("source_key"), ref.getString("url"), ref.getString("sha256"),
                (0 until passages.length()).map { j ->
                    val p = passages.getJSONObject(j)
                    val expected = excerpts.getJSONObject(j)
                    val body = p.getString("text")
                    require(body.length in 1..380 && p.opt("untrusted") == true)
                    require(p.getInt("block_index") == expected.getInt("block_index"))
                    val hash = researchSha(body.toByteArray(Charsets.UTF_8))
                    require(hash == p.getString("excerpt_sha256") && hash == expected.getString("excerpt_sha256")) { "Passage modifié" }
                    ResearchPassage(p.getInt("block_index"), hash, body, hash in caveatHashes)
                }, ref.getString("source_key"))
        }
        val runs = packet.getJSONArray("experiment_artifacts")
        val blobs = root.getJSONArray("local_artifacts")
        require(blobs.length() == runs.length())
        val artifacts = (0 until runs.length()).map { index ->
            val ref = runs.getJSONObject(index)
            val blob = blobs.getJSONObject(index)
            val reportBytes = Base64.getDecoder().decode(blob.getString("report_base64"))
            val fbx = Base64.getDecoder().decode(blob.getString("fbx_base64"))
            require(reportBytes.size in 10..12_000 && fbx.size in 100..2_097_152)
            require(researchSha(reportBytes) == ref.getString("report_sha256")) { "Rapport modifié" }
            require(researchSha(fbx) == ref.getString("fbx_sha256")) { "Fichier FBX modifié" }
            val signature = "Kaydara FBX Binary  ".toByteArray()
            require(fbx.take(signature.size).toByteArray().contentEquals(signature))
            val report = JSONObject(reportBytes.toString(Charsets.UTF_8))
            require(report.getString("schema") == "jade.local_blender_fbx_probe.v1" && report.getString("status") == "PASS")
            require(report.getString("experiment") == "synthetic FBX roundtrip inside Blender only")
            require(report.getString("fbx_sha256") == ref.getString("fbx_sha256") && report.getInt("fbx_bytes") == fbx.size)
            val version = report.getString("blender_version")
            require(version.startsWith("5.2."))
            val validators = report.getJSONObject("validations")
            val required = setOf("two_mesh_objects", "vertex_count_preserved", "triangles_preserved", "bounding_dimensions_preserved")
            require(validators.keys().asSequence().toSet() == required && required.all { validators.opt(it) == true })
            require(geometry(report.getJSONArray("geometry_before")) == geometry(report.getJSONArray("geometry_after")))
            ResearchArtifact(ref.getString("fbx_sha256"), ref.getString("report_sha256"), fbx.size, version)
        }
        return MobileResearchDossier(raw, researchSha(raw.toByteArray(Charsets.UTF_8)), goal, sources, artifacts,
            packet.toString(), proposal.experiments)
    }

    private fun geometry(rows: JSONArray): Map<String, List<Double>> {
        require(rows.length() == 2)
        val expected = mapOf("SyntheticTrunk" to listOf(0.46, 0.46, 2.0), "SyntheticCanopy" to listOf(1.8, 1.8, 2.4))
        val result = mutableMapOf<String, List<Double>>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val name = row.getString("name")
            require(name in expected && name !in result && row.getInt("vertices") == 24 && row.getInt("triangles") == 44)
            val dims = row.getJSONArray("dimensions")
            require(dims.length() == 3)
            val signature = (0..2).map { dims.getDouble(it) }.sorted()
            require(signature.zip(expected.getValue(name)).all { (actual, wanted) -> actual.isFinite() && kotlin.math.abs(actual - wanted) < 0.0001 })
            result[name] = expected.getValue(name)
        }
        return result
    }
}

internal object MobileResearchAuthorization {
    const val ACTION = "PIXEL_LOCAL_EVIDENCE_RECHECK"
    const val TTL = 15 * 60 * 1000L
    fun planHash(snapshot: String): String = researchSha(
        "$ACTION|$snapshot|LOCAL_PRIVATE_STORAGE|NO_NETWORK|NO_PC_TOOL|NO_PROMOTION".toByteArray()
    )

    fun grant(snapshot: String, reviewer: String, note: String, confirmed: Boolean, now: Long): JSONObject {
        require(Regex("[0-9a-f]{64}").matches(snapshot))
        require(confirmed && reviewer.trim().length in 1..80 && note.trim().length in 3..500) { "Indique ton nom, un motif et confirme le périmètre" }
        return JSONObject().put("kind", "GRANTED").put("authorization_id", UUID.randomUUID().toString())
            .put("snapshot_sha256", snapshot).put("plan_sha256", planHash(snapshot))
            .put("reviewer", reviewer.trim()).put("note", note.trim()).put("at_ms", now)
            .put("expires_at_ms", now + TTL).put("action_id", ACTION).put("confirmed", true)
    }

    fun pending(events: List<JSONObject>, id: String, snapshot: String, now: Long): JSONObject {
        val related = events.filter { it.optString("authorization_id") == id }
        require(related.isNotEmpty() && related.last().getString("kind") == "GRANTED") { "Autorisation absente, révoquée ou déjà utilisée" }
        val grant = related.first()
        require(grant.getString("snapshot_sha256") == snapshot && grant.getString("plan_sha256") == planHash(snapshot)) { "Le dossier a changé : nouvelle revue nécessaire" }
        require(now >= grant.getLong("at_ms") && now < grant.getLong("expires_at_ms")) { "Autorisation expirée" }
        return grant
    }
}

/** Pure journal validation: inspection never changes an execution permission. */
internal object MobileResearchJournal {
    private val hash = Regex("[0-9a-f]{64}")
    val stances = setOf("SUPPORTS", "CONTRADICTS", "UNCERTAIN")

    fun inspection(snapshot: String, source: String, reviewer: String, note: String, stance: String, now: Long): JSONObject {
        require(hash.matches(snapshot) && hash.matches(source) && stance in stances)
        require(reviewer.trim().length in 1..80 && note.trim().length in 3..500) { "Indique ton nom et un motif de revue" }
        return JSONObject().put("kind", "INSPECTION_RECORDED").put("snapshot_sha256", snapshot)
            .put("source_sha256", source).put("reviewer", reviewer.trim()).put("note", note.trim())
            .put("stance", stance).put("at_ms", now).put("knowledge_verified", false).put("automatic_promotion", false)
    }

    fun parse(raw: String): List<JSONObject> {
        require(raw.toByteArray(Charsets.UTF_8).size <= 300_000)
        val root = JSONObject(raw)
        require(root.getString("schema") == "jade.mobile_research_audit.v1")
        val rows = root.getJSONArray("events")
        require(rows.length() <= 256)
        var previous = "0".repeat(64)
        var importedSnapshot: String? = null
        val states = mutableMapOf<String, String>()
        val grants = mutableMapOf<String, JSONObject>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val body = JSONObject(row.toString()).apply { remove("sha256") }
            require(row.getInt("sequence") == index + 1 && row.getString("previous_sha256") == previous &&
                row.getString("sha256") == researchSha(canonical(body).toByteArray(Charsets.UTF_8))) { "Journal altéré : actions bloquées" }
            previous = row.getString("sha256")
            require(row.getLong("at_ms") >= 0)
            val kind = row.getString("kind")
            val id = row.optString("authorization_id")
            when (kind) {
                "DOSSIER_IMPORTED" -> {
                    importedSnapshot = row.getString("snapshot_sha256")
                    require(hash.matches(importedSnapshot!!))
                }
                "INSPECTION_RECORDED" -> {
                    require(row.getString("snapshot_sha256") == importedSnapshot)
                    inspection(row.getString("snapshot_sha256"), row.getString("source_sha256"),
                        row.getString("reviewer"), row.getString("note"), row.getString("stance"), row.getLong("at_ms"))
                    require(row.opt("knowledge_verified") == false && row.opt("automatic_promotion") == false)
                }
                "GRANTED" -> {
                    require(id.isNotBlank() && id !in states && row.getString("action_id") == MobileResearchAuthorization.ACTION)
                    val snapshot = row.getString("snapshot_sha256")
                    require(hash.matches(snapshot) && snapshot == importedSnapshot)
                    require(row.getString("plan_sha256") == MobileResearchAuthorization.planHash(snapshot))
                    require(row.getLong("expires_at_ms") - row.getLong("at_ms") == MobileResearchAuthorization.TTL)
                    require(row.getString("reviewer").trim().length in 1..80 && row.getString("note").trim().length in 3..500)
                    require(!row.has("confirmed") || row.opt("confirmed") == true) // Older v1 records predate this field.
                    states[id] = kind; grants[id] = row
                }
                "REVOKED", "STARTED" -> {
                    require(states[id] == "GRANTED")
                    if (kind == "STARTED") {
                        MobileResearchAuthorization.pending(listOf(grants.getValue(id)), id,
                            importedSnapshot ?: error("Dossier absent du journal"), row.getLong("at_ms"))
                    }
                    states[id] = kind
                }
                "COMPLETED", "FAILED" -> {
                    require(states[id] == "STARTED")
                    if (kind == "COMPLETED") {
                        require(row.getString("snapshot_sha256") == grants.getValue(id).getString("snapshot_sha256"))
                        require(row.opt("knowledge_verified") == false && row.opt("automatic_promotion") == false)
                        require(row.getString("scope") == "PIXEL_LOCAL_IMPORTED_BYTES_ONLY")
                    }
                    states[id] = kind
                }
                else -> error("Événement de revue inconnu")
            }
            row
        }
    }
}

internal class MobileResearchReviewStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "research_review_v1")
    private val dossierFile = File(directory, "dossier.json")
    private val journalFile = File(directory, "journal.json")
    companion object { private val lock = Any() }

    private fun read(file: File, maximum: Int): String = AtomicFile(file).openRead().use {
        val buffer = ByteArray(maximum + 1)
        var length = 0
        while (length < buffer.size) {
            val count = it.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        val bytes = buffer.copyOf(length)
        require(bytes.size <= maximum) { "Fichier de revue trop volumineux" }
        bytes.toString(Charsets.UTF_8)
    }
    private fun write(file: File, text: String) {
        check(directory.isDirectory || directory.mkdirs())
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    fun dossier(): MobileResearchDossier? = synchronized(lock) {
        if (!dossierFile.exists() && !File(dossierFile.path + ".bak").exists()) null
        else MobileResearchDossierParser.parse(read(dossierFile, MobileResearchDossierParser.MAX_BYTES))
    }

    fun importDossier(raw: String): MobileResearchDossier = synchronized(lock) {
        val parsed = MobileResearchDossierParser.parse(raw)
        val events = events()
        require(events.size < 250) { "Journal complet" }
        if (dossierFile.exists() || File(dossierFile.path + ".bak").exists()) {
            val previous = read(dossierFile, MobileResearchDossierParser.MAX_BYTES)
            if (previous != raw) write(File(directory, "archive/${researchSha(previous.toByteArray(Charsets.UTF_8))}.json"), previous)
        }
        write(dossierFile, raw)
        append(JSONObject().put("kind", "DOSSIER_IMPORTED").put("snapshot_sha256", parsed.snapshotHash)
            .put("at_ms", System.currentTimeMillis()))
        // Existing Night Cycle still receives only the provisional summary.
        NightResearchProposalInbox(context).importPacket(parsed.packet)
        parsed
    }

    // Context retained only for the legacy summary bridge, never a network API.
    private val context = context.applicationContext

    fun events(): List<JSONObject> = synchronized(lock) {
        if (!journalFile.exists() && !File(journalFile.path + ".bak").exists()) return@synchronized emptyList()
        MobileResearchJournal.parse(read(journalFile, 300_000))
    }

    private fun append(event: JSONObject): JSONObject {
        val previous = events()
        require(previous.size < 256) { "Journal complet" }
        event.put("sequence", previous.size + 1).put("previous_sha256", previous.lastOrNull()?.getString("sha256") ?: "0".repeat(64))
        event.put("sha256", researchSha(canonical(event).toByteArray(Charsets.UTF_8)))
        val text = JSONObject().put("schema", "jade.mobile_research_audit.v1").put("events", JSONArray(previous + event)).toString()
        MobileResearchJournal.parse(text) // Reject impossible transitions before persisting or running.
        write(journalFile, text)
        return event
    }

    fun grant(expectedSnapshot: String, reviewer: String, note: String, confirmed: Boolean): JSONObject = synchronized(lock) {
        val current = dossier() ?: error("Importer un dossier avant d'autoriser")
        require(current.snapshotHash == expectedSnapshot) { "Le dossier a changé : relis-le avant d'autoriser" }
        requireImported(current.snapshotHash)
        append(MobileResearchAuthorization.grant(current.snapshotHash, reviewer, note, confirmed, System.currentTimeMillis()))
    }

    private fun requireImported(snapshot: String) {
        require(events().lastOrNull { it.getString("kind") == "DOSSIER_IMPORTED" }?.getString("snapshot_sha256") == snapshot) {
            "Import incomplet : réimporte le dossier avant de décider"
        }
    }

    fun inspect(expectedSnapshot: String, sourceHash: String, reviewer: String, note: String, stance: String): JSONObject = synchronized(lock) {
        val current = dossier() ?: error("Aucun dossier")
        require(current.snapshotHash == expectedSnapshot) { "Le dossier a changé : relis-le" }
        requireImported(current.snapshotHash)
        require(current.sources.any { it.hash == sourceHash }) { "Source absente du dossier" }
        append(MobileResearchJournal.inspection(current.snapshotHash, sourceHash, reviewer, note, stance, System.currentTimeMillis()))
    }

    fun revoke(id: String): JSONObject = synchronized(lock) {
        require(events().lastOrNull { it.optString("authorization_id") == id }?.getString("kind") == "GRANTED")
        append(JSONObject().put("kind", "REVOKED").put("authorization_id", id).put("at_ms", System.currentTimeMillis()))
    }

    fun execute(id: String, expectedSnapshot: String): JSONObject = synchronized(lock) {
        val current = dossier() ?: error("Aucun dossier")
        require(current.snapshotHash == expectedSnapshot) { "Le dossier a changé" }
        requireImported(current.snapshotHash)
        val rows = events()
        MobileResearchAuthorization.pending(rows, id, current.snapshotHash, System.currentTimeMillis())
        require(rows.size <= 253) { "Pas assez de place pour tracer l'exécution" }
        append(JSONObject().put("kind", "STARTED").put("authorization_id", id).put("at_ms", System.currentTimeMillis()))
        try {
            // Real checks run on the Pixel's imported bytes, without any native
            // process, network, PC/VPS task or memory/evidence-ledger mutation.
            val checked = dossier() ?: error("Dossier disparu")
            require(checked.snapshotHash == current.snapshotHash)
            append(JSONObject().put("kind", "COMPLETED").put("authorization_id", id)
                .put("at_ms", System.currentTimeMillis()).put("source_count", checked.sources.size)
                .put("passage_count", checked.sources.sumOf { it.passages.size })
                .put("fbx_count", checked.artifacts.size).put("snapshot_sha256", checked.snapshotHash)
                .put("artifact_count", checked.artifacts.size).put("proposal_count", checked.experiments.size)
                .put("knowledge_verified", false).put("automatic_promotion", false)
                .put("scope", "PIXEL_LOCAL_IMPORTED_BYTES_ONLY"))
        } catch (error: Exception) {
            append(JSONObject().put("kind", "FAILED").put("authorization_id", id)
                .put("at_ms", System.currentTimeMillis()).put("error_type", error.javaClass.simpleName))
            throw error
        }
    }
}

internal fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Boolean, is Number -> value.toString()
    else -> error("Valeur JSON inconnue")
}
