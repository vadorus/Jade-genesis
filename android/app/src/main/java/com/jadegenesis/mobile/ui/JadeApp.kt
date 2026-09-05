package com.jadegenesis.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeRouteStatus
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.screen.ScreenCaptureService

private const val LOCAL_NETWORK_PERMISSION =
    "android.permission.ACCESS_LOCAL_NETWORK"

private enum class JadeTab(val label: String, val short: String) {
    JADE("Jade", "J"),
    NODES("Nœuds", "N"),
    ACTIVITY("Activité", "A"),
    MEMORY("Mémoire", "M"),
    ADMIN("Admin", "D")
}

@Composable
fun JadeApp(vm: JadeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(JadeTab.JADE) }

    val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37
    var localNetworkGranted by remember {
        mutableStateOf(
            !needsLocalNetworkPermission ||
                ContextCompat.checkSelfPermission(
                    context,
                    LOCAL_NETWORK_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        localNetworkGranted = granted
    }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val requestedAt = System.currentTimeMillis()
            ScreenCaptureService.startCapture(
                context = context,
                resultCode = result.resultCode,
                resultData = data
            )
            vm.onPhoneScreenCaptureStarted(requestedAt)
        } else {
            vm.onPhoneScreenCaptureDenied()
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Surface
            }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        JadeTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Text(item.short) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                when (tab) {
                    JadeTab.JADE -> JadeHome(
                        state = state,
                        vm = vm,
                        requestPhoneScreenCapture = {
                            screenCaptureLauncher.launch(
                                projectionManager.createScreenCaptureIntent()
                            )
                        },
                        modifier = Modifier.padding(innerPadding)
                    )

                    JadeTab.NODES -> NodesScreen(
                        state = state,
                        vm = vm,
                        localNetworkGranted = localNetworkGranted,
                        requestLocalNetwork = {
                            localNetworkLauncher.launch(LOCAL_NETWORK_PERMISSION)
                        },
                        needsLocalNetworkPermission = needsLocalNetworkPermission,
                        modifier = Modifier.padding(innerPadding)
                    )

                    JadeTab.ACTIVITY -> ActivityScreen(
                        state = state,
                        vm = vm,
                        modifier = Modifier.padding(innerPadding)
                    )

                    JadeTab.MEMORY -> MemoryScreen(
                        state = state,
                        vm = vm,
                        modifier = Modifier.padding(innerPadding)
                    )

                    JadeTab.ADMIN -> AdminScreen(
                        state = state,
                        vm = vm,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun JadeHome(
    state: JadeUiState,
    vm: JadeViewModel,
    requestPhoneScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val self = state.selfModel
    val onlineRemote = self?.knownNodes?.count {
        it.kind != NodeKind.PHONE && it.status == NodeStatus.ONLINE
    } ?: 0

    PageColumn(modifier) {
        Text(
            "JADE GENESIS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Cognitive Core ${self?.identity?.version ?: "0.1.2"}",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))

        InfoCard("État") {
            Text("Cerveau : ${self?.activeBrain?.displayName ?: "inconnu"}")
            Text("Nœuds distants en ligne : $onlineRemote")
            Text("Mode ressources : ${self?.resourceBudget?.mode ?: "?"}")
            val preferred = self?.knownNodes?.firstOrNull {
                it.nodeId == self.preferredComputeNodeId
            }
            Text("Calcul préféré : ${preferred?.name ?: "Pixel / local"}")
        }

        Spacer(Modifier.height(10.dp))
        InfoCard("Screen Observer v1") {
            Text(
                "Observation à la demande uniquement : Jade ne capture aucun écran en secret. " +
                    "Le Pixel affiche l'autorisation Android avant chaque session."
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = requestPhoneScreenCapture,
                enabled = !state.screenBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.screenBusy) "Observation en cours…" else "Capturer + analyser l'écran Pixel")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.analyzePcScreen() },
                enabled = !state.screenBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Analyser l'écran du PC")
            }
            if (state.screenMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.screenMessage)
            }
        }

        if (state.chatBusy) {
            Spacer(Modifier.height(12.dp))
            InfoCard("Jade travaille") {
                CircularProgressIndicator()
                Text(
                    "Le Cognitive Core observe les nœuds, choisit un backend et peut vérifier les requêtes complexes."
                )
            }
        }

        if (state.response.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            InfoCard("Jade") {
                Text(state.response)
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            InfoCard("Erreur") {
                Text(error)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(10_000) },
            label = { Text("Parler à Jade") },
            placeholder = { Text("Ex : Analyse l'état de ton Compute Mesh") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.send(input)
                input = ""
            },
            enabled = input.isNotBlank() && !state.chatBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.chatBusy) "Jade réfléchit…" else "Envoyer")
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { vm.send("Qui es-tu et quels nœuds connais-tu ?") },
                enabled = !state.chatBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Qui es-tu ?")
            }
            OutlinedButton(
                onClick = {
                    vm.send(
                        "Évalue tes ressources, tes nœuds disponibles et explique quel calcul tu utiliserais maintenant."
                    )
                },
                enabled = !state.chatBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("État réel")
            }
        }
    }
}

