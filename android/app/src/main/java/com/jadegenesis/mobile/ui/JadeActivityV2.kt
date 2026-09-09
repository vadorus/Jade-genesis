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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import com.jadegenesis.mobile.model.LearningCandidate
import com.jadegenesis.mobile.night.NightLearningSnapshot

private enum class V2ActivityMode { COGNITIVE, NIGHT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JadeActivityV2(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(V2ActivityMode.COGNITIVE) }
    var candidate by remember { mutableStateOf<LearningCandidate?>(null) }
    var evidenceReport by remember { mutableStateOf<NightLearningSnapshot?>(null) }
    val night = remember(state.taskHistory, state.cognitiveTrace, state.learningCandidates) {
        runCatching { com.jadegenesis.mobile.night.NightLearningInbox(context).latest() }.getOrNull()
    }

    LazyColumn(
        modifier = modifier.background(JadeColors.Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MobilePageHeader(
                eyebrow = "Introspection",
                title = "Activité",
                subtitle = "Ce que Jade fait, mesure et prépare"
            )
        }
        item {
            ActivitySegment(mode = mode, onChange = { mode = it })
        }

        if (mode == V2ActivityMode.COGNITIVE) {
            item {
                V2Card(title = "Cognitive Core") {
                    Text(
                        "Un cycle est une chaîne observable : OBSERVE → PLAN → EXECUTE → VERIFY → REVISE → LEARN → COMPLETE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JadeColors.Muted
                    )
                }
            }

            if (state.cognitiveTrace.isEmpty()) {
                item { V2Card { Text("Aucun cycle cognitif enregistré.", color = JadeColors.Muted) } }
            } else {
                items(state.cognitiveTrace.take(18).size) { index ->
                    CognitiveEventCard(state.cognitiveTrace[index])
                }
            }

            item {
                V2Card(title = "Candidats d'apprentissage") {
                    if (state.learningCandidates.isEmpty()) {
                        Text(
                            "Aucun signal répétitif assez mesuré pour proposer une adaptation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JadeColors.Muted
                        )
                    } else {
                        state.learningCandidates.take(6).forEachIndexed { index, item ->
                            CandidateRow(item = item, onClick = { candidate = item })
                            if (index != state.learningCandidates.take(6).lastIndex) V2Divider()
                        }
                    }
                }
            }

            item {
                V2Card(title = "Tâches distribuées") {
                    V2KeyValue("File active", state.pendingTasks.toString())
                    if (state.taskHistory.isEmpty()) {
                        Text("Aucun historique.", color = JadeColors.Muted)
                    } else {
                        state.taskHistory.take(8).forEach { result ->
                            V2Divider()
                            Spacer(Modifier.height(7.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                V2Dot(if (result.success) JadeColors.Jade else JadeColors.Error)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(result.taskKind, fontSize = 13.sp, color = JadeColors.Ink)
                                    Text(
                                        "${result.executedNodeName} · ${result.durationMs} ms" +
                                            if (result.fallbackUsed) " · fallback" else "",
                                        fontFamily = JadeMono,
                                        fontSize = 11.sp,
                                        color = JadeColors.Muted2
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V2SecondaryButton(
                            "Probe",
                            onClick = { vm.runDistributedProbe() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.taskBusy
                        )
                        V2SecondaryButton(
                            "Consolider",
                            onClick = { vm.runMemoryConsolidation() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.taskBusy && state.memoryCount > 0
                        )
                    }
                }
            }
            if (state.taskMessage.isNotBlank()) {
                item { V2InlineMessage(state.taskMessage, JadeColors.Info) }
            }
        } else {
            if (night == null) {
                item {
                    V2Card(title = "Night Learning") {
                        Text(
                            "Aucun snapshot Night Learning validé n'est encore présent dans le cache Shared Genesis State de ce Pixel.",
                            color = JadeColors.Muted
                        )
                    }
                }
            } else {
                item { NightHeaderCard(night) }
                item {
                    NightReasoningCard(
                        report = night,
                        openEvidence = { evidenceReport = night }
                    )
                }
                item { NightCandidateCard(night) }
                item {
                    V2InlineMessage(
                        "Aucune expérience n'est exécutée depuis cet écran. Les candidats restent en revue jusqu'à un futur exécuteur sandboxé et une promotion séparée.",
                        JadeColors.Warn
                    )
                }
            }
        }
    }

    candidate?.let { selected ->
        ModalBottomSheet(
            onDismissRequest = { candidate = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = JadeColors.SheetBg,
            contentColor = JadeColors.Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                MobilePageHeader(
                    eyebrow = selected.status.name,
                    title = selected.title,
                    subtitle = "Candidat d'apprentissage local"
                )
                Spacer(Modifier.height(14.dp))
                Text(selected.description, style = MaterialTheme.typography.bodyLarge, color = JadeColors.Ink)
                Spacer(Modifier.height(12.dp))
                V2SectionLabel("Preuves")
                Spacer(Modifier.height(6.dp))
                Text(selected.evidence, style = MaterialTheme.typography.bodyMedium, color = JadeColors.Muted)
                Spacer(Modifier.height(12.dp))
                V2KeyValue("Confiance", "${(selected.confidence * 100).toInt()} %")
                V2KeyValue("Créé", fullDate(selected.createdAt))
                Spacer(Modifier.height(12.dp))
                V2InlineMessage(
                    "Examiner ne signifie pas appliquer. Une adaptation doit être testée et mesurée avant toute promotion.",
                    JadeColors.Warn
                )
                Spacer(Modifier.height(26.dp))
            }
        }
    }

    evidenceReport?.let { report ->
        ModalBottomSheet(
            onDismissRequest = { evidenceReport = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = JadeColors.SheetBg,
            contentColor = JadeColors.Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                MobilePageHeader(
                    eyebrow = "Preuves",
                    title = "Recherche Night Learning",
                    subtitle = "${report.researchEvidence.size} élément(s) validé(s) par l'inbox Android"
                )
                Spacer(Modifier.height(14.dp))
                if (report.researchEvidence.isEmpty()) {
                    Text("Aucune preuve externe disponible pour ce run.", color = JadeColors.Muted)
                } else {
                    report.researchEvidence.forEach { evidence ->
                        V2Card(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(evidence.title, fontWeight = FontWeight.SemiBold, color = JadeColors.Ink)
                            Spacer(Modifier.height(5.dp))
                            Text(evidence.snippet, fontSize = 13.sp, color = JadeColors.Muted)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${evidence.provider} · confiance ${(evidence.confidence * 100).toInt()} %",
                                fontFamily = JadeMono,
                                fontSize = 11.sp,
                                color = JadeColors.Muted3
                            )
                        }
                    }
                }
                V2InlineMessage(
                    "Ces preuves servent à préparer des hypothèses. Elles ne déclenchent ni exécution ni promotion automatique.",
                    JadeColors.Info
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ActivitySegment(mode: V2ActivityMode, onChange: (V2ActivityMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(JadeColors.SurfaceSunken).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegmentChoice(
            text = "Cognitif",
            selected = mode == V2ActivityMode.COGNITIVE,
            onClick = { onChange(V2ActivityMode.COGNITIVE) },
            modifier = Modifier.weight(1f)
        )
        SegmentChoice(
            text = "Night Learning",
            selected = mode == V2ActivityMode.NIGHT,
            onClick = { onChange(V2ActivityMode.NIGHT) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) JadeColors.Jade.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) JadeColors.Jade else JadeColors.Muted2
        )
    }
}

@Composable
private fun CognitiveEventCard(event: CognitiveTraceEvent) {
    V2Card(borderColor = if (event.success) JadeColors.Border else JadeColors.Warn.copy(alpha = 0.24f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(
                    (if (event.success) JadeColors.Jade else JadeColors.Warn).copy(alpha = 0.10f)
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    event.phase.name.take(2),
                    fontFamily = JadeMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (event.success) JadeColors.Jade else JadeColors.Warn
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(event.phase.name, fontFamily = JadeMono, fontSize = 11.sp, color = JadeColors.JadeDim)
                Text(event.summary, style = MaterialTheme.typography.bodyMedium, color = JadeColors.Ink)
            }
            if (event.durationMs > 0) {
                Text("${event.durationMs} ms", fontFamily = JadeMono, fontSize = 11.sp, color = JadeColors.Muted3)
            }
        }
    }
}

@Composable
private fun CandidateRow(item: LearningCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = JadeColors.Ink)
            Text(
                "confiance ${(item.confidence * 100).toInt()} % · ${item.status}",
                fontFamily = JadeMono,
                fontSize = 11.sp,
                color = JadeColors.Muted2
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = "Examiner", tint = JadeColors.Muted3)
    }
}

@Composable
private fun NightHeaderCard(report: NightLearningSnapshot) {
    V2Card(borderColor = JadeColors.Jade.copy(alpha = 0.24f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                V2SectionLabel("Night Learning")
                Spacer(Modifier.height(4.dp))
                Text("Run ${report.runId.take(18)}", fontFamily = JadeMono, fontSize = 12.sp, color = JadeColors.Ink)
                Text(fullDate(report.generatedAt), fontSize = 12.5.sp, color = JadeColors.Muted)
            }
            V2StatusBadge("REVUE", JadeColors.Jade)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            V2Metric("OBSERVATIONS", report.runtimeObservationCount.toString(), Modifier.weight(1f))
            V2Metric("CONFIANCE", "${(report.runtimeConfidence * 100).toInt()} %", Modifier.weight(1f))
            V2Metric("PREUVES", report.researchEvidence.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun NightReasoningCard(report: NightLearningSnapshot, openEvidence: () -> Unit) {
    V2Card(title = "Ce que Jade a préparé") {
        NightStep("1", "SIGNAL DÉTECTÉ", "${report.runtimeObservationCount} observation(s) Runtime Eval analysée(s).", JadeColors.Info)
        NightStep("2", "RECHERCHE", "${report.researchQuestions.size} question(s) ciblée(s), dérivées des métriques structurées.", JadeColors.Violet)
        NightStep(
            "3",
            "PREUVES",
            "${report.researchEvidence.size} élément(s), dont ${report.externalResearchEvidenceCount} externe(s).",
            JadeColors.Teal,
            onClick = openEvidence
        )
        NightStep(
            "4",
            "HYPOTHÈSE",
            report.hypotheses.firstOrNull()?.statement ?: "Aucune hypothèse retenue.",
            JadeColors.Warn
        )
        NightStep(
            "5",
            "EXPÉRIENCE PROPOSÉE",
            report.experiments.firstOrNull()?.let {
                "${it.target} · ${it.primaryMetric} · ${it.minimumSamplesPerSide}-${it.maximumSamplesPerSide} échantillons/côté"
            } ?: "Aucune expérience proposée.",
            JadeColors.Info
        )
        NightStep(
            "6",
            "CANDIDAT",
            report.improvementCandidates.firstOrNull()?.title ?: "Aucun candidat d'amélioration.",
            JadeColors.Jade,
            last = true
        )
    }
}

@Composable
private fun NightStep(
    index: String,
    title: String,
    description: String,
    tone: Color,
    onClick: (() -> Unit)? = null,
    last: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.11f)), contentAlignment = Alignment.Center) {
                Text(index, fontFamily = JadeMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tone)
            }
            if (!last) Box(Modifier.width(1.dp).height(38.dp).background(JadeColors.RailLine))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = JadeMono, fontSize = 11.sp, color = tone, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = JadeColors.InkMuted)
        }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, contentDescription = "Voir", tint = JadeColors.Muted3)
    }
}

@Composable
private fun NightCandidateCard(report: NightLearningSnapshot) {
    V2Card(title = "Pipeline d'évolution") {
        val candidate = report.improvementCandidates.firstOrNull()
        if (candidate == null) {
            Text("Aucun candidat sur ce run.", color = JadeColors.Muted)
            return@V2Card
        }
        V2StatusBadge(candidate.status, JadeColors.Jade)
        Spacer(Modifier.height(8.dp))
        Text(candidate.title, style = MaterialTheme.typography.titleMedium, color = JadeColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(candidate.rationale, style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
        Spacer(Modifier.height(12.dp))
        Text(
            "CANDIDAT  →  REVUE  →  SANDBOX  →  MESURE  →  VALIDÉ  →  PROMU",
            fontFamily = JadeMono,
            fontSize = 11.sp,
            color = JadeColors.JadeSoft
        )
        Spacer(Modifier.height(9.dp))
        V2KeyValue("Cible", candidate.target)
        V2KeyValue("Sandbox", if (candidate.sandboxRequired) "REQUIS" else "non déclaré")
        V2InlineMessage("État actuel : EN ATTENTE DE REVUE. Aucune promotion automatique.", JadeColors.Warn)
    }
}
