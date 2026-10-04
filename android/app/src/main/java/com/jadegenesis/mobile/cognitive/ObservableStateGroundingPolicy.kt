package com.jadegenesis.mobile.cognitive

import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.NodeRouteSnapshot
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.SelfModel
import java.util.Locale

internal enum class ObservableStateDomain {
    NETWORK,
    DEVICE,
    RUNTIME,
    SYSTEM
}

internal data class ObservableStateGroundingDecision(
    val result: BrainResult,
    val applied: Boolean,
    val domain: ObservableStateDomain? = null
)

internal object ObservableStateGroundingPolicy {
    fun apply(
        input: String,
        result: BrainResult,
        selfModel: SelfModel
    ): ObservableStateGroundingDecision {
        val domain = classify(input)
            ?: return ObservableStateGroundingDecision(result = result, applied = false)

        val groundedText = when (domain) {
            ObservableStateDomain.NETWORK -> renderNetwork(selfModel, result)
            ObservableStateDomain.DEVICE -> renderDevice(selfModel)
            ObservableStateDomain.RUNTIME -> renderRuntime(selfModel, result)
            ObservableStateDomain.SYSTEM -> renderSystem(selfModel, result)
        }

        return ObservableStateGroundingDecision(
            result = result.copy(text = groundedText),
            applied = true,
            domain = domain
        )
    }

    fun isObservableStateQuestion(input: String): Boolean = classify(input) != null

    private fun classify(input: String): ObservableStateDomain? {
        val normalized = ConversationLearningPolicy.normalize(input)
        val stateCue = listOf(
            "etat",
            "statut",
            "actuel",
            "actuellement",
            "maintenant",
            "en ligne",
            "online",
            "disponible",
            "disponibilite",
            "latence",
            "combien",
            "quel est",
            "quelle est"
        ).any { it in normalized }
        if (!stateCue) return null

        if (
            listOf(
                "reseau",
                "tailscale",
                "compute mesh",
                "mesh",
                "noeud",
                "vps",
                "route",
                "latence"
            ).any { it in normalized }
        ) {
            return ObservableStateDomain.NETWORK
        }

        if (
            listOf(
                "batterie",
                "ram",
                "stockage",
                "temperature",
                "thermique",
                "cpu",
                "gpu",
                "android",
                "appareil",
                "telephone",
                "pixel"
            ).any { it in normalized }
        ) {
            return ObservableStateDomain.DEVICE
        }

        if (
            listOf(
                "runtime",
                "cerveau",
                "backend",
                "modele",
                "ollama"
            ).any { it in normalized }
        ) {
            return ObservableStateDomain.RUNTIME
        }

        return if ("etat reel" in normalized || "etat de jade" in normalized) {
            ObservableStateDomain.SYSTEM
        } else {
            null
        }
    }

    private fun renderNetwork(selfModel: SelfModel, result: BrainResult): String = buildString {
        appendLine("Points importants :")
        if (selfModel.knownNodes.isEmpty()) {
            appendLine("- Aucun nœud runtime n'est présent dans le snapshot actuel.")
        } else {
            selfModel.knownNodes.forEach { node ->
                append("- ")
                append(node.name)
                append(" — ")
                append(node.status.name)
                val routes = node.routes
                if (routes.isNotEmpty()) {
                    append(" ; ")
                    append(routes.joinToString(" ; ") { routeSummary(it) })
                } else if (node.status != NodeStatus.LOCAL) {
                    append(" ; aucune route détaillée dans le snapshot")
                }
                appendLine(".")
            }
        }

        val selectedNode = selfModel.knownNodes.firstOrNull { it.nodeId == result.nodeId }
        if (selectedNode != null) {
            appendLine("- Cerveau utilisé pour cette réponse : ${selectedNode.name}.")
        }
        appendLine("- Débit réseau : non mesuré dans le snapshot runtime.")
        append("- Type d'accès Internet du Pixel : non mesuré dans ce snapshot.")
    }

    private fun routeSummary(route: NodeRouteSnapshot): String = buildString {
        append(route.kind.name)
        append(" ")
        append(route.status.name)
        val latency = route.latencyMs
        if (latency != null && latency >= 0L) {
            append(" ")
            append(latency)
            append(" ms")
        } else {
            append(" ; latence non mesurée")
        }
    }

    private fun renderDevice(selfModel: SelfModel): String {
        val device = selfModel.device
        return buildString {
            appendLine("Points importants :")
            appendLine(
                "- Appareil : ${device.manufacturer} ${device.model}, Android ${device.androidVersion}."
            )
            appendLine(
                if (device.batteryPercent >= 0) {
                    "- Batterie : ${device.batteryPercent} %${if (device.charging) ", en charge" else ""}."
                } else {
                    "- Batterie : non mesurée dans le snapshot."
                }
            )
            appendLine(
                "- RAM disponible : ${formatNumber(device.ramAvailableGb)} Go / ${formatNumber(device.ramTotalGb)} Go."
            )
            appendLine(
                "- Stockage libre : ${formatNumber(device.storageFreeGb)} Go / ${formatNumber(device.storageTotalGb)} Go."
            )
            appendLine("- Thermique : ${device.thermalStatus.ifBlank { "non mesuré" }}.")
            append("- Mode ressources Jade : ${selfModel.resourceBudget.mode.name}.")
        }
    }

    private fun renderRuntime(selfModel: SelfModel, result: BrainResult): String = buildString {
        appendLine("Points importants :")
        if (selfModel.knownNodes.isEmpty()) {
            appendLine("- Aucun nœud runtime n'est présent dans le snapshot actuel.")
        } else {
            selfModel.knownNodes.forEach { node ->
                append("- ${node.name} — ${node.status.name}")
                if (node.runtimeVersion.isNotBlank()) {
                    append(" ; runtime ${node.runtimeVersion}")
                } else {
                    append(" ; version runtime non mesurée")
                }
                if (node.brainBackend.isNotBlank() || node.brainModel.isNotBlank()) {
                    append(" ; cerveau ")
                    append(
                        listOf(node.brainBackend, node.brainModel)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    )
                    append(" ; prêt=${node.brainReady}")
                }
                appendLine(".")
            }
        }
        val selectedNode = selfModel.knownNodes.firstOrNull { it.nodeId == result.nodeId }
        if (selectedNode != null) {
            append("- Cerveau utilisé pour cette réponse : ${selectedNode.name}.")
        } else {
            append("- Nœud cerveau de cette réponse : non mesuré.")
        }
    }

    private fun renderSystem(selfModel: SelfModel, result: BrainResult): String =
        buildString {
            append(renderDevice(selfModel))
            appendLine()
            appendLine()
            append(renderNetwork(selfModel, result))
        }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite() || value < 0.0) return "non mesuré"
        return String.format(Locale.US, "%.2f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
}
