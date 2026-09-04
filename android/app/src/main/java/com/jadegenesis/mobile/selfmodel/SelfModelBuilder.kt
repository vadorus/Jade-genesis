package com.jadegenesis.mobile.selfmodel

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.Capability
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.SelfModel

class SelfModelBuilder {

    fun build(
        identity: JadeIdentity,
        nodeId: String,
        device: DeviceProfile,
        resourceBudget: ResourceBudget,
        activeBrain: BrainInfo,
        knownNodes: List<GenesisNode>,
        preferredComputeNodeId: String?,
        toolNames: List<String>
    ): SelfModel {
        val pcNodes = knownNodes.filter {
            it.kind == NodeKind.PC
        }
        val vpsNodes = knownNodes.filter {
            it.kind == NodeKind.VPS
        }
        val onlinePc = pcNodes.any {
            it.status == NodeStatus.ONLINE
        }
        val onlineVps = vpsNodes.any {
            it.status == NodeStatus.ONLINE
        }
        val onlineTaskNode = knownNodes.any {
            it.kind != NodeKind.PHONE &&
                it.status == NodeStatus.ONLINE &&
                "task_execution_v2" in it.capabilities
        }
        val onlineGenericTaskNode = knownNodes.any {
            it.kind != NodeKind.PHONE &&
                it.status == NodeStatus.ONLINE &&
                "task_execution_v2" in it.capabilities &&
                "text_analysis" in it.capabilities &&
                "genesis_probe" in it.capabilities
        }

        val capabilities = listOf(
            Capability("persistent_identity", true, "DataStore"),
            Capability("local_memory", true, "Room 3"),
            Capability(
                "device_inspection",
                "inspect_device" in toolNames,
                "ToolRegistry"
            ),
            Capability(
                "resource_governor",
                true,
                "ResourceGovernor",
                "Adapte le budget de travail à la RAM, batterie, température et état Android."
            ),
            Capability(
                "brain_backend_router",
                true,
                "BrainRouter",
                "Cerveaux interchangeables selon disponibilité et ressources."
            ),
            Capability(
                "node_manager",
                true,
                "NodeManager 0.0.5",
                "Registre les nœuds, mesure leur disponibilité, protocole et capacités."
            ),
            Capability(
                "task_router",
                true,
                "AdaptiveTaskRouter 0.0.5",
                "Classe les nœuds par ressources, charge et historique mesuré, puis essaie les alternatives si nécessaire."
            ),
            Capability(
                "task_ledger",
                true,
                "TaskLedger 0.0.5",
                "Conserve les résultats, durées, échecs et fallbacks pour améliorer le routage futur."
            ),
            Capability(
                "adaptive_routing",
                true,
                "MeasuredExecutionHistory",
                "Le routage utilise réellement les succès, échecs et durées précédemment mesurés."
            ),
            Capability(
                "distributed_execution",
                onlineTaskNode,
                if (onlineTaskNode) {
                    "TaskRouter_remote_ready_v2"
                } else {
                    "TaskRouter_local_fallback_only"
                },
                "La 0.0.5 possède un protocole générique de tâches autorisées et un fallback multi-nœuds."
            ),
            Capability(
                "generic_task_runtime",
                onlineGenericTaskNode,
                if (onlineGenericTaskNode) {
                    "NodeRuntime_0.0.5"
                } else {
                    "NodeRuntime_upgrade_required"
                },
                "Tâches actuellement autorisées : genesis_probe et text_analysis."
            ),
            Capability(
                "generative_ai",
                activeBrain.backendType != BrainBackendType.PROTOTYPE,
                activeBrain.displayName
            ),
            Capability("microphone", false, "not_requested_yet"),
            Capability("camera", false, "not_requested_yet"),
            Capability(
                "pc_node",
                onlinePc,
                if (onlinePc) {
                    "NodeManager_online"
                } else if (pcNodes.isNotEmpty()) {
                    "NodeManager_registered_offline"
                } else {
                    "NodeManager_no_pc_registered"
                }
            ),
            Capability(
                "vps_node",
                onlineVps,
                if (onlineVps) {
                    "NodeManager_online"
                } else if (vpsNodes.isNotEmpty()) {
                    "NodeManager_registered_offline"
                } else {
                    "NodeManager_no_vps_registered"
                }
            )
        )

        val limits = mutableListOf(
            "Aucun modèle génératif n'est encore connecté.",
            "Le runtime 0.0.5 reste sur une liste blanche : genesis_probe et text_analysis uniquement ; aucune commande système arbitraire n'est exposée.",
            "L'adaptation 0.0.5 concerne le routage mesurable des tâches ; Jade ne réécrit pas encore seule son code ni ses poids de modèle.",
            "Le lien LAN de développement utilise HTTP local avec un jeton de Node Runtime ; le chiffrement et l'appairage renforcé viendront plus tard.",
            "Le budget mémoire est une recommandation de fonctionnement sûr, pas une limite physique absolue d'Android.",
            "Caméra et micro seront ajoutés dans une étape ultérieure."
        )

        if (
            knownNodes.none {
                it.kind != NodeKind.PHONE &&
                    it.status == NodeStatus.ONLINE
            }
        ) {
            limits.add(
                "Aucun nœud distant n'est actuellement en ligne."
            )
        }

        return SelfModel(
            identity = identity,
            nodeId = nodeId,
            device = device,
            resourceBudget = resourceBudget,
            activeBrain = activeBrain,
            knownNodes = knownNodes,
            preferredComputeNodeId = preferredComputeNodeId,
            capabilities = capabilities,
            knownLimits = limits
        )
    }
}
