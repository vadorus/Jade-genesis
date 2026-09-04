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
        id = "prototype-brain-0.0.5",
        displayName = "Prototype Brain",
        backendType = BrainBackendType.PROTOTYPE,
        location = "distributed-core",
        resourceClass = BrainResourceClass.MINIMAL,
        requiresNetwork = false,
        paidApi = false,
        available = true,
        priority = 1,
        details = "Cerveau local à règles, utilisé comme secours pendant la construction du runtime distribué adaptatif."
    )

    override suspend fun think(context: BrainContext): BrainResult {
        val text = context.userInput.lowercase().trim()

        if (
            "amélior" in text ||
            "amelior" in text ||
            "apprend" in text ||
            "apprentissage" in text ||
            "évolu" in text ||
            "evolu" in text ||
            "adapt" in text
        ) {
            val self = context.selfModel
            val compatible = self.knownNodes.count {
                it.status == NodeStatus.ONLINE &&
                    "task_execution_v2" in it.capabilities
            }

            return BrainResult(
                text = buildString {
                    append(
                        "En 0.0.5 mon amélioration mesurable commence par le routage. " +
                            "Je conserve pour chaque tâche le nœud tenté, le succès ou l'échec, " +
                            "la durée et les fallbacks. Mon Task Router réutilise cet historique " +
                            "avec l'état actuel des ressources pour classer les nœuds lors des tâches suivantes. "
                    )
                    append(
                        "J'ai actuellement $compatible nœud(s) distant(s) compatible(s) avec le protocole de tâches v2. "
                    )
                    append(
                        "Je ne modifie pas encore seule mon code ni les poids d'un modèle : cette étape viendra " +
                            "seulement avec des mécanismes de proposition, test, validation et retour arrière."
                    )
                }
            )
        }

        if (
            "tâche" in text ||
            "tache" in text ||
            "task router" in text ||
            "distribu" in text ||
            "exécution" in text ||
            "execution" in text
        ) {
            val self = context.selfModel
            val preferred = self.knownNodes.firstOrNull {
                it.nodeId == self.preferredComputeNodeId
            }
            val compatible = self.knownNodes.filter {
                it.kind != NodeKind.PHONE &&
                    it.status == NodeStatus.ONLINE &&
                    "task_execution_v2" in it.capabilities
            }

            return BrainResult(
                text = buildString {
                    append(
                        "Mon Adaptive Task Router 0.0.5 classe les nœuds à partir de mes ressources actuelles, " +
                            "de leurs capacités et de l'historique mesuré de mes exécutions. "
                    )
                    preferred?.let {
                        append("Le nœud de calcul général actuellement préféré est ${it.name}. ")
                    }
                    if (compatible.isNotEmpty()) {
                        append(
                            "J'ai ${compatible.size} nœud(s) distant(s) compatible(s) avec task_execution_v2. "
                        )
                    } else {
                        append(
                            "Aucun nœud distant v2 n'est actuellement prêt, donc j'utilise mon nœud local. "
                        )
                    }
                    append(
                        "Les tâches autorisées dans cette version sont genesis_probe et text_analysis. " +
                            "Si le premier nœud choisi échoue, j'essaie les nœuds compatibles suivants. " +
                            "Aucune commande système arbitraire n'est exposée."
                    )
                }
            )
        }

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
                        append("Aucun nœud distant n'est actuellement en ligne. ")
                    } else {
                        append(
                            "En ligne : " +
                                online.joinToString {
                                    "${it.name} (${it.ramAvailableGb} Go RAM libres, ${it.protocol.ifBlank { "protocole inconnu" }})"
                                } +
                                ". "
                        )
                    }

                    preferred?.let {
                        append(
                            "Pour l'état actuel de mes ressources, mon nœud de calcul général préféré est ${it.name}. "
                        )
                    }

                    append(
                        "Pour une tâche précise, la 0.0.5 recalcule ensuite le meilleur nœud selon la capacité demandée, " +
                            "la charge de travail et ses mesures passées."
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
                        "Je recommande un budget de travail d'environ ${r.recommendedWorkingSetMb} Mo, "
                    )
                    append(
                        "avec ${r.maxParallelTasks} tâche(s) parallèle(s) maximum. "
                    )
                    append(
                        "Je garde une réserve système cible d'environ ${r.systemRamReserveGb} Go. "
                    )
                    append(
                        "Ma mémoire de processus utilise actuellement ${d.processHeapUsedMb} Mo sur ${d.processHeapMaxMb} Mo. "
                    )

                    if (
                        r.preferRemoteCompute &&
                        preferredRemote != null
                    ) {
                        append(
                            "Pour les calculs lourds, mon Node Manager préfère ${preferredRemote.name}. "
                        )
                    } else if (r.preferRemoteCompute) {
                        append(
                            "Pour les calculs lourds, je dois préférer un autre nœud dès qu'il sera disponible. "
                        )
                    } else {
                        append(
                            "Les ressources locales sont actuellement suffisantes pour mon niveau de charge. "
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
                    append("Je suis ${self.identity.name} ${self.identity.version}. ")
                    append("Mon identité est ${self.identity.jadeId.take(16)}… ")
                    append("Mon nœud d'interface actuel est ${self.nodeId}, ")
                    append("${self.device.manufacturer} ${self.device.model}. ")
                    append(
                        "Je suis conçue comme une seule identité distribuée sur plusieurs nœuds. " +
                            "Mon cerveau actif est ${self.activeBrain.displayName}, mon Resource Governor est en mode " +
                            "${self.resourceBudget.mode} et mon Node Manager connaît ${self.knownNodes.size} nœud(s). " +
                            "Mon Task Router 0.0.5 peut adapter ses choix à partir de mesures réelles."
                    )
                }
            )
        }

        return BrainResult(
            text = "Mon cerveau 0.0.5 reste un backend local minimal. Mon runtime distribué sait cependant gérer plusieurs " +
                "types de tâches autorisées, mesurer leurs résultats, conserver cet historique et adapter le routage futur " +
                "sans changer mon identité ni ma mémoire."
        )
    }
}
