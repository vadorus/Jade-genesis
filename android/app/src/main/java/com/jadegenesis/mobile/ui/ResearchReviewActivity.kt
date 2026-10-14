package com.jadegenesis.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jadegenesis.mobile.night.MobileResearchAuthorization
import com.jadegenesis.mobile.night.MobileResearchDossier
import com.jadegenesis.mobile.night.MobileResearchDossierParser
import com.jadegenesis.mobile.night.MobileResearchReviewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class ResearchReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JadeGenesisTheme { ResearchReviewScreen(onBack = { finish() }) } }
    }
}

@Composable
internal fun ResearchReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MobileResearchReviewStore(context) }
    val scope = rememberCoroutineScope()
    var dossier by remember { mutableStateOf<MobileResearchDossier?>(null) }
    var events by remember { mutableStateOf(emptyList<JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var healthy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("Dossier") }
    var reviewer by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var showLegacyTest by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val listState = rememberLazyListState()
    LaunchedEffect(section) { listState.scrollToItem(0) }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) { store.dossier() to store.events() }
        dossier = loaded.first; events = loaded.second; healthy = true
    }
    fun perform(success: String, action: () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; message = ""
            try { withContext(Dispatchers.IO) { action() }; refresh(); message = success; confirmed = false }
            catch (error: Exception) {
                message = error.message ?: "Action refusée"
                try { refresh() } catch (_: Exception) { healthy = false }
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        try { refresh() } catch (error: Exception) { message = error.message ?: "Revue indisponible" }
        finally { busy = false }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("Dossier importé sur ce Pixel. Aucune expérience autorisée.") {
            require(uri.scheme == "content")
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(MobileResearchDossierParser.MAX_BYTES + 1)
                var length = 0
                while (length < buffer.size) {
                    val count = input.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    length += count
                }
                require(length <= MobileResearchDossierParser.MAX_BYTES) { "Dossier trop volumineux" }
                buffer.copyOf(length)
            } ?: error("Fichier inaccessible")
            store.importDossier(bytes.toString(Charsets.UTF_8))
        }
    }
    Surface(color = JadeColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("Retour") }
                TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy) { Text("Importer un dossier") }
            }
            Text("Recherches et preuves", style = MaterialTheme.typography.headlineSmall, color = JadeColors.Ink)
            Text("Atelier local de Jade", color = JadeColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                listOf("Dossier", "Expériences", "Autorisation", "Journal").forEach { label ->
                    FilterChip(selected = section == label, onClick = { section = label }, label = { Text(label) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = JadeColors.Info, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                val legacy = dossier?.scope == "LEGACY_TEST_DOSSIER"
                val current = dossier?.takeIf { !legacy || showLegacyTest }
                if (current == null) item { V2Card {
                    Text("Revue des améliorations de Jade", style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                    Text("Importe un compte rendu de NightLearningLab pour examiner les observations, les hypothèses et les essais proposés pour Jade. Chaque décision reste locale et explicite.", color = JadeColors.Muted)
                    if (legacy) TextButton(onClick = { showLegacyTest = true }) { Text("Voir l'ancien dossier de test") }
                } }
                if (current != null && section == "Dossier") {
                    item { V2Card {
                        Text(current.goal, style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                        Text("${current.sources.size} éléments à relire · ${current.experiments.size} essais proposés", color = JadeColors.Jade)
                        Text("Les observations déclarées et les hypothèses restent à examiner. Un recontrôle des empreintes ne prouve pas une amélioration de Jade.", color = JadeColors.Muted)
                    } }
                    current.sources.forEach { source -> item {
                        var expanded by remember(current.snapshotHash, source.key) { mutableStateOf(false) }
                        V2Card {
                            Text(source.title, color = JadeColors.Ink)
                            Text(source.url.ifBlank { "Compte rendu local de Jade" }, style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer les passages" else "Afficher les passages") }
                            if (expanded) {
                                Text("Empreinte du document : ${source.hash}", style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
                                source.passages.forEach { passage ->
                                    Text(passage.text, color = JadeColors.Ink)
                                    Text("Bloc ${passage.block} · ${passage.hash}", style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
                                    if (passage.caveat) Text("Restriction possible à examiner", color = JadeColors.Warn)
                                    HorizontalDivider()
                                }
                                Text("Texte à examiner. L'empreinte garantit l'égalité du contenu, pas sa vérité.", color = JadeColors.Muted)
                                val inspection = events.lastOrNull { it.optString("kind") == "INSPECTION_RECORDED" &&
                                    it.optString("snapshot_sha256") == current.snapshotHash && it.optString("source_sha256") == source.hash }
                                if (inspection != null) Text("Dernière lecture : ${reviewStance(inspection.getString("stance"))} · ${inspection.getString("reviewer")}", color = JadeColors.Jade)
                                var stance by remember(current.snapshotHash, source.key) { mutableStateOf("UNCERTAIN") }
                                Text("Consigner votre lecture", style = MaterialTheme.typography.titleSmall, color = JadeColors.Ink)
                                OutlinedTextField(value = reviewer, onValueChange = { if (it.length <= 80) reviewer = it }, label = { Text("Votre nom") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nom du relecteur" }, singleLine = true)
                                OutlinedTextField(value = note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("Motif de la lecture") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Motif de la revue" }, minLines = 2)
                                listOf("UNCERTAIN", "SUPPORTS", "CONTRADICTS").forEach { value ->
                                    FilterChip(selected = stance == value, onClick = { stance = value }, label = { Text(reviewStance(value)) })
                                }
                                Button(onClick = { perform("Lecture enregistrée. Aucune expérience autorisée.") {
                                    store.inspect(current.snapshotHash, source.hash, reviewer, note, stance)
                                } }, enabled = healthy && !busy && reviewer.isNotBlank() && note.trim().length >= 3) { Text("Consigner ma lecture") }
                                Text("Une lecture déclarée ne vaut pas contre-revue indépendante et n'accorde aucune autorisation.", color = JadeColors.Muted)
                            }
                        }
                    } }
                    current.artifacts.forEachIndexed { index, artifact -> item { V2Card {
                        Text("Fichier de preuve ${index + 1}", color = JadeColors.Ink)
                        Text("${artifact.version} · ${artifact.bytes} octets", color = JadeColors.Muted)
                        Text("Contrôles locaux seulement. Résultat non attesté indépendamment.", color = JadeColors.Warn)
                        Text("Fichier : ${artifact.fbxHash}", style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
                    } } }
                }
                if (current != null && section == "Expériences") {
                    item { V2Card { Text("Expériences proposées", style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                        Text("Propositions du dossier transmis à la revue nocturne. Elles restent en attente : aucun lancement automatique.", color = JadeColors.Muted) } }
                    current.experiments.forEach { experiment -> item { V2Card {
                        Text(experiment.title, style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                        Text(if (experiment.status == "BLOCKED_BY_DEPENDENCY") "Bloquée par une étape préalable." else "Proposée, non exécutée.", color = JadeColors.Warn)
                        if (experiment.target.isNotBlank()) Text("Cible : ${experiment.target} · mesure : ${experiment.metric}", color = JadeColors.Muted)
                        if (experiment.minimumSamples > 0) Text("${experiment.minimumSamples} à ${experiment.maximumSamples} scénarios par variante, avec référence conservée.", color = JadeColors.Muted)
                        if (experiment.successCriterion.isNotBlank()) Text("Critère : ${experiment.successCriterion}", color = JadeColors.Ink)
                        Text("Un plan d'essai précis dans un environnement isolé reste nécessaire. Cette proposition n'autorise aucun lancement.", color = JadeColors.Muted)
                    } } }
                }
                if (current != null && section == "Autorisation") {
                    val live = events.filter { it.optString("kind") == "GRANTED" }.lastOrNull { grant ->
                        runCatching { MobileResearchAuthorization.pending(events, grant.getString("authorization_id"), current.snapshotHash, now) }.isSuccess
                    }
                    item { V2Card {
                        Text("Recontrôler les preuves sur ce Pixel", style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                        Text("Relit le compte rendu, les éléments cités et les propositions. Vérifie leur cohérence et leurs empreintes, puis conserve ce contrôle dans le journal local.", color = JadeColors.Muted)
                        Text("Périmètre : stockage privé de Jade TEST, hors réseau. Aucun outil PC/VPS ni promotion de mémoire.", color = JadeColors.Muted)
                        Text("Une seule utilisation, pendant 15 minutes. Autoriser et lancer sont deux actions séparées.", color = JadeColors.Ink)
                        if (live == null) {
                            OutlinedTextField(value = reviewer, onValueChange = { if (it.length <= 80) reviewer = it }, label = { Text("Votre nom") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nom du relecteur" }, singleLine = true)
                            OutlinedTextField(value = note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("Motif de la décision") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Motif de la revue" }, minLines = 2)
                            Row { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, modifier = Modifier.semantics { contentDescription = "Confirmer le périmètre de l'essai" }); Text("J'autorise ce recontrôle local sur ce dossier précis.", color = JadeColors.Ink, modifier = Modifier.padding(top = 12.dp)) }
                            Button(onClick = { perform("Autorisation enregistrée. L'essai n'a pas encore commencé.") { store.grant(current.snapshotHash, reviewer, note, confirmed) } }, enabled = healthy && !busy && confirmed && reviewer.isNotBlank() && note.trim().length >= 3) { Text("Autoriser pour 15 minutes") }
                        } else {
                            Text("Autorisé par ${live.getString("reviewer")} jusqu'à ${reviewDate(live.getLong("expires_at_ms"))}", color = JadeColors.Jade)
                            Button(onClick = { perform("Recontrôle terminé sur le Pixel. Résultat conservé dans le journal.") { store.execute(live.getString("authorization_id"), current.snapshotHash) } }, enabled = healthy && !busy) { Text("Lancer le recontrôle") }
                            OutlinedButton(onClick = { perform("Autorisation révoquée avant exécution.") { store.revoke(live.getString("authorization_id")) } }, enabled = healthy && !busy) { Text("Révoquer l'autorisation") }
                        }
                    } }
                    item { V2Card { Text("Essais d'amélioration de Jade", color = JadeColors.Ink); Text("Le recontrôle local relit le dossier. Pour tester une modification de Jade, il faudra préparer et autoriser son plan d'essai propre.", color = JadeColors.Muted) } }
                }
                if (section == "Journal") {
                    if (events.isEmpty()) item { Text("Aucune décision enregistrée.", color = JadeColors.Muted) }
                    events.asReversed().forEach { event -> item { V2Card {
                        val kind = event.getString("kind")
                        Text(when (kind) { "DOSSIER_IMPORTED" -> "Dossier importé"; "INSPECTION_RECORDED" -> "Lecture humaine consignée"; "GRANTED" -> "Autorisation accordée"; "REVOKED" -> "Autorisation révoquée"; "STARTED" -> "Recontrôle commencé"; "COMPLETED" -> "Recontrôle terminé"; else -> "Recontrôle en échec" }, color = JadeColors.Ink)
                        Text(reviewDate(event.getLong("at_ms")), color = JadeColors.Muted)
                        if (event.has("reviewer")) Text("${event.getString("reviewer")} : ${event.getString("note")}", color = JadeColors.Ink)
                        if (kind == "INSPECTION_RECORDED") Text("${reviewStance(event.getString("stance"))} · connaissance toujours non vérifiée", color = JadeColors.Warn)
                        if (kind == "GRANTED" && events.lastOrNull { it.optString("authorization_id") == event.optString("authorization_id") }?.getString("kind") == "GRANTED") {
                            Text(if (event.getLong("expires_at_ms") <= now) "Autorisation expirée" else if (current?.snapshotHash != event.getString("snapshot_sha256")) "Dossier remplacé : lancement bloqué" else "Autorisation en attente, aucun lancement", color = JadeColors.Warn)
                            OutlinedButton(onClick = { perform("Autorisation révoquée avant exécution.") { store.revoke(event.getString("authorization_id")) } }, enabled = healthy && !busy) { Text("Révoquer cette autorisation") }
                        }
                        if (kind == "COMPLETED") { Text("${event.getInt("source_count")} éléments · ${event.getInt("passage_count")} passages · ${event.optInt("proposal_count")} propositions recontrôlés sur cet appareil", color = JadeColors.Jade); Text("Connaissance toujours non vérifiée. Aucune promotion.", color = JadeColors.Warn) }
                        if (kind == "STARTED" && events.lastOrNull { it.optString("authorization_id") == event.optString("authorization_id") }?.getString("kind") == "STARTED") Text("Résultat inconnu : autorisation déjà consommée, aucune relance automatique.", color = JadeColors.Warn)
                        Text("Trace : ${event.getString("sha256")}", style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
                    } } }
                }
            }
        }
    }
}

private fun reviewDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun reviewStance(stance: String): String = when (stance) {
    "SUPPORTS" -> "Soutient l'hypothèse selon ma lecture"
    "CONTRADICTS" -> "Contredit l'hypothèse selon ma lecture"
    else -> "Incertain / à approfondir"
}
