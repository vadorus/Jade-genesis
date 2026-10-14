package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus

/** Missing optional GPU temperatures never decide whether a chat backend exists. */
internal object DistributedChatAvailability {
    fun reason(nodes: List<GenesisNode>, validationOffline: Boolean = false): String? {
        if (validationOffline) return "Jade TEST fonctionne volontairement hors réseau. Son chat distribué ne peut pas joindre un modèle de calcul."
        val remote = nodes.filter { it.kind != NodeKind.PHONE }
        if (remote.isEmpty()) return "Aucun nœud de calcul n'est configuré. Ajoute ou reconnecte le PC dans Nœuds."
        val online = remote.filter { it.status == NodeStatus.ONLINE }
        if (online.isEmpty()) return "Les nœuds de calcul sont hors ligne ou ne répondent plus. Vérifie le service Jade du PC puis actualise les nœuds."
        val compatible = online.filter { listOf("task_execution_v3", "local_brain", "brain_chat").all { capability -> capability in it.capabilities } }
        if (compatible.isEmpty()) return "Les nœuds en ligne ne proposent pas le chat génératif de Jade. Vérifie leur runtime."
        if (compatible.none { it.brainReady }) return "Le modèle de conversation n'est pas prêt sur les nœuds de calcul. Vérifie le moteur local du PC."
        return null
    }
}
