package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResourceClass
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus

class PrototypeBrain : BrainBackend {

    override val info = BrainInfo(
        id = "prototype-brain-0.0.3",
        displayName = "Prototype Brain",
        backendType = BrainBackendType.PROTOTYPE,
        location = "phone",
        resourceClass = BrainResourceClass.MINIMAL,
        requiresNetwork = false,
        paidApi = false,
        available = true,
        priority = 1,
        details = "Cerveau local à règles, utilisé comme secours et pour valider l'architecture."
    )

    override suspend fun think(context: BrainContext): BrainResult {
        val text = context.userInput.lowercase().trim()

        if (
            "noeud" in text ||
            "nœud" in text ||
            "node manager" in text ||
            "mon pc" in text ||
            "ordinateur" in text ||
            "vps" in text
        ) {
            val self = context.selfModel
            val preferred = self.knownNodes.firstOrNull {
                it.nodeId == self.preferredComputeNodeId
            }
            val remotes = self.knownNodes.filter {
                it.kind != NodeKind.PHONE
            }
            val online = remotes.filter {
                it.status == NodeStatus.ONLINE
            }

            return BrainResult(
                text = buildString {
                    append(
                        "Mon Node Manager connaît ${self.knownNodes.size} nœud(s), " +
                            "dont ${remotes.size} distant(s). "
                    )

                    if (online.isEmpty()) {
                        append(
                            "Aucun nœud distant n'est actuellement en ligne. "
                        )
                    } else {
                        append(
                            "En ligne : " +
                                online.joinToString {
                                    "${it.name} (${it.ramAvailableGb} Go RAM libres)"
                                } +
                                ". "
                        )
                    }

                    preferred?.let {
                        append(
                            "Pour l'état actuel de mes ressources, mon nœud de " +
                                "calcul préféré est ${it.name}. "
                        )
                    }

                    append(
                        "En 0.0.3 je peux enregistrer et tester un PC/VPS, " +
                            "mais le Task Router n'envoie pas encore de tâche réelle vers lui."
                    )
                }
            )
        }

        if (
            "ressource" in text ||
            "limite" in text ||
            "ram" in text ||
            "mémoire vive" in text ||
            "memoire vive" in text ||
            "batterie" in text ||
            "thermique" in text ||
            "chauffe" in text ||
            "performance" in text ||
            "consommation" in text
        ) {
            val self = context.selfModel
            val d = self.device
            val r = self.resourceBudget
            val preferred = self.knownNodes.firstOrNull {
                it.nodeId == self.preferredComputeNodeId
            }
            val preferredRemote = preferred?.takeIf {
                it.kind != NodeKind.PHONE &&
                    it.status == NodeStatus.ONLINE
            }

            return BrainResult(
                text = buildString {
                    append("Mon Resource Governor est en mode ${r.mode}. ")
                    append(
                        "Je recommande un budget de travail d'environ " +
                            "${r.recommendedWorkingSetMb} Mo, "
                    )
                    append(
                        "avec ${r.maxParallelTasks} tâche(s) parallèle(s) maximum. "
                    )
                    append(
                        "Je garde une réserve système cible d'environ " +
                            "${r.systemRamReserveGb} Go. "
                    )
                    append(
                        "Ma mémoire de processus utilise actuellement " +
                            "${d.processHeapUsedMb} Mo sur ${d.processHeapMaxMb} Mo. "
                    )

                    if (
                        r.preferRemoteCompute &&
                        preferredRemote != null
                    ) {
                        append(
                            "Pour les calculs lourds, mon Node Manager préfère " +
                                "${preferredRemote.name}. "
                        )
                    } else if (r.preferRemoteCompute) {
                        append(
                            "Pour les calculs lourds, je dois préférer un autre nœud " +
                                "dès qu'il sera disponible. "
                        )
                    } else {
                        append(
                            "Les ressources locales sont actuellement suffisantes " +
                                "pour mon niveau de charge. "
                        )
                    }

                    append(
                        "Raison principale : ${r.reasons.firstOrNull() ?: "budget normal"}."
                    )
                }
            )
        }

        if (
            "matériel" in text ||
            "materiel" in text ||
            "téléphone" in text ||
            "telephone" in text ||
            "appareil" in text ||
            "inspect" in text
        ) {
            return BrainResult(
                text = "Je vais inspecter mon appareil pour répondre avec mes données réelles.",
                toolName = "inspect_device"
            )
        }

        if (
            "qui es-tu" in text ||
            "qui es tu" in text ||
            "où es-tu" in text ||
            "ou es tu" in text ||
            "où fonctionnes" in text ||
            "ou fonctionnes" in text
        ) {
            val self = context.selfModel
            return BrainResult(
                text = buildString {
                    append(
                        "Je suis ${self.identity.name} ${self.identity.version}. "
                    )
                    append(
                        "Mon identité est ${self.identity.jadeId.take(16)}… "
                    )
                    append(
                        "Je fonctionne sur le nœud ${self.nodeId}, "
                    )
                    append(
                        "${self.device.manufacturer} ${self.device.model}. "
                    )
                    append(
                        "Mon cerveau actif est ${self.activeBrain.displayName}, " +
                            "mon Resource Governor est en mode ${self.resourceBudget.mode} " +
                            "et mon Node Manager connaît ${self.knownNodes.size} nœud(s)."
                    )
                }
            )
        }

        return BrainResult(
            text = "Mon cerveau 0.0.3 est encore un backend local minimal. " +
                "J'ai maintenant un Resource Governor, un BrainRouter et un NodeManager " +
                "capable d'enregistrer et tester des nœuds PC/VPS sans changer mon identité ni ma mémoire."
        )
    }
}
