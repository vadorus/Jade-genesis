package com.jadegenesis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.jadegenesis.mobile.memory.MemoryHealthMonitor
import com.jadegenesis.mobile.memory.MemoryHealthSnapshot
import com.jadegenesis.mobile.memory.MemoryHealthStatus
import com.jadegenesis.mobile.model.MemorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JadeMemoryV2(
    state: JadeUiState,
    vm: JadeViewModel,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf("TOUT") }
    var selected by remember { mutableStateOf<MemorySnapshot?>(null) }
    val context = LocalContext.current
    val health by produceState<MemoryHealthSnapshot?>(
        initialValue = null,
        key1 = state.memoryCount
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { MemoryHealthMonitor(context).snapshot() }.getOrNull()
        }
    }
    val types = listOf("TOUT", "FACT", "OBSERVATION", "PROCEDURE", "HYPOTHESIS", "FAILURE", "EXPERIENCE", "KNOWLEDGE")
    val visible = state.memories.filter { filter == "TOUT" || it.type.equals(filter, ignoreCase = true) }

    LazyColumn(
        modifier = modifier.background(JadeColors.Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            MobilePageHeader(
                eyebrow = "Mémoire v2",
                title = "Ce que Jade sait",
                subtitle = "${state.memoryCount} élément(s) persistants"
            )
        }
        item {
            MemoryHealthCard(health)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                types.forEach { type ->
                    MemoryFilterChip(
                        text = type,
                        selected = filter == type,
                        onClick = { filter = type }
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                V2Card {
                    Text(
                        if (state.memories.isEmpty()) "Aucune mémoire pour le moment." else "Aucune mémoire dans ce filtre.",
                        color = JadeColors.Muted
                    )
                }
            }
        } else {
            items(visible.take(40).size) { index ->
                MemoryCardV2(visible[index], onClick = { selected = visible[index] })
            }
        }

        item {
            V2SecondaryButton(
                text = if (state.taskBusy) "Consolidation…" else "Vérifier / consolider la mémoire",
                onClick = { vm.runMemoryConsolidation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.taskBusy && state.memoryCount > 0
            )
        }
        if (state.taskMessage.isNotBlank()) {
            item { V2InlineMessage(state.taskMessage, JadeColors.Info) }
        }
    }

    selected?.let { memory ->
        val tone = memoryTone(memory.type)
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = JadeColors.SheetBg,
            contentColor = JadeColors.Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V2StatusBadge(memory.type, tone)
                    Spacer(Modifier.width(8.dp))
                    if (memory.supersededBy != null) {
                        V2StatusBadge("SUPERSÉDÉE", JadeColors.Warn)
                    } else if (memory.verifiedAt != null) {
                        V2StatusBadge("VÉRIFIÉE", JadeColors.Jade)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(memory.content, style = MaterialTheme.typography.bodyLarge, color = JadeColors.Ink)
                Spacer(Modifier.height(16.dp))
                V2SectionLabel("Provenance et confiance")
                Spacer(Modifier.height(6.dp))
                V2KeyValue("Source", memory.source)
                V2KeyValue("Nœud d'origine", memory.originNode.ifBlank { "inconnu" })
                V2KeyValue("Confiance", "${(memory.confidence.coerceIn(0.0, 1.0) * 100).toInt()} %")
                V2KeyValue("Créée", fullDate(memory.createdAt))
                V2KeyValue("Rappels", memory.recallCount.toString())
                if (memory.lastRecalledAt > 0L) V2KeyValue("Dernier rappel", fullDate(memory.lastRecalledAt))
                V2KeyValue("Vérification", memory.verifiedAt?.let(::fullDate) ?: "NON VÉRIFIÉE")
                memory.supersededBy?.let { V2KeyValue("Supersédée par", it) }
                Spacer(Modifier.height(12.dp))
                V2InlineMessage(
                    if (memory.verifiedAt != null) {
                        "Jade distingue ici une mémoire vérifiée d'une simple observation ou hypothèse."
                    } else {
                        "Cette mémoire existe dans le contexte, mais n'est pas marquée comme connaissance vérifiée."
                    },
                    tone
                )
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun MemoryHealthCard(health: MemoryHealthSnapshot?) {
    V2Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                V2SectionLabel("Memory Health")
                Spacer(Modifier.height(3.dp))
                Text(
                    health?.let { "${humanBytes(it.totalBytes)} sur le Pixel" }
                        ?: "Mesure du stockage…",
                    style = MaterialTheme.typography.titleMedium,
                    color = JadeColors.Ink
                )
            }
            health?.let {
                V2StatusBadge(memoryHealthLabel(it.status), memoryHealthTone(it.status))
            }
        }

        if (health != null) {
            Spacer(Modifier.height(12.dp))
            V2KeyValue("Base mémoire", humanBytes(health.coreDatabaseBytes))
            V2KeyValue("Conversation", humanBytes(health.conversationLearningBytes))
            V2KeyValue("Runtime Eval", humanBytes(health.runtimeEvalBytes))
            V2KeyValue("État distribué", humanBytes(health.sharedStateBytes))
            V2KeyValue("Autres états Jade", humanBytes(health.otherJadeStateBytes))
            V2KeyValue("Croissance 7 j", health.growth7dBytes?.let(::formatGrowth) ?: "collecte en cours")
            V2KeyValue("Croissance 30 j", health.growth30dBytes?.let(::formatGrowth) ?: "collecte en cours")
            Spacer(Modifier.height(10.dp))
            V2InlineMessage(
                "Surveillance uniquement : la taille ne déclenche jamais d'effacement automatique.",
                memoryHealthTone(health.status)
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Jade additionne la base Room, son journal SQLite et ses états persistants sans lire leur contenu.",
                style = MaterialTheme.typography.bodySmall,
                color = JadeColors.Muted
            )
        }
    }
}

private fun memoryHealthTone(status: MemoryHealthStatus): Color = when (status) {
    MemoryHealthStatus.NORMAL -> JadeColors.Jade
    MemoryHealthStatus.WATCH -> JadeColors.Warn
    MemoryHealthStatus.HIGH -> JadeColors.Error
}

private fun memoryHealthLabel(status: MemoryHealthStatus): String = when (status) {
    MemoryHealthStatus.NORMAL -> "NORMAL"
    MemoryHealthStatus.WATCH -> "À SURVEILLER"
    MemoryHealthStatus.HIGH -> "IMPORTANT"
}

private fun formatGrowth(bytes: Long): String = when {
    bytes > 0L -> "+${humanBytes(bytes)}"
    bytes < 0L -> "−${humanBytes(-bytes)}"
    else -> "stable"
}

private fun humanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        safe >= gib -> String.format(Locale.FRANCE, "%.2f Go", safe / gib)
        safe >= mib -> String.format(Locale.FRANCE, "%.1f Mo", safe / mib)
        safe >= kib -> String.format(Locale.FRANCE, "%.1f Ko", safe / kib)
        else -> "${safe.toLong()} o"
    }
}

