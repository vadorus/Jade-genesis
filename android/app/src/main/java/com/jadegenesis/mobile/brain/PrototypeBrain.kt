package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResourceClass
import com.jadegenesis.mobile.model.BrainResult

class PrototypeBrain : BrainBackend {

    override val info = BrainInfo(
        id = "prototype-brain-0.0.2",
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
                    append(
                        if (r.preferRemoteCompute) {
                            "Pour les calculs lourds, je dois préférer un autre nœud quand il sera disponible. "
                        } else {
                            "Les ressources locales sont actuellement suffisantes pour mon niveau de charge. "
                        }
                    )
                    append("Raison principale : ${r.reasons.firstOrNull() ?: "budget normal"}.")
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
                    append("Je fonctionne sur le nœud ${self.nodeId}, ")
                    append("${self.device.manufacturer} ${self.device.model}. ")
                    append(
                        "Mon cerveau actif est ${self.activeBrain.displayName} " +
                            "et mon Resource Governor est en mode ${self.resourceBudget.mode}."
                    )
                }
            )
        }

        return BrainResult(
            text = "Mon cerveau 0.0.2 est encore un backend local minimal. " +
                "J'ai maintenant un Resource Governor et un BrainRouter : " +
                "je peux évaluer mes ressources et l'architecture peut choisir " +
                "un autre cerveau plus tard sans changer mon identité ni ma mémoire."
        )
    }
}
