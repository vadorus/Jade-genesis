package com.jadegenesis.mobile.selfmodel

import com.jadegenesis.mobile.model.Capability
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.SelfModel

class SelfModelBuilder {

    fun build(
        identity: JadeIdentity,
        nodeId: String,
        device: DeviceProfile,
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
            Capability("microphone", false, "not_requested_yet"),
            Capability("camera", false, "not_requested_yet"),
            Capability("local_ai", false, "brain_not_connected"),
            Capability("pc_node", false, "NodeManager_not_built"),
            Capability("vps_node", false, "NodeManager_not_built")
        )

        return SelfModel(
            identity = identity,
            nodeId = nodeId,
            device = device,
            capabilities = capabilities,
            knownLimits = listOf(
                "Aucun vrai modèle IA n'est encore connecté.",
                "Le Pixel fonctionne actuellement comme nœud autonome.",
                "PC et VPS ne sont pas encore reliés.",
                "Caméra et micro seront ajoutés dans une étape ultérieure."
            )
        )
    }
}
