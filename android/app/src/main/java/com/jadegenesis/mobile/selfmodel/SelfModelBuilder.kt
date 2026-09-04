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
                "task_execution_v1" in it.capabilities &&
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
                "NodeManager 0.0.4",
                "Registre les nœuds, mesure leur disponibilité et expose leurs capacités."
            ),
            Capability(
                "task_router",
                true,
                "TaskRouter 0.0.4",
                "Choisit un nœud selon les ressources et revient au local si l'exécution distante échoue."
            ),
            Capability(
                "distributed_execution",
                onlineTaskNode,
                if (onlineTaskNode) {
                    "TaskRouter_remote_ready"
                } else {
                    "TaskRouter_local_fallback_only"
                },
                "La 0.0.4 sait exécuter la tâche bornée genesis_probe sur un nœud compatible."
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
            "Le Task Router 0.0.4 n'autorise qu'une tâche de validation bornée genesis_probe ; aucune commande système arbitraire n'est exposée.",
            "Le routage choisit local ou distant selon le Resource Governor et l'état des nœuds, avec fallback local automatique.",
            "Le lien LAN de développement utilise HTTP local avec un jeton de Node Agent ; le chiffrement et l'appairage renforcé viendront plus tard.",
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
