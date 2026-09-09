package com.jadegenesis.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val V2CardShape = RoundedCornerShape(18.dp)
internal val V2SmallShape = RoundedCornerShape(14.dp)
internal val V2ButtonShape = RoundedCornerShape(14.dp)
internal val V2PillShape = RoundedCornerShape(999.dp)

@Composable
internal fun MobilePageHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                eyebrow.uppercase(Locale.FRANCE),
                fontFamily = JadeMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = JadeColors.JadeDim,
                letterSpacing = 0.9.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = JadeColors.Ink)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = JadeColors.Muted)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun V2Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    borderColor: Color = JadeColors.Border,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = V2CardShape,
        colors = CardDefaults.cardColors(containerColor = JadeColors.Surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(Modifier.padding(16.dp)) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = JadeColors.Ink,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
internal fun V2SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.FRANCE),
        fontFamily = JadeMono,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = JadeColors.JadeDim,
        letterSpacing = 0.8.sp
    )
}

@Composable
internal fun V2StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontFamily = JadeMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
internal fun V2Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, fontFamily = JadeMono, color = JadeColors.Muted3)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 13.sp, fontFamily = JadeMono, color = JadeColors.Ink)
    }
}

@Composable
internal fun V2KeyValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = JadeColors.Muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = JadeMono,
            color = JadeColors.Ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.25f)
        )
    }
}

@Composable
internal fun V2Divider() {
    HorizontalDivider(color = JadeColors.Divider)
}

@Composable
internal fun V2PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = V2ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = JadeColors.Jade,
            contentColor = JadeColors.JadeInk,
            disabledContainerColor = JadeColors.Jade.copy(alpha = 0.18f),
            disabledContentColor = JadeColors.Muted3
        )
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun V2SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = V2ButtonShape,
        border = BorderStroke(1.dp, JadeColors.Jade.copy(alpha = 0.20f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = JadeColors.JadeSoft,
            disabledContentColor = JadeColors.Muted3
        )
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun V2InlineMessage(text: String, tone: Color = JadeColors.Jade) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(V2SmallShape)
            .background(tone.copy(alpha = 0.07f))
            .border(1.dp, tone.copy(alpha = 0.16f), V2SmallShape)
            .padding(13.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = JadeColors.InkMuted)
    }
}

@Composable
internal fun V2Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(V2PillShape).background(color))
}

internal fun nodeTone(node: GenesisNode): Color = when (node.status) {
    NodeStatus.LOCAL, NodeStatus.ONLINE -> JadeColors.Jade
    NodeStatus.OFFLINE, NodeStatus.UNKNOWN -> JadeColors.Warn
    NodeStatus.ERROR -> JadeColors.Error
}

internal fun nodeRole(node: GenesisNode): String = when (node.kind) {
    NodeKind.PHONE -> "Identité, interface, capteurs et routage mobile."
    NodeKind.PC -> "Calcul lourd, cerveau local et vision quand le PC est disponible."
    NodeKind.VPS -> "État durable, orchestration 24/7 et Night Learning."
    NodeKind.UNKNOWN -> "Nœud enregistré dans le Device Registry."
}

internal fun memoryTone(type: String): Color = when (type.uppercase(Locale.FRANCE)) {
    "FACT" -> JadeColors.Jade
    "OBSERVATION" -> JadeColors.Info
    "PROCEDURE" -> JadeColors.Violet
    "HYPOTHESIS" -> JadeColors.Warn
    "FAILURE" -> JadeColors.Error
    "EXPERIENCE" -> JadeColors.Teal
    "KNOWLEDGE" -> JadeColors.Knowledge
    else -> JadeColors.Muted2
}

internal fun diagnosticTone(level: DiagnosticLevel): Color = when (level) {
    DiagnosticLevel.INFO -> JadeColors.JadeDim
    DiagnosticLevel.WARN -> JadeColors.Warn
    DiagnosticLevel.ERROR -> JadeColors.Error
    DiagnosticLevel.DEBUG -> JadeColors.Violet
}

internal fun shortTime(timestamp: Long): String =
    if (timestamp <= 0L) "--:--" else SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(timestamp))

internal fun fullDate(timestamp: Long): String =
    if (timestamp <= 0L) "inconnue" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(timestamp))

internal fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "inconnu"
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0 -> "il y a ${days}j"
        hours > 0 -> "il y a ${hours}h"
        minutes > 0 -> "il y a ${minutes}m"
        else -> "à l'instant"
    }
}

internal fun fmt(value: Double): String = String.format(Locale.FRANCE, "%.1f", value)
