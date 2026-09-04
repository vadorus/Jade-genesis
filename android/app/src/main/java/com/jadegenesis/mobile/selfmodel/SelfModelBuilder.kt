package com.jadegenesis.mobile.selfmodel

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.Capability
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.SelfModel

class SelfModelBuilder {

    fun build(
        identity: JadeIdentity,
        nodeId: String,
        device: DeviceProfile,
        resourceBudget: ResourceBudget,
        activeBrain: BrainInfo,
        toolNames: List<String>
    ): SelfModel {
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
                "generative_ai",
                activeBrain.backendType != BrainBackendType.PROTOTYPE,
                activeBrain.displayName
            ),
            Capability("microphone", false, "not_requested_yet"),
            Capability("camera", false, "not_requested_yet"),
            Capability("pc_node", false, "NodeManager_not_built"),
            Capability("vps_node", false, "NodeManager_not_built")
        )

        return SelfModel(
            identity = identity,
            nodeId = nodeId,
            device = device,
            resourceBudget = resourceBudget,
            activeBrain = activeBrain,
            capabilities = capabilities,
            knownLimits = listOf(
                "Aucun modèle génératif n'est encore connecté.",
                "Le Resource Governor calcule déjà un budget dynamique, mais le Task Router complet n'est pas encore construit.",
                "Le budget mémoire est une recommandation de fonctionnement sûr, pas une limite physique absolue d'Android.",
                "Le Pixel fonctionne encore comme nœud autonome.",
                "PC et VPS ne sont pas encore reliés.",
                "Caméra et micro seront ajoutés dans une étape ultérieure."
            )
        )
    }
}
