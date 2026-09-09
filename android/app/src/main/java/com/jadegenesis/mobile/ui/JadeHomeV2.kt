package com.jadegenesis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadegenesis.mobile.BuildConfig
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.night.NightLearningInbox

internal data class PerceptionActions(
    val captureNow: () -> Unit,
    val armObservation: () -> Unit,
    val openCrop: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JadeHomeV2(
    state: JadeUiState,
    vm: JadeViewModel,
    actions: PerceptionActions,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    var lastSent by remember { mutableStateOf("") }
    var vitalOpen by remember { mutableStateOf(false) }
    var perceptionOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val night = remember(state.selfModel, state.taskHistory, state.cognitiveTrace) {
        runCatching { NightLearningInbox(context).latest() }.getOrNull()
    }
    val self = state.selfModel
    val onlineRemote = self?.knownNodes?.count {
        it.kind != NodeKind.PHONE && it.status == NodeStatus.ONLINE
    } ?: 0
    val preferred = self?.knownNodes?.firstOrNull { it.nodeId == self.preferredComputeNodeId }

    Column(modifier = modifier.background(JadeColors.Bg)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "JADE GENESIS",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = JadeColors.JadeDim,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            "Bonsoir.",
                            style = MaterialTheme.typography.headlineLarge,
                            color = JadeColors.Ink
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(V2PillShape)
                            .background(JadeColors.Surface)
                            .clickable { vitalOpen = !vitalOpen }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        V2Dot(if (state.error == null) JadeColors.Jade else JadeColors.Warn)
                        Text(
                            if (state.error == null) "ACTIVE" else "ATTENTION",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            color = JadeColors.InkMuted
                        )
                        Icon(
                            if (vitalOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (vitalOpen) "Replier l'état" else "Déplier l'état",
                            tint = JadeColors.Muted2,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (state.screenArmed) {
                item {
                    V2InlineMessage(
                        "Observation armée · Android garde une notification visible jusqu'à la capture.",
                        JadeColors.Info
                    )
                }
            }

            if (vitalOpen) {
                item {
                    V2Card(title = "Signes vitaux") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            V2Metric(
                                "CERVEAU",
                                self?.activeBrain?.displayName ?: "inconnu",
                                Modifier.weight(1f)
                            )
                            V2Metric("NŒUDS", "$onlineRemote distants", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            V2Metric(
                                "RESSOURCES",
                                self?.resourceBudget?.mode?.name ?: "?",
                                Modifier.weight(1f)
                            )
                            V2Metric(
                                "CALCUL PRÉFÉRÉ",
                                preferred?.name ?: "Pixel / local",
                                Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Android ${BuildConfig.VERSION_NAME}",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            color = JadeColors.Muted3
                        )
                    }
                }
            }

            item {
                V2Card(
                    title = "Cette nuit",
                    borderColor = if (night != null) JadeColors.Jade.copy(alpha = 0.25f) else JadeColors.Border
                ) {
                    if (night == null) {
                        Text(
                            "Aucun rapport Night Learning synchronisé sur ce Pixel pour le moment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JadeColors.Muted
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V2StatusBadge("NIGHT LEARNING", JadeColors.Jade)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                relativeTime(night.generatedAt),
                                fontFamily = JadeMono,
                                fontSize = 11.sp,
                                color = JadeColors.Muted3
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "J'ai préparé ${night.hypotheses.size} hypothèse(s), " +
                                "${night.experiments.size} expérience(s) et " +
                                "${night.improvementCandidates.size} candidat(s) à examiner.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = JadeColors.Ink
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "${night.researchEvidence.size} preuve(s) · confiance runtime ${(night.runtimeConfidence * 100).toInt()} %",
                            fontFamily = JadeMono,
                            fontSize = 11.sp,
                            color = JadeColors.Muted2
                        )
                    }
                }
            }

            if (lastSent.isNotBlank()) {
                item { ConversationBubbleV2(fromJade = false, text = lastSent) }
            }
            if (state.chatBusy) {
                item {
                    ConversationBubbleV2(
                        fromJade = true,
                        text = "J'analyse le contexte, les nœuds disponibles et les preuves utiles…",
                        busy = true
                    )
                }
            } else if (state.response.isNotBlank()) {
                item { ConversationBubbleV2(fromJade = true, text = state.response) }
            } else {
                item {
                    ConversationBubbleV2(
                        fromJade = true,
                        text = "Je suis prête. Parle-moi de ce que tu veux faire, ou montre-moi quelque chose à analyser."
                    )
                }
            }

            if (state.screenMessage.isNotBlank()) {
                item { V2InlineMessage(state.screenMessage, JadeColors.Info) }
            }
            if (state.researchMessage.isNotBlank()) {
                item { V2InlineMessage(state.researchMessage, JadeColors.Violet) }
            }
            state.error?.let { message ->
                item { V2InlineMessage(message, JadeColors.Error) }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V2SecondaryButton(
                        text = "État réel",
                        onClick = {
                            lastSent = "Évalue tes ressources et tes nœuds disponibles."
                            vm.send(lastSent)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !state.chatBusy
                    )
                    V2SecondaryButton(
                        text = "Qui es-tu ?",
                        onClick = {
                            lastSent = "Qui es-tu ?"
                            vm.send(lastSent)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !state.chatBusy
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JadeColors.NavBg)
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { perceptionOpen = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(JadeColors.Jade.copy(alpha = 0.10f))
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Perception", tint = JadeColors.Jade)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(10_000) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                placeholder = { Text("Parler à Jade…", color = JadeColors.Muted3) },
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val clean = draft.trim()
                    if (clean.isNotEmpty() && !state.chatBusy) {
                        lastSent = clean
                        vm.send(clean)
                        draft = ""
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = JadeColors.Surface,
                    unfocusedContainerColor = JadeColors.Surface,
                    focusedBorderColor = JadeColors.Jade.copy(alpha = 0.42f),
                    unfocusedBorderColor = JadeColors.BorderStrong,
                    focusedTextColor = JadeColors.Ink,
                    unfocusedTextColor = JadeColors.Ink
                )
            )
            IconButton(
                onClick = {
                    val clean = draft.trim()
                    if (clean.isNotEmpty()) {
                        lastSent = clean
                        vm.send(clean)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank() && !state.chatBusy,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (draft.isNotBlank() && !state.chatBusy) JadeColors.Jade
                        else JadeColors.SurfaceKey
                    )
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "Envoyer",
                    tint = if (draft.isNotBlank() && !state.chatBusy) JadeColors.JadeInk else JadeColors.Muted3
                )
            }
        }
    }

    if (perceptionOpen) {
        ModalBottomSheet(
            onDismissRequest = { perceptionOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = JadeColors.SheetBg,
            contentColor = JadeColors.Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                MobilePageHeader(
                    eyebrow = "Perception",
                    title = "Montrer quelque chose à Jade",
                    subtitle = "Chaque capture reste déclenchée ou confirmée par toi."
                )
                Spacer(Modifier.height(14.dp))
                PerceptionAction(
                    Icons.Filled.CameraAlt,
                    "Capturer maintenant",
                    "Capture Android puis analyse immédiate.",
                    enabled = !state.screenBusy && !state.researchBusy
                ) {
                    perceptionOpen = false
                    actions.captureNow()
                }
                PerceptionAction(
                    Icons.Filled.Visibility,
                    "Armer l'observation",
                    "Une notification Android visible permet de capturer plus tard.",
                    enabled = !state.screenBusy && !state.researchBusy
                ) {
                    perceptionOpen = false
                    actions.armObservation()
                }
                PerceptionAction(
                    Icons.Filled.Crop,
                    "Choisir une zone",
                    "Cadre l'image et donne une consigne avant l'analyse.",
                    enabled = !state.screenBusy && !state.researchBusy
                ) {
                    perceptionOpen = false
                    actions.openCrop()
                }
                PerceptionAction(
                    Icons.Filled.Visibility,
                    "Analyser la dernière image",
                    "Utilise la dernière image ou zone déjà préparée.",
                    enabled = !state.screenBusy && !state.researchBusy
                ) {
                    perceptionOpen = false
                    vm.analyzePreparedVisual()
                }
                PerceptionAction(
                    Icons.Filled.DesktopWindows,
                    "Observer l'écran du PC",
                    "Demande au nœud PC compatible d'analyser son écran.",
                    enabled = !state.screenBusy && !state.researchBusy
                ) {
                    perceptionOpen = false
                    vm.analyzePcScreen()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Aucune observation cachée : les autorisations Android et l'état armé restent visibles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JadeColors.Muted
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConversationBubbleV2(fromJade: Boolean, text: String, busy: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromJade) Arrangement.Start else Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (fromJade) 0.92f else 0.82f)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (fromJade) 5.dp else 18.dp,
                        bottomEnd = if (fromJade) 18.dp else 5.dp
                    )
                )
                .background(if (fromJade) JadeColors.Surface else JadeColors.Jade.copy(alpha = 0.15f))
                .padding(15.dp)
        ) {
            if (fromJade) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V2Dot(JadeColors.Jade)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "JADE",
                        fontFamily = JadeMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = JadeColors.JadeDim
                    )
                    if (busy) {
                        Spacer(Modifier.width(9.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = JadeColors.Jade
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, color = JadeColors.Ink)
        }
    }
}

@Composable
private fun PerceptionAction(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp)
            .clip(V2SmallShape)
            .background(JadeColors.Surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(JadeColors.Jade.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) JadeColors.Jade else JadeColors.Muted3)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) JadeColors.Ink else JadeColors.Muted3)
            Text(description, style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
        }
    }
}
