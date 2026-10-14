package com.jadegenesis.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.ui.unit.dp
import com.jadegenesis.mobile.tools.JsonDemonstration
import com.jadegenesis.mobile.tools.JsonFileLearningWorkshop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object JsonWorkshopDemo {
    val train = listOf(
        JsonDemonstration("{\"label\":\"A\",\"n\":1}", "{\n  \"nom\": \"A\",\n  \"n\": 1\n}"),
        JsonDemonstration("{\"label\":\"B\",\"active\":true}", "{\n  \"nom\": \"B\",\n  \"active\": true\n}")
    )
    val validation = JsonDemonstration("{\"label\":\"C\",\"extra\":null}", "{\n  \"nom\": \"C\",\n  \"extra\": null\n}")
}

class JsonToolWorkshopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { JadeGenesisTheme { JsonToolWorkshopScreen { finish() } } }
    }
}

@Composable
internal fun JsonToolWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val workshop = remember { JsonFileLearningWorkshop(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var state by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var trainBefore1 by remember { mutableStateOf("") }; var trainAfter1 by remember { mutableStateOf("") }
    var trainBefore2 by remember { mutableStateOf("") }; var trainAfter2 by remember { mutableStateOf("") }
    var testBefore by remember { mutableStateOf("") }; var testAfter by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf<JSONObject?>(null) }
    fun perform(success: String, action: () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { action() }; state = withContext(Dispatchers.IO) { workshop.state() }; message = success; listState.scrollToItem(0) }
            catch (error: Exception) { message = error.message ?: "Action refusée"; runCatching { state = withContext(Dispatchers.IO) { workshop.state() } } }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { perform("") { } }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("Fichier lu et copié dans l'atelier privé. Original conservé.") {
            require(uri.scheme == "content")
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16_385); var size = 0
                while (size < buffer.size) { val count = input.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }
                require(size <= 16_384) { "Fichier limité à 16 Ko" }; buffer.copyOf(size)
            } ?: error("Fichier inaccessible")
            workshop.importInput(bytes)
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) perform("Résultat exporté dans le fichier choisi.") {
            val output = workshop.state().getJSONObject("last_result").getString("output")
            context.contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray(Charsets.UTF_8)) } ?: error("Destination inaccessible")
        }
    }
    Surface(Modifier.fillMaxSize(), color = JadeColors.Bg) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TextButton(onClick = onBack) { Text("Retour") }; Text("Atelier d'outils JSON", style = MaterialTheme.typography.headlineSmall, color = JadeColors.Ink); Text("Apprendre une procédure, la vérifier, l'utiliser sur des fichiers", color = JadeColors.Muted) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (message.isNotBlank()) item { Text(message, color = JadeColors.Info) }
            item { V2Card {
                Text("1. Construire à partir d'exemples", style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                Text("Jade recherche une procédure de mise en forme et, si les exemples le demandent, de renommage d'une clé principale. Le cas de vérification reste séparé de l'apprentissage.", color = JadeColors.Muted)
                TextButton(onClick = {
                    trainBefore1 = JsonWorkshopDemo.train[0].before; trainAfter1 = JsonWorkshopDemo.train[0].after
                    trainBefore2 = JsonWorkshopDemo.train[1].before; trainAfter2 = JsonWorkshopDemo.train[1].after
                    testBefore = JsonWorkshopDemo.validation.before; testAfter = JsonWorkshopDemo.validation.after; expanded = true
                }, enabled = !busy) { Text("Charger les exemples de démonstration") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer les exemples" else "Saisir mes exemples") }
                if (expanded) {
                    OutlinedTextField(trainBefore1, { trainBefore1 = it.take(16_384) }, label = { Text("Exemple 1 : JSON avant") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(trainAfter1, { trainAfter1 = it.take(16_384) }, label = { Text("Exemple 1 : résultat voulu") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(trainBefore2, { trainBefore2 = it.take(16_384) }, label = { Text("Exemple 2 : JSON avant") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(trainAfter2, { trainAfter2 = it.take(16_384) }, label = { Text("Exemple 2 : résultat voulu") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testBefore, { testBefore = it.take(16_384) }, label = { Text("Cas séparé : JSON avant") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(testAfter, { testAfter = it.take(16_384) }, label = { Text("Cas séparé : résultat attendu") }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = { val train = listOf(JsonDemonstration(trainBefore1, trainAfter1), JsonDemonstration(trainBefore2, trainAfter2)); perform("Procédure candidate construite. Elle n'est pas encore un outil actif.") { workshop.propose(train) }; expanded = false }, enabled = !busy && trainBefore1.isNotBlank() && trainBefore2.isNotBlank()) { Text("Construire l'outil depuis les exemples") }
                state.optJSONObject("candidate")?.let { candidate ->
                    Text("Candidat : ${candidate.getString("tool_sha256").take(12)}", color = JadeColors.Jade)
                    Text(candidate.getJSONObject("recipe").toString(2), color = JadeColors.Muted)
                    Button(onClick = { runCatching { workshop.validationPlan(JsonDemonstration(testBefore, testAfter)) }.onSuccess { approval = it }.onFailure { message = it.message ?: "Cas invalide" } }, enabled = !busy && testBefore.isNotBlank()) { Text("Autoriser la vérification") }
                }
            } }
            val grant = state.optJSONObject("grant")
            if (grant?.optString("status") == "GRANTED") item { V2Card {
                Text("Autorisation enregistrée. Aucun lancement effectué.", color = JadeColors.Jade)
                Button(onClick = { perform("Vérification ou transformation terminée. Résultat tracé.") { workshop.execute(grant.getString("id")) } }, enabled = !busy) { Text("Exécuter le plan autorisé") }
                OutlinedButton(onClick = { perform("Autorisation révoquée.") { workshop.revoke() } }, enabled = !busy) { Text("Révoquer") }
            } }
            item { V2Card {
                Text("2. Utiliser l'outil conservé", style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
                val retained = state.optJSONObject("retained")
                Text(if (retained == null) "Aucun outil JSON appris et validé." else "Outil conservé : ${retained.getString("tool_sha256").take(12)} · validé localement", color = if (retained == null) JadeColors.Muted else JadeColors.Jade)
                Button(onClick = { importFile.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy) { Text("Lire un fichier JSON") }
                Text("Le fichier choisi est copié dans le stockage privé. La transformation crée une nouvelle copie ; l'original reste préservé.", color = JadeColors.Muted)
                state.optJSONObject("input")?.let { Text("Copie lue : ${it.getString("sha256").take(12)}", color = JadeColors.Muted) }
                Button(onClick = { runCatching { workshop.filePlan() }.onSuccess { approval = it }.onFailure { message = it.message ?: "Plan indisponible" } }, enabled = !busy && retained != null && state.has("input")) { Text("Autoriser la copie transformée") }
            } }
            state.optJSONObject("last_result")?.let { result -> item { V2Card {
                Text(if (result.optString("status") == "TOOL_RETAINED") "Cas séparé réussi. Outil conservé." else "Fichier transformé avec l'outil conservé", color = JadeColors.Jade)
                if (result.has("output")) { Text(result.getString("output"), color = JadeColors.Ink); Text("Original préservé · outil choisi automatiquement · aucune reconstruction", color = JadeColors.Muted); Button(onClick = { exportFile.launch("jade-resultat.json") }, enabled = !busy) { Text("Exporter le résultat") } }
                Text("Validation locale d'une procédure bornée, sans attestation indépendante.", color = JadeColors.Muted)
            } } }
            item { V2Card {
                Text("Journal d'apprentissage et d'utilisation", color = JadeColors.Ink)
                val rows = state.optJSONArray("events")
                if (rows == null || rows.length() == 0) Text("Aucune expérience enregistrée.", color = JadeColors.Muted)
                else (maxOf(0, rows.length() - 8) until rows.length()).reversed().forEach { i ->
                    val row = rows.getJSONObject(i)
                    Text("${row.getInt("sequence")} · ${row.getString("kind")} · ${row.getString("sha256").take(12)}", color = JadeColors.Muted)
                }
            } }
        }
    }
    approval?.let { plan -> AlertDialog(onDismissRequest = { approval = null }, title = { Text("Autoriser ce plan précis ?") }, text = { Text(if (plan.getString("action") == "VALIDATE_AND_RETAIN_JSON_TOOL") "Vérifier le candidat sur ton cas séparé et le conserver uniquement s'il passe.\nOutil : ${plan.getString("tool_sha256")}\nPérimètre : ce Pixel, sans réseau." else "Lire la copie importée et écrire une nouvelle copie transformée avec l'outil conservé.\nEntrée : ${plan.getString("input_sha256")}\nPérimètre : stockage privé, original préservé.") }, confirmButton = { TextButton(onClick = { val chosen = plan; approval = null; perform("Autorisation persistée. Lance le plan séparément.") { workshop.authorize(chosen) } }) { Text("Autoriser ce plan") } }, dismissButton = { TextButton(onClick = { approval = null }) { Text("Annuler") } }) }
}
