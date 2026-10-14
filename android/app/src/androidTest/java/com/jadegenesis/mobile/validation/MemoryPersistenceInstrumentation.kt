package com.jadegenesis.mobile.validation

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Rect
import com.jadegenesis.mobile.MainActivity
import com.jadegenesis.mobile.night.MobileResearchReviewStore
import com.jadegenesis.mobile.night.MobileResearchDossierParser
import com.jadegenesis.mobile.night.MobileResearchAuthorization
import com.jadegenesis.mobile.tools.JsonFileLearningWorkshop
import com.jadegenesis.mobile.ui.JsonToolWorkshopActivity
import com.jadegenesis.mobile.core.JadeCore
import java.io.File
import com.jadegenesis.mobile.memory.JadeDatabase
import com.jadegenesis.mobile.memory.MemoryStore
import com.jadegenesis.mobile.model.MemoryType
import com.jadegenesis.mobile.night.NightMemoryReviewPlanner
import com.jadegenesis.mobile.night.NightReviewAgendaStore
import com.jadegenesis.mobile.night.NightEvidenceLedger
import com.jadegenesis.mobile.night.NightEvidenceReviewer
import com.jadegenesis.mobile.night.OfficialSourceCaptureInbox
import com.jadegenesis.mobile.night.NightResearchProposalInbox
import com.jadegenesis.mobile.night.NightReviewCandidate
import com.jadegenesis.mobile.night.ReviewEvidence
import com.jadegenesis.mobile.night.ReviewDecision
import com.jadegenesis.mobile.night.EvidenceType
import com.jadegenesis.mobile.night.EvidenceDirection
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/**
 * Runs ONLY against com.jadegenesis.mobile.validation.
 * Stage 1 writes synthetic facts; stage 2 checks after process termination;
 * stage 3 removes only records created by this probe.
 */
