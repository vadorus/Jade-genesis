package com.jadegenesis.mobile.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus

private const val LOCAL_NETWORK_PERMISSION =
    "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
fun JadeApp(vm: JadeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    var pcHost by remember { mutableStateOf("") }
    var pcPort by remember { mutableStateOf("8765") }
    var pcToken by remember { mutableStateOf("") }
    var distributedText by remember {
        mutableStateOf(
            "Jade Genesis répartit ses tâches selon les ressources disponibles."
        )
    }

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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "JADE GENESIS",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Adaptive Distributed Core Prototype " +
                        (state.selfModel?.identity?.version ?: "0.0.5"),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(16.dp))

                state.selfModel?.let { self ->
                    InfoCard("Identité") {
                        Text(self.identity.name)
                        Text("Version ${self.identity.version}")
                        Text("ID : ${self.identity.jadeId}")
                        Text("Nœud d'interface actuel : ${self.nodeId}")
                        Text("Brain : ${self.activeBrain.displayName}")
                    }

                    Spacer(Modifier.height(10.dp))

                    InfoCard("Corps actuel") {
                        val d = self.device
                        Text(
                            "${d.manufacturer} ${d.model}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("Android ${d.androidVersion} — API ${d.sdkInt}")
                        Text("SoC : ${d.socManufacturer} ${d.socModel}")
                        Text("CPU : ${d.cpuCores} cœurs logiques")
                        Text("RAM : ${d.ramTotalGb} Go")
                        Text("Libre : ${d.ramAvailableGb} Go RAM")
                        Text(
                            "Mémoire Jade : ${d.processHeapUsedMb} / " +
                                "${d.processHeapMaxMb} Mo"
                        )
                        Text("Classe mémoire Android : ${d.appMemoryClassMb} Mo")
                        Text("Stockage libre : ${d.storageFreeGb} Go")
                        Text(
                            "Batterie : ${d.batteryPercent}%" +
                                if (d.charging) " — en charge" else ""
                        )
                        Text(
                            "Économie d'énergie : " +
                                if (d.powerSaveMode) "active" else "inactive"
                        )
                        Text("Thermique : ${d.thermalStatus}")
                    }

                    Spacer(Modifier.height(10.dp))

                    InfoCard("Resource Governor") {
                        val r = self.resourceBudget
                        Text(
                            "Mode : ${r.mode}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Budget de travail : " +
                                "${r.recommendedWorkingSetMb} Mo"
                        )
                        Text(
                            "Réserve RAM système cible : " +
                                "${r.systemRamReserveGb} Go"
                        )
                        Text(
                            "Tâches parallèles max : " +
                                "${r.maxParallelTasks}"
                        )
                        Text(
                            "Travail lourd en arrière-plan : " +
                                if (r.heavyBackgroundWorkAllowed) {
                                    "autorisé"
                                } else {
                                    "non"
                                }
                        )
                        Text(
                            "Préférer un autre nœud pour le lourd : " +
                                if (r.preferRemoteCompute) "oui" else "non"
                        )
                        Text(
                            "Tranche de travail : " +
                                "${r.maxTaskSliceSeconds} s max"
                        )

                        Spacer(Modifier.height(4.dp))

                        r.reasons.forEach {
                            Text("• $it")
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    InfoCard("Node Manager") {
                        val preferred = self.knownNodes.firstOrNull {
                            it.nodeId == self.preferredComputeNodeId
                        }

                        Text(
                            "Nœuds connus : ${self.knownNodes.size}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Nœud de calcul général préféré : " +
                                (preferred?.name ?: "aucun")
                        )

                        Spacer(Modifier.height(6.dp))

                        self.knownNodes.forEach { node ->
                            val symbol = when (node.status) {
                                NodeStatus.LOCAL -> "●"
                                NodeStatus.ONLINE -> "✓"
                                NodeStatus.OFFLINE -> "○"
                                NodeStatus.ERROR -> "!"
                                NodeStatus.UNKNOWN -> "?"
                            }

                            Text(
                                "$symbol ${node.name} — ${node.kind} / ${node.status}",
                                fontWeight = if (
                                    node.nodeId == self.preferredComputeNodeId
                                ) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )

                            if (node.kind != NodeKind.PHONE) {
                                Text("  ${node.host}:${node.port}")
                            }

                            if (node.protocol.isNotBlank()) {
                                Text("  Protocole : ${node.protocol}")
                            }

                            if (node.cpuCores > 0) {
                                Text(
                                    "  CPU : ${node.cpuCores} cœurs" +
                                        if (node.cpuName.isNotBlank()) {
                                            " — ${node.cpuName}"
                                        } else {
                                            ""
                                        }
                                )
                            }

                            if (node.ramTotalGb > 0.0) {
                                Text(
                                    "  RAM : ${node.ramAvailableGb} / " +
                                        "${node.ramTotalGb} Go libres/total"
                                )
                            }

                            if (node.capabilities.isNotEmpty()) {
                                Text(
                                    "  Capacités : " +
                                        node.capabilities.joinToString()
                                )
                            }

                            node.lastError
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    Text("  Erreur : $it")
                                }

                            Spacer(Modifier.height(5.dp))
                        }

                        if (
                            needsLocalNetworkPermission &&
                            !localNetworkGranted
                        ) {
                            Text(
                                "Android 17 bloque le LAN tant que Jade " +
                                    "n'a pas l'autorisation Réseau local."
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    localNetworkLauncher.launch(
                                        LOCAL_NETWORK_PERMISSION
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Autoriser le réseau local")
                            }
                        } else {
                            Text("Réseau local : autorisé")
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = pcHost,
                            onValueChange = { pcHost = it },
                            label = { Text("IP du PC") },
                            placeholder = { Text("Ex : 192.168.1.25") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(6.dp))

                        OutlinedTextField(
                            value = pcPort,
                            onValueChange = { value ->
                                pcPort = value
                                    .filter { it.isDigit() }
                                    .take(5)
                            },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )

                        Spacer(Modifier.height(6.dp))

                        OutlinedTextField(
                            value = pcToken,
                            onValueChange = { pcToken = it },
                            label = { Text("Jeton du Node Runtime") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation =
                                PasswordVisualTransformation()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                vm.registerPcNode(
                                    host = pcHost.trim(),
                                    port = pcPort.toIntOrNull() ?: 8765,
                                    token = pcToken.trim()
                                )
                            },
                            enabled =
                                !state.nodeBusy &&
                                    localNetworkGranted &&
                                    pcHost.isNotBlank() &&
                                    pcToken.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.nodeBusy) {
                                    "Test en cours…"
                                } else {
                                    "Enregistrer + tester le PC"
                                }
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        Button(
                            onClick = { vm.refreshNodes() },
                            enabled =
                                !state.nodeBusy &&
                                    localNetworkGranted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Rafraîchir les nœuds")
                        }

                        if (state.nodeMessage.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.nodeMessage)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    InfoCard("Adaptive Distributed Tasks") {
                        val preferred = self.knownNodes.firstOrNull {
                            it.nodeId == self.preferredComputeNodeId
                        }

                        Text(
                            "Task Router : adaptatif 0.0.5",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Cible générale actuelle : ${preferred?.name ?: "local"}"
                        )
                        Text(
                            "Le choix par tâche combine ressources, capacité requise et historique mesuré."
                        )
                        Text(
                            "Liste blanche : genesis_probe, text_analysis. Aucune commande système arbitraire."
                        )
                        Text(
                            "Historique local : ${state.taskHistory.size} tâche(s) chargée(s)."
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { vm.runDistributedProbe() },
                            enabled = !state.taskBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.taskBusy) {
                                    "Routage en cours…"
                                } else {
                                    "Tester le calcul distribué"
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = distributedText,
                            onValueChange = { distributedText = it.take(12_000) },
                            label = { Text("Texte pour text_analysis") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )

                        Spacer(Modifier.height(6.dp))

                        Button(
                            onClick = {
                                vm.runDistributedTextAnalysis(distributedText)
                            },
                            enabled =
                                !state.taskBusy &&
                                    distributedText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Analyser sur le meilleur nœud")
                        }

                        if (state.taskMessage.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.taskMessage)
                        }

                        state.lastTaskResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text("Tâche : ${result.taskId}")
                            Text("Type : ${result.taskKind}")
                            Text("État : ${result.status}")
                            Text(
                                "Nœud demandé : " +
                                    (result.requestedNodeName ?: "aucun")
                            )
                            Text(
                                "Nœud exécutant : ${result.executedNodeName} " +
                                    "(${result.executionLocation})"
                            )
                            Text("Durée mesurée : ${result.durationMs} ms")
                            Text(
                                "Fallback : " +
                                    if (result.fallbackUsed) "oui" else "non"
                            )
                            Text("Décision : ${result.routeReason}")
                            result.fallbackReason?.let {
                                Text("Raison fallback : $it")
                            }

                            if (result.attempts.isNotEmpty()) {
                                Text("Tentatives :")
                                result.attempts.forEachIndexed { index, attempt ->
                                    Text(
                                        "  ${index + 1}. ${attempt.nodeName} — " +
                                            "${if (attempt.success) "succès" else "échec"} — " +
                                            "${attempt.durationMs} ms"
                                    )
                                }
                            }

                            Text("Résultat : ${result.output.take(220)}")
                        }

                        if (state.taskHistory.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Dernières mesures :",
                                fontWeight = FontWeight.SemiBold
                            )
                            state.taskHistory.take(3).forEach { result ->
                                Text(
                                    "• ${result.taskKind} — ${result.executedNodeName} — " +
                                        "${result.durationMs} ms — ${result.status}"
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    InfoCard("Self Model") {
                        self.capabilities.forEach {
                            Text(
                                "${if (it.available) "✓" else "×"} " +
                                    "${it.name} — ${it.source}"
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.send("Qui es-tu ?") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Qui es-tu ?")
                    }

                    Button(
                        onClick = { vm.send("Inspecte ton téléphone") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Inspecter")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        vm.send(
                            "Évalue tes ressources, tes limites, ta RAM, " +
                                "ta batterie et ta température"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Évaluer mes ressources")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        vm.send(
                            "Quels nœuds connais-tu et lequel préfères-tu ?"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Évaluer mes nœuds")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Parler à Jade") },
                    placeholder = {
                        Text("Ex : Comment adaptes-tu tes tâches ?")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Envoyer")
                }

                if (state.response.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    InfoCard("Jade") {
                        Text(state.response)
                    }
                }

                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    InfoCard("Erreur") {
                        Text(it)
                    }
                }

                Spacer(Modifier.height(12.dp))

                InfoCard("Mémoire locale — ${state.memoryCount}") {
                    if (state.memories.isEmpty()) {
                        Text("Aucune mémoire pour le moment.")
                    } else {
                        state.memories.take(10).forEach { memory ->
                            Text(
                                "${memory.type} • ${memory.content}",
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit
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
