package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainResult

class PrototypeBrain : BrainBackend {

    override suspend fun think(context: BrainContext): BrainResult {
        val text = context.userInput.lowercase().trim()

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
                    append("Je fonctionne actuellement sur le nœud ${self.nodeId}, ")
                    append("${self.device.manufacturer} ${self.device.model}.")
                }
            )
        }

        return BrainResult(
            text = "Mon cerveau 0.0.1 est encore minimal. " +
                "Je peux déjà connaître mon identité, mon Self Model, ma mémoire " +
                "et inspecter réellement le téléphone. Le prochain BrainBackend sera un vrai modèle IA."
        )
    }
}
