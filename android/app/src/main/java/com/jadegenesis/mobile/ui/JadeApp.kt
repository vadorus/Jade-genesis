package com.jadegenesis.mobile.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun JadeApp(vm: JadeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }

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
                    "Android Core Prototype " +
                        (state.selfModel?.identity?.version ?: "0.0.2"),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(16.dp))

                state.selfModel?.let { self ->
                    InfoCard("Identité") {
                        Text(self.identity.name)
                        Text("Version ${self.identity.version}")
                        Text("ID : ${self.identity.jadeId}")
                        Text("Nœud : ${self.nodeId}")
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

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Parler à Jade") },
                    placeholder = {
                        Text("Ex : Retiens que le test vaut 42")
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