@Composable
private fun NodesScreen(
    state: JadeUiState,
    vm: JadeViewModel,
    localNetworkGranted: Boolean,
    requestLocalNetwork: () -> Unit,
    needsLocalNetworkPermission: Boolean,
    modifier: Modifier = Modifier
) {
    var showPairing by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }

    PageColumn(modifier) {
        PageTitle("Mes nœuds", "Device Registry + routes LAN/Tailscale")

        state.selfModel?.knownNodes?.forEach { node ->
            InfoCard(node.name) {
                Text("${node.kind} • ${node.status}", fontWeight = FontWeight.SemiBold)
                if (node.runtimeVersion.isNotBlank()) {
                    Text(
                        "Runtime ${node.runtimeVersion}" +
                            if (node.runtimeChannel.isNotBlank()) " • ${node.runtimeChannel}" else ""
                    )
                }
                if (node.brainBackend.isNotBlank()) {
                    Text(
                        "Cerveau : ${node.brainBackend}" +
                            if (node.brainModel.isNotBlank()) " / ${node.brainModel}" else ""
                    )
                }
                if (node.ramTotalGb > 0.0) {
                    Text("RAM : ${node.ramAvailableGb} / ${node.ramTotalGb} Go libres/total")
                }
                if (node.routes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Routes enregistrées :", fontWeight = FontWeight.SemiBold)
                    node.routes.forEach { route ->
                        val active = route.routeId == node.activeRouteId
                        Text(
                            "${if (active) "●" else "○"} ${route.kind} • ${route.status} • " +
                                "${route.host}:${route.port}" +
                                (route.latencyMs?.let { " • ${it} ms" } ?: "")
                        )
                        route.lastError?.takeIf { it.isNotBlank() }?.let {
                            Text("  $it")
                        }
                    }
                }
                if (node.kind != NodeKind.PHONE && node.routes.isEmpty()) {
                    Text("Aucune route enregistrée.")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { vm.refreshNodes() },
            enabled = !state.nodeBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.nodeBusy) "Sondage en cours…" else "Rafraîchir les nœuds")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.runComputeMeshProbe() },
            enabled = !state.taskBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tester le Compute Mesh en parallèle")
        }

        state.meshProbe?.let { mesh ->
            Spacer(Modifier.height(8.dp))
            InfoCard("Dernier test Mesh") {
                Text("${mesh.successCount}/${mesh.nodeResults.size} nœud(s) réussis")
                Text("Durée globale : ${mesh.completedAt - mesh.startedAt} ms")
                mesh.nodeResults.forEach { result ->
                    Text(
                        "• ${result.nodeName} — " +
                            if (result.success) "${result.durationMs} ms" else "échec : ${result.error}"
                    )
                }
            }
        }

        if (state.nodeMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.nodeMessage)
        }
        if (state.taskMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.taskMessage)
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { showPairing = !showPairing },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showPairing) "Masquer l'association" else "Ajouter un appareil ou une route")
        }

        if (showPairing) {
            Spacer(Modifier.height(8.dp))
            InfoCard("Association — une seule fois") {
                Text(
                    "Jade garde ensuite le nœud et ses routes. Pour un même PC, une seconde adresse LAN/Tailscale sera fusionnée si le runtime renvoie le même Node ID."
                )
                if (needsLocalNetworkPermission && !localNetworkGranted) {
                    Spacer(Modifier.height(6.dp))
                    Text("Android doit autoriser le réseau local pour tester une route LAN.")
                    Button(
                        onClick = requestLocalNetwork,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Autoriser le réseau local")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Adresse LAN, Tailscale ou DNS") },
                    placeholder = { Text("Ex : 100.x.x.x") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { value ->
                        port = value.filter { it.isDigit() }.take(5)
                    },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Jeton du Node Runtime") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.registerNode(
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 8765,
                            token = token.trim()
                        )
                        token = ""
                    },
                    enabled =
                        !state.nodeBusy &&
                            host.isNotBlank() &&
                            token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Associer + tester")
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    PageColumn(modifier) {
        PageTitle("Activité", "Ce que Jade fait et mesure")

        InfoCard("Cognitive Core") {
            if (state.cognitiveTrace.isEmpty()) {
                Text("Aucun cycle cognitif enregistré pour le moment.")
            } else {
                state.cognitiveTrace.take(16).forEach { event ->
                    Text(
                        "${event.phase} • ${if (event.success) "OK" else "WARN"} • ${event.summary}"
                    )
                    if (event.durationMs > 0) {
                        Text("  ${event.durationMs} ms")
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        InfoCard("Candidats d'apprentissage") {
            if (state.learningCandidates.isEmpty()) {
                Text("Aucun problème répétitif assez mesuré pour proposer une adaptation.")
            } else {
                state.learningCandidates.forEach { candidate ->
                    Text(candidate.title, fontWeight = FontWeight.SemiBold)
                    Text(candidate.description)
                    Text("Preuves : ${candidate.evidence}")
                    Text("Confiance : ${(candidate.confidence * 100).toInt()} %")
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        InfoCard("Tâches distribuées") {
            Text("File active : ${state.pendingTasks}")
            if (state.taskHistory.isEmpty()) {
                Text("Aucun historique.")
            } else {
                state.taskHistory.take(12).forEach { result ->
                    Text(
                        "• ${result.taskKind} — ${result.executedNodeName} — " +
                            "${result.durationMs} ms — ${if (result.success) "OK" else "ÉCHEC"}" +
                            if (result.fallbackUsed) " — fallback" else ""
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { vm.runDistributedProbe() },
                enabled = !state.taskBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Probe")
            }
            OutlinedButton(
                onClick = { vm.runMemoryConsolidation() },
                enabled = !state.taskBusy && state.memoryCount > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("Consolider")
            }
        }
    }
}

@Composable
private fun MemoryScreen(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    PageColumn(modifier) {
        PageTitle("Mémoire", "${state.memoryCount} élément(s) persistent(s)")
        InfoCard("Mémoire récente") {
            if (state.memories.isEmpty()) {
                Text("Aucune mémoire pour le moment.")
            } else {
                state.memories.take(24).forEach { memory ->
                    Text("${memory.type} • ${memory.content}")
                    Text("source=${memory.source} • confiance=${memory.confidence}")
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { vm.runMemoryConsolidation() },
            enabled = !state.taskBusy && state.memoryCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Vérifier / consolider la mémoire")
        }
    }
}

@Composable
private fun AdminScreen(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
    var toolIdea by remember { mutableStateOf("") }

    PageColumn(modifier) {
        PageTitle("Admin / Diagnostic", "Le capot technique de Jade")

        if (!state.adminConfigured) {
            InfoCard("Créer le PIN Admin") {
                Text(
                    "Ce PIN local protège les détails de diagnostic. Il n'est jamais envoyé au PC ni au VPS."
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(10) },
                    label = { Text("Nouveau PIN (4–10 chiffres)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.configureAdminPin(pin)
                        pin = ""
                    },
                    enabled = pin.length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Créer et ouvrir le mode Admin")
                }
            }
            return@PageColumn
        }

        if (!state.adminUnlocked) {
            InfoCard("Mode Admin verrouillé") {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(10) },
                    label = { Text("PIN Admin") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.unlockAdmin(pin)
                        pin = ""
                    },
                    enabled = pin.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Déverrouiller")
                }
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
            return@PageColumn
        }

        InfoCard("Tool Lab v1") {
            Text(
                "Jade peut maintenant concevoir du code d'outil candidat. " +
                    "Le candidat est versionné et contrôlé statiquement, mais jamais exécuté ni activé automatiquement."
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = toolIdea,
                onValueChange = { toolIdea = it.take(4_000) },
                label = { Text("Outil à développer") },
                placeholder = { Text("Ex : un outil qui analyse un fichier log et extrait les erreurs") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.proposeToolCandidate(toolIdea)
                    toolIdea = ""
                },
                enabled = toolIdea.isNotBlank() && !state.toolBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.toolBusy) "Conception…" else "Créer un candidat")
            }
            if (state.toolMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(state.toolMessage)
            }
            if (state.toolCandidates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                state.toolCandidates.take(6).forEach { candidate ->
                    Text(candidate.name, fontWeight = FontWeight.SemiBold)
                    Text("${candidate.status} • ${candidate.language}")
                    Text("SHA ${candidate.sourceSha256.take(12)}…")
                    if (candidate.validationWarnings.isNotEmpty()) {
                        Text("Revue : ${candidate.validationWarnings.joinToString(" | ").take(260)}")
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        InfoCard("Runtime Manager") {
            if (state.runtimes.isEmpty()) {
                Text("Aucun runtime distant enregistré.")
            } else {
                state.runtimes.forEach { runtime ->
                    Text(
                        "${runtime.nodeName} • ${if (runtime.online) "ONLINE" else "OFFLINE"} • " +
                            "runtime ${runtime.runtimeVersion} • ${runtime.channel}"
                    )
                    if (runtime.updateAvailable) {
                        Text("  Mise à niveau 0.1.1 recommandée.")
                    }
                }
            }
            Text(
                "La V0.1 suit version/canal et prépare stable/candidate. L'installation automatique distante reste volontairement désactivée pour ce premier runtime manager."
            )
        }

        Spacer(Modifier.height(10.dp))
        InfoCard("Journal") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("DEBUG détaillé")
                Switch(
                    checked = state.debugEnabled,
                    onCheckedChange = { vm.setDebugEnabled(it) }
                )
            }
            Text("Les tokens, mots de passe et secrets sont masqués dans les métadonnées.")
            Spacer(Modifier.height(6.dp))
            state.diagnostics.take(30).forEach { entry ->
                Text(
                    "${entry.level} • ${entry.event} • ${entry.message}"
                )
                if (entry.level == DiagnosticLevel.ERROR) {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.generateDiagnosticBundle() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Générer un bundle diagnostic")
        }
        state.diagnosticBundlePath?.let { path ->
            Spacer(Modifier.height(6.dp))
            Text("Bundle créé : $path")
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { vm.lockAdmin() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Verrouiller le mode Admin")
        }
    }
}

@Composable
private fun PageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
