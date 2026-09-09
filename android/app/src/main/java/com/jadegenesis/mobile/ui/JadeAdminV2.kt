package com.jadegenesis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadegenesis.mobile.BuildConfig

@Composable
internal fun JadeAdminV2(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
    var toolIdea by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.background(JadeColors.Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MobilePageHeader(
                eyebrow = "Capot technique",
                title = "Admin",
                subtitle = if (state.adminUnlocked) "Session locale déverrouillée" else "Protégé par PIN local"
            )
        }

        if (!state.adminConfigured) {
            item {
                AdminPinCard(
                    title = "Créer le PIN Admin",
                    message = "Ce PIN protège uniquement le mode technique du téléphone. Il n'est jamais envoyé au PC ni au VPS.",
                    pin = pin,
                    onDigit = { if (pin.length < 10) pin += it },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    action = "Créer et ouvrir",
                    enabled = pin.length >= 4,
                    onAction = {
                        vm.configureAdminPin(pin)
                        pin = ""
                    }
                )
            }
        } else if (!state.adminUnlocked) {
            item {
                AdminPinCard(
                    title = "Mode Admin verrouillé",
                    message = "Déverrouille le capot technique pour voir Tool Lab, Runtime Eval, Evolution et les diagnostics.",
                    pin = pin,
                    onDigit = { if (pin.length < 10) pin += it },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    action = "Déverrouiller",
                    enabled = pin.length >= 4,
                    onAction = {
                        vm.unlockAdmin(pin)
                        pin = ""
                    }
                )
            }
            state.error?.let { item { V2InlineMessage(it, JadeColors.Error) } }
        } else {
            item {
                V2Card(title = "Identité") {
                    val self = state.selfModel
                    V2KeyValue("Version Android", BuildConfig.VERSION_NAME)
                    V2KeyValue("Jade ID", self?.identity?.jadeId ?: "indisponible")
                    V2KeyValue("Nœud interface", self?.nodeId ?: "inconnu")
                    V2KeyValue("Cerveau actif", self?.activeBrain?.displayName ?: "inconnu")
                }
            }

            item {
                V2Card(title = "Tool Lab") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V2StatusBadge("NON ACTIF", JadeColors.Warn)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${state.toolCandidates.size} candidat(s)",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            color = JadeColors.Muted3
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Jade peut concevoir un outil candidat. Le code reste versionné et contrôlé, sans activation automatique. Un futur exécuteur devra passer par une sandbox contrôlée.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JadeColors.Muted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = toolIdea,
                        onValueChange = { toolIdea = it.take(4_000) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        label = { Text("Outil à développer") },
                        placeholder = { Text("Ex : analyser un fichier log et extraire les erreurs") },
                        minLines = 3,
                        colors = adminFieldColors()
                    )
                    Spacer(Modifier.height(9.dp))
                    V2PrimaryButton(
                        text = if (state.toolBusy) "Conception…" else "Créer un candidat",
                        onClick = {
                            vm.proposeToolCandidate(toolIdea)
                            toolIdea = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolIdea.isNotBlank() && !state.toolBusy
                    )
                    if (state.toolMessage.isNotBlank()) {
                        Spacer(Modifier.height(9.dp))
                        V2InlineMessage(state.toolMessage)
                    }
                    state.toolCandidates.take(5).forEach { candidate ->
                        Spacer(Modifier.height(10.dp))
                        V2Divider()
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = JadeColors.Ink)
                                Text(
                                    "${candidate.language} · SHA ${candidate.sourceSha256.take(10)}…",
                                    fontFamily = JadeMono,
                                    fontSize = 11.sp,
                                    color = JadeColors.Muted2
                                )
                            }
                            V2StatusBadge(candidate.status, JadeColors.Warn)
                        }
                        if (candidate.validationWarnings.isNotEmpty()) {
                            Spacer(Modifier.height(5.dp))
                            Text(
                                candidate.validationWarnings.joinToString(" · ").take(320),
                                style = MaterialTheme.typography.bodySmall,
                                color = JadeColors.Muted
                            )
                        }
                    }
                }
            }

            item {
                V2Card(title = "Runtime Eval") {
                    val attempts = state.taskHistory.take(50)
                    val success = attempts.count { it.success }
                    val fallbacks = attempts.count { it.fallbackUsed }
                    V2KeyValue("Échantillons UI", attempts.size.toString())
                    V2KeyValue("Succès", if (attempts.isEmpty()) "—" else "$success/${attempts.size}")
                    V2KeyValue("Fallbacks", fallbacks.toString())
                    V2InlineMessage(
                        "L'écran n'invente aucun score absent de JadeUiState. Les rapports Runtime Eval complets restent produits par le moteur dédié.",
                        JadeColors.Info
                    )
                }
            }

            item {
                V2Card(title = "Evolution Engine") {
                    V2StatusBadge("GARDE-FOUS ACTIFS", JadeColors.Jade)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pipeline : CANDIDAT → PROPOSÉ → TEST → VALIDÉ → PROMOTION SÉPARÉE. Un meilleur score ne suffit jamais à auto-promouvoir une configuration.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JadeColors.InkMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    V2KeyValue("Promotion auto", "INTERDITE")
                    V2KeyValue("Réécriture production", "INTERDITE")
                    V2KeyValue("Shell distant arbitraire", "INTERDIT")
                    V2KeyValue("Approbation utilisateur", "REQUISE")
                }
            }

            item {
                V2Card(title = "Runtime Manager") {
                    if (state.runtimes.isEmpty()) {
                        Text("Aucun runtime distant exposé.", color = JadeColors.Muted)
                    } else {
                        state.runtimes.forEachIndexed { index, runtime ->
                            if (index > 0) V2Divider()
                            V2KeyValue(runtime.nodeName, if (runtime.online) "ONLINE" else "OFFLINE")
                            V2KeyValue("Version", runtime.runtimeVersion.ifBlank { "?" })
                            V2KeyValue("Canal", runtime.channel.ifBlank { "?" })
                            if (runtime.updateAvailable) V2StatusBadge("MISE À JOUR", JadeColors.Warn)
                        }
                    }
                }
            }

            item {
                V2Card(title = "Sécurité") {
                    V2KeyValue("PIN Admin", "LOCAL UNIQUEMENT")
                    V2KeyValue("Secrets logs", "MASQUÉS")
                    V2KeyValue("Night Learning", "REVUE UNIQUEMENT")
                    V2KeyValue("Transport nœuds", "AUTHENTIFIÉ")
                    Text(
                        "Les actions Android sensibles restent explicites : capture visible, import d'image confirmé et privilèges non auto-accordés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JadeColors.Muted
                    )
                }
            }

            item {
                V2Card(title = "Journal") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("DEBUG détaillé", fontSize = 13.5.sp, color = JadeColors.Ink)
                            Text("Tokens et secrets masqués", fontSize = 12.sp, color = JadeColors.Muted)
                        }
                        Switch(
                            checked = state.debugEnabled,
                            onCheckedChange = { vm.setDebugEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JadeColors.JadeInk,
                                checkedTrackColor = JadeColors.Jade,
                                uncheckedTrackColor = JadeColors.SurfaceKey
                            )
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    state.diagnostics.take(20).forEach { entry ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            V2Dot(diagnosticTone(entry.level))
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entry.level} · ${entry.event}",
                                    fontFamily = JadeMono,
                                    fontSize = 11.sp,
                                    color = diagnosticTone(entry.level)
                                )
                                Text(entry.message, fontSize = 12.5.sp, color = JadeColors.Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    V2SecondaryButton(
                        "Générer un bundle diagnostic",
                        onClick = { vm.generateDiagnosticBundle() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.diagnosticBundlePath?.let {
                        Spacer(Modifier.height(7.dp))
                        Text(it, fontFamily = JadeMono, fontSize = 11.sp, color = JadeColors.Muted2)
                    }
                }
            }

            item {
                V2SecondaryButton(
                    "Verrouiller le mode Admin",
                    onClick = { vm.lockAdmin() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AdminPinCard(
    title: String,
    message: String,
    pin: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    V2Card(title = title, borderColor = JadeColors.Jade.copy(alpha = 0.20f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(JadeColors.Jade.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = JadeColors.Jade)
            }
            Spacer(Modifier.size(11.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(JadeColors.SurfaceSunken),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (pin.isEmpty()) "••••" else "•".repeat(pin.length),
                fontFamily = JadeMono,
                fontSize = 24.sp,
                letterSpacing = 6.sp,
                color = if (pin.isEmpty()) JadeColors.Muted3 else JadeColors.JadeSoft
            )
        }
        Spacer(Modifier.height(12.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    V2SecondaryButton(digit, { onDigit(digit) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            V2SecondaryButton("0", { onDigit("0") }, Modifier.weight(2f))
            IconButton(
                onClick = onBackspace,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
            ) {
                Icon(Icons.Filled.Backspace, contentDescription = "Effacer", tint = JadeColors.JadeSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        V2PrimaryButton(action, onAction, Modifier.fillMaxWidth(), enabled)
    }
}

@Composable
private fun adminFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = JadeColors.SurfaceSunken,
    unfocusedContainerColor = JadeColors.SurfaceSunken,
    focusedBorderColor = JadeColors.Jade.copy(alpha = 0.42f),
    unfocusedBorderColor = JadeColors.BorderStrong,
    focusedTextColor = JadeColors.Ink,
    unfocusedTextColor = JadeColors.Ink
)