@Composable
private fun MemoryFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val tone = if (text == "TOUT") JadeColors.Jade else memoryTone(text)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) tone.copy(alpha = 0.16f) else JadeColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            fontFamily = JadeMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) tone else JadeColors.Muted2
        )
    }
}

@Composable
private fun MemoryCardV2(memory: MemorySnapshot, onClick: () -> Unit) {
    val tone = memoryTone(memory.type)
    V2Card(
        modifier = Modifier.clickable(onClick = onClick),
        borderColor = tone.copy(alpha = 0.18f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            V2StatusBadge(memory.type, tone)
            Spacer(Modifier.width(7.dp))
            when {
                memory.supersededBy != null -> V2StatusBadge("SUPERSÉDÉE", JadeColors.Warn)
                memory.verifiedAt != null -> V2StatusBadge("VÉRIFIÉE", JadeColors.Jade)
                else -> V2StatusBadge("À CONFIRMER", JadeColors.Muted2)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            memory.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (memory.supersededBy == null) JadeColors.Ink else JadeColors.Muted
        )
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(memory.confidence.coerceIn(0.0, 1.0) * 100).toInt()} %",
                fontFamily = JadeMono,
                fontSize = 11.sp,
                color = tone
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(JadeColors.TrackBg)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(memory.confidence.toFloat().coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(tone)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "↻ ${memory.recallCount}",
                fontFamily = JadeMono,
                fontSize = 11.sp,
                color = JadeColors.Muted3
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${memory.source} · ${relativeTime(memory.createdAt)}",
            fontFamily = JadeMono,
            fontSize = 11.sp,
            color = JadeColors.Muted3
        )
    }
}