class MemoryPersistenceInstrumentation : Instrumentation() {
    private var phase = "seed"
    private var sourceManifestBase64: String = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "seed"
        sourceManifestBase64 = arguments?.getString("manifest_b64") ?: ""
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val packageName = targetContext.packageName
            check(packageName == "com.jadegenesis.mobile.validation") {
                "Refusing to run against a non-validation package: $packageName"
            }
            val summary = runBlocking {
                when (phase) {
                    "seed" -> seed()
                    "verify" -> verifyAfterRestart()
                    "cleanup" -> cleanup()
                    "evidence_seed" -> evidenceSeed()
                    "evidence_verify" -> evidenceVerify()
                    "evidence_cleanup" -> evidenceCleanup()
                    "source_import" -> sourceImport()
                    "source_verify" -> sourceVerify()
                    "source_cleanup" -> sourceCleanup()
                    "review_import" -> reviewImport()
                    "review_verify" -> reviewVerify()
                    "review_cleanup" -> reviewCleanup()
                    "mobile_review_import" -> mobileReviewImport()
                    "mobile_review_ui" -> mobileReviewUi()
                    "mobile_review_inspection" -> mobileReviewInspection()
                    "mobile_review_verify" -> mobileReviewVerify()
                    "jade_learning_review" -> jadeLearningReview()
                    "jade_learning_restart" -> jadeLearningRestart()
                    "chat_availability_diagnostic" -> chatAvailabilityDiagnostic()
                    "json_tool_learn" -> jsonToolLearn()
                    "json_tool_reuse" -> jsonToolReuse()
                    else -> error("Unknown phase $phase")
                }
            }
            Log.i("JadeValidationProbe", "PASS [$phase] $summary")
            result.putString("stream", "PASS [$phase] $summary\n")
            finish(0, result)
        } catch (problem: Throwable) {
            Log.e("JadeValidationProbe", "FAIL [$phase]", problem)
            result.putString("stream", "FAIL [$phase]: ${problem.javaClass.simpleName}: ${problem.message}\n")
            finish(-1, result)
        }
    }

    private fun preferences() = targetContext.getSharedPreferences(
        "jade_validation_probe_v1", android.content.Context.MODE_PRIVATE
    )

    private fun mobilePreferences() = targetContext.getSharedPreferences("jade_validation_mobile_review_v1", android.content.Context.MODE_PRIVATE)

    private fun jsonToolLearn(): String {
        val workshop = JsonFileLearningWorkshop(targetContext)
        check(workshop.catalog() == null) { "JSON tool already acquired; preserve prior proof instead of reseeding" }
        check(runCatching { workshop.filePlan() }.isFailure)
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Atelier d'outils JSON")
        clickUi("Charger les exemples de démonstration")
        clickUi("Construire l'outil depuis les exemples")
        awaitNode("Procédure candidate construite. Elle n'est pas encore un outil actif.")
        check(workshop.catalog() == null)
        clickUi("Autoriser la vérification"); clickUi("Autoriser ce plan")
        clickUi("Révoquer"); awaitNode("Autorisation révoquée.")
        val revoked = workshop.state().getJSONObject("grant").getString("id")
        check(runCatching { workshop.execute(revoked) }.isFailure)
        clickUi("Autoriser la vérification"); clickUi("Autoriser ce plan")
        clickUi("Exécuter le plan autorisé")
        awaitNode("Cas séparé réussi. Outil conservé.")
        val state = workshop.state()
        check(state.getJSONObject("retained").getString("status") == "VALIDATED_LOCALLY")
        check("learned_json_transform_v1" in JadeCore(targetContext).toolNames())
        val prefs = targetContext.getSharedPreferences("jade_validation_json_tool_v1", android.content.Context.MODE_PRIVATE)
        check(prefs.edit().putString("tool_sha256", state.getJSONObject("retained").getString("tool_sha256"))
            .putInt("candidate_events", (0 until state.getJSONArray("events").length()).count { state.getJSONArray("events").getJSONObject(it).getString("kind") == "CANDIDATE_CREATED" }).commit())
        saveMobileScreenshot("validation_json_tool_learned.png")
        return "native_UI=PASS, no_tool_before=true, created_from_2_TRAIN=true, separate_validation=PASS, revoked_permission_refused=true, registered_in_Jade=true"
    }

    private fun jsonToolReuse(): String {
        val workshop = JsonFileLearningWorkshop(targetContext)
        val prefs = targetContext.getSharedPreferences("jade_validation_json_tool_v1", android.content.Context.MODE_PRIVATE)
        val hash = prefs.getString("tool_sha256", null) ?: error("Complete the JSON learning phase first")
        check(workshop.state().getJSONObject("retained").getString("tool_sha256") == hash)
        check("learned_json_transform_v1" in JadeCore(targetContext).toolNames())
        val bytes = "{\"label\":\"nouveau fichier après redémarrage\",\"large\":9007199254740993,\"extra\":{\"enabled\":true}}".toByteArray(Charsets.UTF_8)
        workshop.importInput(bytes)
        check(runCatching { workshop.executeApprovedFile() }.isFailure)
        startActivitySync(Intent(targetContext, JsonToolWorkshopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Autoriser la copie transformée"); clickUi("Autoriser ce plan")
        clickUi("Exécuter le plan autorisé")
        awaitNode("Fichier transformé avec l'outil conservé")
        val state = workshop.state(); val result = state.getJSONObject("last_result")
        check(result.getString("tool_sha256") == hash && result.getString("selection") == "RETAINED_TOOL_AUTO_SELECTED" && result.getInt("resynthesis_calls") == 0 && result.getBoolean("original_unchanged"))
        val output = result.getString("output")
        check(output.contains("\"nom\": \"nouveau fichier après redémarrage\""))
        check(output.contains("9007199254740993") && output.contains("\"extra\""))
        val directory = File(targetContext.filesDir, "json_file_workshop_v1")
        check(File(directory, result.getString("output_file")).readText(Charsets.UTF_8) == output)
        val events = state.getJSONArray("events")
        check((0 until events.length()).count { events.getJSONObject(it).getString("kind") == "CANDIDATE_CREATED" } == prefs.getInt("candidate_events", -1))
        check(runCatching { workshop.execute(state.getJSONObject("grant").getString("id")) }.isFailure)
        saveMobileScreenshot("validation_json_tool_reused.png")
        return "real_restart_reuse=PASS, new_JSON_file_read_and_written=true, same_tool_hash=true, resynthesis_delta=0, large_integer_preserved=true, original_unchanged=true, permission_replay_refused=true"
    }

    private fun mobileReviewImport(): String {
        val file = File(targetContext.filesDir, "validation_mobile_dossier.json")
        check(file.length() in 10..MobileResearchDossierParser.MAX_BYTES.toLong())
        val raw = file.readText(Charsets.UTF_8)
        val store = MobileResearchReviewStore(targetContext)
        val expected = MobileResearchDossierParser.parse(raw)
        val old = store.dossier()
        check(old == null || old.snapshotHash == expected.snapshotHash) { "Refusing to overwrite a different mobile research dossier" }
        val before = NightEvidenceLedger(targetContext).evidence().size
        val grantsBefore = store.events().count { it.getString("kind") == "GRANTED" }
        val imported = store.importDossier(raw)
        check(imported.sources.size == 3 && imported.artifacts.size == 2 && imported.sources.sumOf { it.passages.size } == 15)
        check(mobilePreferences().edit().putString("snapshot", imported.snapshotHash).putInt("ledger_before", before).commit())
        check(store.events().count { it.getString("kind") == "GRANTED" } == grantsBefore) {
            "Import must not add a permission; historical decisions are retained"
        }
        return "real_sources=3, passages=15, actual_fbx_bytes=2, permission_imported=false"
    }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val found = findNode(root.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun awaitNode(label: String, description: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var lastScroll = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            // Compose can replace virtual nodes when the authorization form
            // changes height. Never reuse cached bounds/check state for clicks.
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            val root = uiAutomation.rootInActiveWindow
            val found = findNode(root) {
                if (description) it.contentDescription?.toString() == label else it.text?.toString() == label
            }
            if (found != null) return found
            if (SystemClock.uptimeMillis() - lastScroll > 600 && root?.packageName?.toString() == "com.jadegenesis.mobile.validation") {
                findNode(root) { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastScroll = SystemClock.uptimeMillis()
            }
            SystemClock.sleep(100)
        }
        val root = uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() == "com.jadegenesis.mobile.validation") {
            saveMobileScreenshot("validation_mobile_ui_failure.png")
            val trace = StringBuilder()
            fun dump(node: AccessibilityNodeInfo?) {
                if (node == null) return
                trace.append("${node.className} text=${node.text} description=${node.contentDescription} enabled=${node.isEnabled} actions=${node.actionList.map { it.id }}\n")
                for (i in 0 until node.childCount) dump(node.getChild(i))
            }
            dump(root)
            File(targetContext.filesDir, "validation_mobile_ui_failure.txt").writeText(trace.toString())
        }
        error("Native Jade TEST UI element missing: $label")
    }

    private fun clickUi(label: String, description: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var node: AccessibilityNodeInfo? = awaitNode(label, description)
            // A node can become visible while a lazy-list scroll is still in
            // flight. A tap then stops the scroll instead of pressing the button.
            try { uiAutomation.waitForIdle(300, 3000) } catch (_: java.util.concurrent.TimeoutException) { }
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            node = awaitNode(label, description)
            check(node?.packageName?.toString() == "com.jadegenesis.mobile.validation") { "UI click outside Jade TEST refused" }
            repeat(5) {
                if (node?.isEnabled == true && node?.actionList?.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } == true) {
                    val bounds = Rect(); node?.getBoundsInScreen(bounds)
                    check(!bounds.isEmpty && node?.isVisibleToUser == true)
                    // Invoke the native accessibility action on this visible TEST
                    // control. Coordinate taps can merely stop a lazy-list scroll.
                    check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Native TEST action refused: $label" }
                    SystemClock.sleep(350); return
                }
                node = node?.parent
            }
            SystemClock.sleep(100)
        }
        saveMobileScreenshot("validation_mobile_ui_failure.png")
        error("Jade TEST UI element not clickable: $label")
    }

    private fun checkUi(description: String) {
        if (!awaitNode(description, true).isChecked) clickUi(description, true)
    }

    private fun setUi(description: String, value: String) {
        val node = awaitNode(description, true)
        check(node.packageName?.toString() == "com.jadegenesis.mobile.validation")
        val editable = findNode(node) { candidate -> candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            ?: findNode(node.parent) { candidate -> candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            ?: error("No editable descendant in Jade TEST field $description (${node.className})")
        check(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Cannot edit Jade TEST field $description" }
        SystemClock.sleep(150)
    }

    private fun mobileReviewUi(): String {
        val store = MobileResearchReviewStore(targetContext)
        val current = store.dossier() ?: error("Import the mobile review dossier first")
        check(current.snapshotHash == mobilePreferences().getString("snapshot", ""))
        check(runCatching { store.execute("invented", current.snapshotHash) }.isFailure)
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        clickUi("Mémoire")
        clickUi("Recherches et preuves")
        awaitNode("Atelier local de Jade")
        if (current.scope == "LEGACY_TEST_DOSSIER") clickUi("Voir l'ancien dossier de test")
        awaitNode(current.goal)
        clickUi("Afficher les passages")
        awaitNode(current.sources.first().passages.first().text)
        clickUi("Autorisation")
        setUi("Nom du relecteur", "Validation instrumentée")
        setUi("Motif de la revue", "TEST : recontrôle des octets importés sur ce Pixel uniquement.")
        checkUi("Confirmer le périmètre de l'essai")
        clickUi("Autoriser pour 15 minutes")
        awaitNode("Lancer le recontrôle")
        val revokedId = store.events().last { it.getString("kind") == "GRANTED" }.getString("authorization_id")
        clickUi("Révoquer l'autorisation")
        awaitNode("Autorisation révoquée avant exécution.")
        awaitNode("Autoriser pour 15 minutes")
        check(runCatching { store.execute(revokedId, current.snapshotHash) }.isFailure)
        checkUi("Confirmer le périmètre de l'essai")
        clickUi("Autoriser pour 15 minutes")
        awaitNode("Lancer le recontrôle")
        val usedId = store.events().last { it.getString("kind") == "GRANTED" }.getString("authorization_id")
        clickUi("Lancer le recontrôle")
        awaitNode("Recontrôle terminé sur le Pixel. Résultat conservé dans le journal.")
        clickUi("Journal")
        awaitNode("3 éléments · 15 passages · 3 propositions recontrôlés sur cet appareil")
        check(runCatching { store.execute(usedId, current.snapshotHash) }.isFailure)
        check(mobilePreferences().edit().putString("used_id", usedId).putString("revoked_id", revokedId).commit())
        check(NightEvidenceLedger(targetContext).evidence().size == mobilePreferences().getInt("ledger_before", -1))
        saveMobileScreenshot("validation_mobile_review_journal.png")
        return "native_navigation=PASS, displayed_passages=PASS, grant_revoke_run=PASS, sources=3, passages=15, fbx=2, no_promotion=true"
    }

    private fun mobileReviewInspection(): String {
        val store = MobileResearchReviewStore(targetContext)
        val current = store.dossier() ?: error("Import the mobile dossier first")
        val before = store.events()
        val ledger = NightEvidenceLedger(targetContext).evidence().size
        check(runCatching { store.inspect("0".repeat(64), current.sources.first().hash, "TEST", "TEST reason", "UNCERTAIN") }.isFailure)
        check(runCatching { store.inspect(current.snapshotHash, "0".repeat(64), "TEST", "TEST reason", "UNCERTAIN") }.isFailure)
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Mémoire"); clickUi("Recherches et preuves")
        if (current.scope == "LEGACY_TEST_DOSSIER") clickUi("Voir l'ancien dossier de test")
        clickUi("Expériences"); awaitNode("Expériences proposées")
        awaitNode("Bloquée par une étape préalable.")
        clickUi("Dossier"); clickUi("Afficher les passages")
        setUi("Nom du relecteur", "Validation instrumentée")
        setUi("Motif de la revue", "TEST : lecture incertaine, aucune autorisation ni promotion.")
        clickUi("Consigner ma lecture")
        awaitNode("Lecture enregistrée. Aucune expérience autorisée.")
        val loaded = MobileResearchReviewStore(targetContext).events()
        check(loaded.size == before.size + 1 && loaded.last().getString("kind") == "INSPECTION_RECORDED")
        check(loaded.last().getString("stance") == "UNCERTAIN" && loaded.last().getString("snapshot_sha256") == current.snapshotHash)
        check(loaded.count { it.getString("kind") == "GRANTED" } == before.count { it.getString("kind") == "GRANTED" })
        check(NightEvidenceLedger(targetContext).evidence().size == ledger)
        check(mobilePreferences().edit().putString("inspection_hash", loaded.last().getString("sha256")).commit())
        clickUi("Journal"); awaitNode("Lecture humaine consignée")
        saveMobileScreenshot("validation_mobile_review_inspection.png")
        return "proposed_experiments_visible=PASS, blocked_dependency_visible=PASS, source_inspection_ui=PASS, persisted=PASS, no_grant=true, no_promotion=true"
    }

    private fun mobileReviewVerify(): String {
        val store = MobileResearchReviewStore(targetContext)
        val current = store.dossier() ?: error("Mobile dossier not persisted")
        val prefs = mobilePreferences()
        check(current.snapshotHash == prefs.getString("snapshot", ""))
        val rows = store.events()
        val inspectionHash = prefs.getString("inspection_hash", null)
        if (inspectionHash != null) check(rows.any { it.getString("sha256") == inspectionHash && it.getString("kind") == "INSPECTION_RECORDED" })
        val completed = rows.last { it.optString("authorization_id") == prefs.getString("used_id", "") }
        check(completed.getString("kind") == "COMPLETED" && completed.getInt("source_count") == 3 && completed.getInt("passage_count") == 15 && completed.getInt("fbx_count") == 2)
        check(completed.opt("knowledge_verified") == false && completed.opt("automatic_promotion") == false)
        check(rows.last { it.optString("authorization_id") == prefs.getString("revoked_id", "") }.getString("kind") == "REVOKED")
        check(runCatching { store.execute(prefs.getString("used_id", "")!!, current.snapshotHash) }.isFailure)
        check(NightEvidenceLedger(targetContext).evidence().size == prefs.getInt("ledger_before", -1))
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Mémoire"); clickUi("Recherches et preuves")
        if (current.scope == "LEGACY_TEST_DOSSIER") clickUi("Voir l'ancien dossier de test")
        awaitNode(current.goal)
        saveMobileScreenshot("validation_mobile_review_dossier.png")
        return "restart_persistence=PASS, revoked_and_used_permissions=PASS, actual_dossier_visible=PASS, evidence_ledger_unchanged=true"
    }

    private fun chatAvailabilityDiagnostic(): String {
        val profile = com.jadegenesis.mobile.device.DeviceProfiler(targetContext).capture()
        check(profile.thermalStatus in setOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN", "UNKNOWN"))
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitNode("Chat en secours")
        awaitNode("Jade TEST fonctionne volontairement hors réseau. Son chat distribué ne peut pas joindre un modèle de calcul.")
        awaitNode("CHAT LIMITÉ")
        saveMobileScreenshot("validation_chat_availability.png")
        return "actual_phone_thermal_api=${profile.thermalStatus}, explicit_offline_reason_visible=PASS, no_personal_chat_or_settings_touched=true"
    }

    private fun jadeLearningReview(): String {
        val raw = File(targetContext.filesDir, "validation_learning_dossier.json").readText(Charsets.UTF_8)
        val expected = MobileResearchDossierParser.parse(raw)
        check(expected.scope == "JADE_SELF_IMPROVEMENT" && expected.artifacts.isEmpty())
        val store = MobileResearchReviewStore(targetContext)
        val before = NightEvidenceLedger(targetContext).evidence().size
        val old = store.dossier()
        val grantsBefore = store.events().count { it.getString("kind") == "GRANTED" }
        store.importDossier(raw)
        check(store.events().count { it.getString("kind") == "GRANTED" } == grantsBefore)
        if (old != null && old.raw != raw) {
            val archive = File(targetContext.filesDir, "research_review_v1/archive/${old.snapshotHash}.json")
            check(archive.readText(Charsets.UTF_8) == old.raw) { "Previous review dossier was not preserved" }
        }
        val pending = NightResearchProposalInbox(targetContext).latest() ?: error("No general Jade proposal summary")
        check(pending.scope == "JADE_SELF_IMPROVEMENT" && pending.hypothesisCount == 1 && pending.experiments.size == 1)
        check(runCatching { store.execute(expected.experiments.single().id, expected.snapshotHash) }.isFailure)
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Mémoire"); clickUi("Recherches et preuves")
        awaitNode("Atelier local de Jade"); awaitNode(expected.goal)
        clickUi("Expériences"); awaitNode(expected.experiments.single().title)
        awaitNode("Critère : ${expected.experiments.single().successCriterion}")
        saveMobileScreenshot("validation_jade_learning_proposals.png")
        clickUi("Dossier"); clickUi("Afficher les passages")
        setUi("Nom du relecteur", "Validation instrumentée")
        setUi("Motif de la revue", "TEST synthétique Jade : observation à approfondir, aucun essai d'amélioration autorisé.")
        clickUi("Consigner ma lecture"); awaitNode("Lecture enregistrée. Aucune expérience autorisée.")
        clickUi("Autorisation")
        setUi("Nom du relecteur", "Validation instrumentée")
        setUi("Motif de la revue", "TEST : recontrôler le dossier Jade uniquement, sans modifier Jade.")
        checkUi("Confirmer le périmètre de l'essai"); clickUi("Autoriser pour 15 minutes"); awaitNode("Lancer le recontrôle")
        val id = store.events().last { it.getString("kind") == "GRANTED" }.getString("authorization_id")
        clickUi("Lancer le recontrôle"); awaitNode("Recontrôle terminé sur le Pixel. Résultat conservé dans le journal.")
        check(runCatching { store.execute(id, expected.snapshotHash) }.isFailure)
        check(NightEvidenceLedger(targetContext).evidence().size == before)
        check(mobilePreferences().edit().putString("jade_snapshot", expected.snapshotHash).putString("jade_used_id", id)
            .putInt("jade_ledger_before", before).commit())
        clickUi("Journal"); awaitNode("2 éléments · 2 passages · 1 propositions recontrôlés sur cet appareil")
        saveMobileScreenshot("validation_jade_learning_journal.png")
        return "Jade_general_report=PASS, hypothesis_criterion_visible=PASS, proposal_cannot_execute=PASS, source_review=PASS, local_recheck_only=PASS, previous_dossier_archived=true, no_promotion=true"
    }

    private fun jadeLearningRestart(): String {
        val store = MobileResearchReviewStore(targetContext)
        val current = store.dossier() ?: error("No persisted Jade report")
        check(current.scope == "JADE_SELF_IMPROVEMENT" && current.snapshotHash == mobilePreferences().getString("jade_snapshot", ""))
        val used = mobilePreferences().getString("jade_used_id", "")!!
        check(store.events().last { it.optString("authorization_id") == used }.getString("kind") == "COMPLETED")
        check(store.events().any { it.getString("kind") == "INSPECTION_RECORDED" && it.optString("snapshot_sha256") == current.snapshotHash })
        check(runCatching { store.execute(used, current.snapshotHash) }.isFailure)
        check(NightEvidenceLedger(targetContext).evidence().size == mobilePreferences().getInt("jade_ledger_before", -1))
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clickUi("Mémoire"); clickUi("Recherches et preuves"); awaitNode(current.goal)
        clickUi("Expériences"); awaitNode("Critère : ${current.experiments.single().successCriterion}")
        return "Jade_review_restart=PASS, decisions_persisted=PASS, consumed_permission_blocked=PASS, evidence_ledger_unchanged=true"
    }

    private fun saveMobileScreenshot(name: String) {
        check(uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.jadegenesis.mobile.validation")
        val bitmap = uiAutomation.takeScreenshot() ?: error("Jade TEST screenshot unavailable")
        File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private suspend fun seed(): String {
        val db = JadeDatabase.get(targetContext)
        val store = MemoryStore(db.memoryDao())
        val prefs = preferences()
        check(!prefs.contains("knowledge_id")) {
            "Existing synthetic dataset: verify/cleanup it before seeding again"
        }

        val knowledge = store.remember(
            MemoryType.KNOWLEDGE,
            "Roblox Blender synthetic memory: UV mesh export to Roblox Studio.",
            "VALIDATION_SYNTHETIC",
            0.65,
            "validation-probe"
        )
        val noise = (1..18).map { i ->
            store.remember(
                MemoryType.OBSERVATION,
                "Unrelated telemetry sample number $i.",
                "VALIDATION_SYNTHETIC",
                0.3,
                "validation-probe"
            )
        }
        val hypothesis = store.remember(
            MemoryType.HYPOTHESIS,
            "Synthetic uncertainty about Blender material transfer.",
            "VALIDATION_SYNTHETIC",
            0.5,
            "validation-probe"
        )
        val failure = store.remember(
            MemoryType.FAILURE,
            "Synthetic failed import experiment.",
            "VALIDATION_SYNTHETIC",
            0.5,
            "validation-probe"
        )

        val selected = store.latestForContext(limit = 8, query = "Roblox Blender")
        check(selected.any { it.id == knowledge.id }) {
            "Relevant old knowledge was pushed out by newer telemetry"
        }
        val now = System.currentTimeMillis()
        val agenda = NightMemoryReviewPlanner.propose(store.latest(40), now)
        check(agenda.any { it.memoryId == failure.id && it.reason == "FAILURE_TO_REEXAMINE" })
        check(agenda.any { it.memoryId == hypothesis.id && it.reason == "HYPOTHESIS_TO_VERIFY" })
        NightReviewAgendaStore(targetContext).save(agenda, now)

        val created = listOf(knowledge, hypothesis, failure) + noise
        check(prefs.edit()
            .putString("knowledge_id", knowledge.id)
            .putString("failure_id", failure.id)
            .putString("hypothesis_id", hypothesis.id)
            .putString("all_ids", created.joinToString(",") { it.id })
            .commit()) { "Cannot persist test metadata" }
        return "created=${created.size}, retrieval=PASS, agenda=${agenda.size}, persisted=PASS"
    }

    private suspend fun verifyAfterRestart(): String {
        val prefs = preferences()
        val knowledgeId = prefs.getString("knowledge_id", null)
            ?: error("Synthetic knowledge ID not persisted")
        val failureId = prefs.getString("failure_id", null)
            ?: error("Synthetic failure ID not persisted")
        val hypothesisId = prefs.getString("hypothesis_id", null)
            ?: error("Synthetic hypothesis ID not persisted")
        val store = MemoryStore(JadeDatabase.get(targetContext).memoryDao())
        val allIds = prefs.getString("all_ids", "")!!.split(",").filter { it.isNotBlank() }
        val rows = JadeDatabase.get(targetContext).memoryDao().activeByIds(allIds)
        check(rows.size == 21) { "Expected 21 persisted synthetic memory records, got ${rows.size}" }
        val retrieved = store.latestForContext(limit = 8, query = "Roblox Blender")
        check(retrieved.any { it.id == knowledgeId }) {
            "Knowledge not recalled after restart"
        }
        val agenda = NightReviewAgendaStore(targetContext).latest()
        check(agenda.any { it.memoryId == failureId && it.reason == "FAILURE_TO_REEXAMINE" }) {
            "Failure review not persisted"
        }
        check(agenda.any { it.memoryId == hypothesisId && it.reason == "HYPOTHESIS_TO_VERIFY" }) {
            "Hypothesis review not persisted"
        }
        check(agenda.none { it.memoryId.isBlank() })
        return "database=21/21, contextual_recall=PASS, agenda=PASS, original_package_untouched"
    }

    private fun reviewPreferences() = targetContext.getSharedPreferences(
        "jade_validation_review_packet_probe_v1",
        android.content.Context.MODE_PRIVATE
    )

    private fun reviewImport(): String {
        check(sourceManifestBase64.length in 10..16_000) {
            "Missing or oversized evidence review packet"
        }
        val packet = android.util.Base64.decode(
            sourceManifestBase64, android.util.Base64.NO_WRAP
        ).toString(Charsets.UTF_8)
        val ledger = NightEvidenceLedger(targetContext)
        val before = ledger.evidence().size
        val imported = NightResearchProposalInbox(targetContext).importPacket(packet)
        check(imported.sourceCount == 3 && imported.blenderLocalChecks == 2)
        check(imported.potentialCaveats >= 1)
        check(ledger.evidence().size == before) {
            "Uninspected source reports must not enter the approved evidence ledger"
        }
        check(reviewPreferences().edit()
            .putString("goal_sha256", imported.goalSha256)
            .putInt("ledger_count_before", before)
            .commit())
        return "sources=3, checked_blender_replays=2, needs_review=true, ledger_unchanged=true"
    }

    private fun reviewVerify(): String {
        val expected = reviewPreferences().getString("goal_sha256", null)
            ?: error("Evidence review packet was not imported")
        val expectedLedgerSize = reviewPreferences().getInt("ledger_count_before", -1)
        val pending = NightResearchProposalInbox(targetContext).latest()
            ?: error("Provisional review packet was not persisted")
        check(pending.goalSha256 == expected)
        check(pending.sourceCount == 3 && pending.blenderLocalChecks == 2)
        check(pending.reviewStatus == "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
        check(pending.nextExperiment == "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY")
        check(NightEvidenceLedger(targetContext).evidence().size == expectedLedgerSize)
        return "packet_persisted=true, pending_roblox_import=true, no_evidence_promotion=true"
    }

    private fun reviewCleanup(): String {
        check(reviewPreferences().contains("goal_sha256")) {
            "No previously imported provisional packet"
        }
        NightResearchProposalInbox(targetContext).clear()
        check(NightResearchProposalInbox(targetContext).latest() == null)
        check(reviewPreferences().edit().clear().commit())
        return "pending_review_removed=1, personal_app_untouched=true"
    }

    private fun capturePreferences() = targetContext.getSharedPreferences(
        "jade_validation_official_source_probe_v1",
        android.content.Context.MODE_PRIVATE
    )

    private fun sourceImport(): String {
        check(sourceManifestBase64.length in 10..16_000) {
            "Missing or oversized source manifest"
        }
        val original = android.util.Base64.decode(
            sourceManifestBase64, android.util.Base64.NO_WRAP
        ).toString(Charsets.UTF_8)
        val imported = OfficialSourceCaptureInbox(targetContext).importManifest(original)
        val serialized = imported.joinToString(",") { it.sourceKey + ":" + it.sha256 }
        check(capturePreferences().edit().putString("expected", serialized).commit())
        return "real_official_sources=" + imported.size +
            ", all_unassessed=true, imported_without_network=true"
    }

    private fun sourceVerify(): String {
        val expected = capturePreferences().getString("expected", null)
            ?: error("Source capture manifest not persisted")
        val captured = OfficialSourceCaptureInbox(targetContext).latest()
        val actual = captured.joinToString(",") { it.sourceKey + ":" + it.sha256 }
        check(actual == expected) {
            "Source metadata changed after app restart"
        }
        check(captured.size == 3)
        // Merely downloading public documentation never verifies any memory.
        val undecided = NightEvidenceReviewer.assess(
            NightReviewCandidate(
                "validation-candidate-without-reviewed-evidence",
                "KNOWLEDGE_NEEDS_EVIDENCE", 2
            ),
            emptyList(),
            System.currentTimeMillis()
        )
        check(undecided.decision == ReviewDecision.NEEDS_EVIDENCE)
        check(!undecided.automaticPromotion)
        return "sources=3/3, hashes_persisted=true, verdict=NEEDS_EVIDENCE, auto_promoted=false"
    }

    private fun sourceCleanup(): String {
        val expected = capturePreferences().getString("expected", null)
            ?: error("No captured manifest available")
        val count = expected.split(",").count { it.isNotBlank() }
        OfficialSourceCaptureInbox(targetContext).clear()
        check(OfficialSourceCaptureInbox(targetContext).latest().isEmpty())
        check(capturePreferences().edit().clear().commit())
        return "capture_metadata_removed=" + count + ", original_package_untouched=true"
    }

    private fun proofPreferences() = targetContext.getSharedPreferences(
        "jade_validation_evidence_probe_v1", android.content.Context.MODE_PRIVATE
    )

    private fun syntheticHash(payload: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private suspend fun evidenceSeed(): String {
        check(!proofPreferences().contains("memory_id")) {
            "Existing synthetic evidence fixture: verify and cleanup first"
        }
        val store = MemoryStore(JadeDatabase.get(targetContext).memoryDao())
        val memory = store.remember(
            MemoryType.KNOWLEDGE,
            "Synthetic Blender to Roblox import advice awaiting inspection.",
            "VALIDATION_SYNTHETIC", 0.55, "validation-evidence-probe"
        )
        val ledger = NightEvidenceLedger(targetContext)
        val now = System.currentTimeMillis()
        fun proof(id: String, origin: String, type: EvidenceType, direction: EvidenceDirection) =
            ReviewEvidence(
                evidenceId = "synthetic-$id",
                memoryId = memory.id,
                sourceId = origin,
                type = type,
                direction = direction,
                observedAt = now,
                inspected = true,
                fingerprint = syntheticHash("synthetic-evidence-fixture-$id")
            )
        ledger.append(proof("official", "synthetic-official-docs", EvidenceType.PRIMARY_DOCUMENT, EvidenceDirection.SUPPORTS))
        ledger.append(proof("other", "synthetic-independent-docs", EvidenceType.SECONDARY_DOCUMENT, EvidenceDirection.SUPPORTS))
        ledger.append(proof("replay", "synthetic-controlled-replay", EvidenceType.REPLAYED_TEST, EvidenceDirection.SUPPORTS))
        val candidate = NightReviewCandidate(memory.id, "KNOWLEDGE_NEEDS_EVIDENCE", 2)
        val ready = NightEvidenceReviewer.assess(candidate, ledger.evidence(), now)
        check(ready.decision == ReviewDecision.READY_FOR_REVIEW && !ready.automaticPromotion)

        ledger.append(proof("counterexample", "synthetic-counterexample", EvidenceType.PRIMARY_DOCUMENT, EvidenceDirection.REFUTES))
        val contested = NightEvidenceReviewer.assess(candidate, ledger.evidence(), now)
        check(contested.decision == ReviewDecision.CONTESTED && contested.refuteCount == 1)
        ledger.saveReviews(listOf(contested))
        check(proofPreferences().edit().putString("memory_id", memory.id).commit())
        return "sources=4, conflicting=1, ready_before_conflict=PASS, contested=PASS"
    }

    private suspend fun evidenceVerify(): String {
        val id = proofPreferences().getString("memory_id", null)
            ?: error("Synthetic evidence ID not persisted")
        val ledger = NightEvidenceLedger(targetContext)
        val proofs = ledger.evidence().filter { it.memoryId == id }
        check(proofs.size == 4) { "Expected four persistent evidence entries, got ${proofs.size}" }
        val verdict = ledger.latestReviews().singleOrNull { it.memoryId == id }
            ?: error("Contradiction review not persisted")
        check(verdict.decision == ReviewDecision.CONTESTED && verdict.refuteCount == 1)
        check(!verdict.automaticPromotion)
        val rows = JadeDatabase.get(targetContext).memoryDao().activeByIds(listOf(id))
        check(rows.size == 1 && rows.single().verifiedAt == null) {
            "Knowledge must NOT be promoted to verified after conflicting evidence"
        }
        return "evidence=4/4, conflict_persisted=PASS, automatic_promotion=false, knowledge_unverified=PASS"
    }

    private suspend fun evidenceCleanup(): String {
        val prefs = proofPreferences()
        val id = prefs.getString("memory_id", null)
            ?: error("No synthetic evidence fixture to remove")
        val ledger = NightEvidenceLedger(targetContext)
        ledger.removeForMemory(id)
        check(ledger.evidence().none { it.memoryId == id })
        check(ledger.latestReviews().none { it.memoryId == id })
        val removed = JadeDatabase.get(targetContext).memoryDao().deleteByIds(listOf(id))
        check(removed == 1)
        check(prefs.edit().remove("memory_id").commit())
        return "synthetic_knowledge_removed=1, evidence_removed=4, reviews_removed=1"
    }

    private suspend fun cleanup(): String {
        val prefs = preferences()
        val ids = prefs.getString("all_ids", "")!!
            .split(",").filter { it.isNotBlank() }
        val removed = if (ids.isEmpty()) 0 else {
            JadeDatabase.get(targetContext).memoryDao().deleteByIds(ids)
        }
        check(removed == ids.size) { "Cleanup mismatch removed=$removed expected=${ids.size}" }
        check(prefs.edit().clear().commit()) { "Could not clear synthetic metadata" }
        check(targetContext.getSharedPreferences(
            "jade_night_review_agenda_v1", android.content.Context.MODE_PRIVATE
        ).edit().remove("latest").commit()) { "Could not clear synthetic agenda" }
        return "synthetic_records_removed=$removed, agenda_cleared=PASS"
    }
}
