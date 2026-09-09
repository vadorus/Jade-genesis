package com.jadegenesis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JadeNodesV2(
    state: JadeUiState,
    vm: JadeViewModel,
    needsLocalNetworkPermission: Boolean,
    localNetworkGranted: Boolean,
    requestLocalNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedNode by remember { mutableStateOf<GenesisNode?>(null) }
    var pairingOpen by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }
    val nodes = state.selfModel?.knownNodes.orEmpty()
    val online = nodes.count { it.kind == NodeKind.PHONE || it.status.name == "ONLINE" || it.status.name == "LOCAL" }

    LazyColumn(
        modifier = modifier.background(JadeColors.Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MobilePageHeader(
                eyebrow = "Compute Mesh",
                title = "Mes nœuds",
                subtitle = "$online/${nodes.size} disponibles",
                trailing = {
                    IconButton(onClick = { vm.refreshNodes() }, enabled = !state.nodeBusy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rafraîchir", tint = JadeColors.Jade)
                    }
                }
            )
        }

        if (nodes.isEmpty()) {
            item {
                V2Card {
                    Text("Aucun nœud exposé par le Self Model.", color = JadeColors.Muted)
                }
            }
        } else {
            items(nodes.size) { index ->
                val node = nodes[index]
                NodeSummaryCard(node = node, onClick = { selectedNode = node })
            }
        }

        if (state.nodeBusy) {
            item { V2InlineMessage("Jade sonde les routes enregistrées…", JadeColors.Info) }
        }
        if (state.nodeMessage.isNotBlank()) {
            item { V2InlineMessage(state.nodeMessage) }
        }

        item {
            V2Card(title = "Compute Mesh") {
                Text(
                    "Tester les nœuds compatibles permet de mesurer leur réponse réelle avant de leur confier une tâche.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JadeColors.Muted
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V2SecondaryButton(
                        "Rafraîchir",
                        onClick = { vm.refreshNodes() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.nodeBusy
                    )
                    V2PrimaryButton(
                        "Tester le Mesh",
                        onClick = { vm.runComputeMeshProbe() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.taskBusy
                    )
                }
                state.meshProbe?.let { mesh ->
                    Spacer(Modifier.height(12.dp))
                    V2SectionLabel("Dernier test")
                    Spacer(Modifier.height(7.dp))
                    V2KeyValue("Réussite", "${mesh.successCount}/${mesh.nodeResults.size}")
                    V2KeyValue("Durée", "${mesh.completedAt - mesh.startedAt} ms")
                    mesh.nodeResults.take(6).forEach { result ->
                        V2KeyValue(
                            result.nodeName,
                            if (result.success) "${result.durationMs} ms" else "ÉCHEC"
                        )
                    }
                }
            }
        }

        item {
            V2SecondaryButton(
                text = if (pairingOpen) "Masquer l'association" else "Ajouter un appareil / une route",
                onClick = { pairingOpen = !pairingOpen },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (pairingOpen) {
            item {
                V2Card(title = "Association") {
                    Text(
                        "Une route LAN, Tailscale ou DNS est testée puis rattachée au Node ID renvoyé par le runtime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JadeColors.Muted
                    )
                    if (needsLocalNetworkPermission && !localNetworkGranted) {
                        Spacer(Modifier.height(10.dp))
                        V2InlineMessage(
                            "Android doit autoriser le réseau local avant de tester une route LAN.",
                            JadeColors.Warn
                        )
                        Spacer(Modifier.height(8.dp))
                        V2SecondaryButton(
                            "Autoriser le réseau local",
                            onClick = requestLocalNetwork,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    NodeField(host, { host = it }, "Adresse", "100.x.x.x ou nom DNS")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = nodeFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text("Jeton du Node Runtime") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = nodeFieldColors()
                    )
                    Spacer(Modifier.height(12.dp))
                    V2PrimaryButton(
                        "Associer + tester",
                        onClick = {
                            vm.registerNode(host.trim(), port.toIntOrNull() ?: 8765, token.trim())
                            token = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.nodeBusy && host.isNotBlank() && token.isNotBlank()
                    )
                }
            }
        }

        if (state.taskMessage.isNotBlank()) {
            item { V2InlineMessage(state.taskMessage, JadeColors.Info) }
        }
    }

    selectedNode?.let { node ->
        ModalBottomSheet(
            onDismissRequest = { selectedNode = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = JadeColors.SheetBg,
            contentColor = JadeColors.Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                MobilePageHeader(
                    eyebrow = node.kind.name,
                    title = node.name,
                    subtitle = nodeRole(node)
                )
                Spacer(Modifier.height(14.dp))
                V2KeyValue("État", node.status.name)
                V2KeyValue("Node ID", node.nodeId)
                if (node.runtimeVersion.isNotBlank()) V2KeyValue("Runtime", node.runtimeVersion)
                if (node.osName.isNotBlank()) V2KeyValue("OS", node.osName)
                if (node.cpuName.isNotBlank()) V2KeyValue("CPU", node.cpuName)
                if (node.cpuCores > 0) V2KeyValue("Cœurs", node.cpuCores.toString())
                if (node.ramTotalGb > 0.0) {
                    V2KeyValue("RAM", "${fmt(node.ramAvailableGb)} / ${fmt(node.ramTotalGb)} Go libres")
                }
                if (node.gpuName.isNotBlank()) V2KeyValue("GPU", node.gpuName)
                if (node.gpuVramTotalGb > 0.0) {
                    V2KeyValue("VRAM", "${fmt(node.gpuVramFreeGb)} / ${fmt(node.gpuVramTotalGb)} Go libres")
                }
                if (node.brainLoadedModel.isNotBlank()) {
                    V2KeyValue("Modèle chargé", node.brainLoadedModel)
                } else if (node.brainModel.isNotBlank()) {
                    V2KeyValue("Modèle", node.brainModel)
                }
                if (node.brainTokensPerSecond > 0.0) {
                    V2KeyValue("Débit", "${fmt(node.brainTokensPerSecond)} tok/s")
                }
                if (node.routes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    V2SectionLabel("Routes")
                    Spacer(Modifier.height(6.dp))
                    node.routes.forEach { route ->
                        V2Card(
                            modifier = Modifier.padding(bottom = 7.dp),
                            borderColor = if (route.routeId == node.activeRouteId) JadeColors.Jade.copy(alpha = 0.28f) else JadeColors.Border
                        ) {
                            Text(
                                "${route.kind} · ${route.status}",
                                fontFamily = JadeMono,
                                fontSize = 11.sp,
                                color = JadeColors.JadeDim
                            )
                            Text("${route.host}:${route.port}", fontFamily = JadeMono, fontSize = 12.sp)
                            route.latencyMs?.let {
                                Text("${it} ms", fontFamily = JadeMono, fontSize = 11.sp, color = JadeColors.Muted2)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NodeSummaryCard(node: GenesisNode, onClick: () -> Unit) {
    val tone = nodeTone(node)
    V2Card(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = tone.copy(alpha = 0.18f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            V2Dot(tone)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleLarge, color = JadeColors.Ink)
                Text(nodeRole(node), style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
            }
            V2StatusBadge(node.status.name, tone)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val route = node.routes.firstOrNull { it.routeId == node.activeRouteId } ?: node.routes.firstOrNull()
            V2Metric(
                "ROUTE",
                route?.kind?.name ?: if (node.kind == NodeKind.PHONE) "LOCAL" else "—",
                Modifier.weight(1f)
            )
            V2Metric(
                "LATENCE",
                route?.latencyMs?.let { "$it ms" } ?: "—",
                Modifier.weight(1f)
            )
            V2Metric(
                "RUNTIME",
                node.runtimeVersion.ifBlank { "—" },
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NodeField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        colors = nodeFieldColors()
    )
}

@Composable
private fun nodeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = JadeColors.SurfaceSunken,
    unfocusedContainerColor = JadeColors.SurfaceSunken,
    focusedBorderColor = JadeColors.Jade.copy(alpha = 0.42f),
    unfocusedBorderColor = JadeColors.BorderStrong,
    focusedTextColor = JadeColors.Ink,
    unfocusedTextColor = JadeColors.Ink
)
