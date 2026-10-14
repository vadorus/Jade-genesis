package com.jadegenesis.mobile.cognitive

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative allowlist for a cheap, device-local snapshot answer.
 * This does not infer causes or past states. Those requests stay in the
 * ordinary cognitive route. The caller must also confirm that its state
 * snapshot renderer recognizes the request.
 */
internal object ObservableStateFastPathPolicy {
    fun eligible(input: String): Boolean {
        if (input.length !in 5..220) return false
        val ascii = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val complex = listOf(
            "pourquoi", "cause", "erreur", "echec", "incident", "historique",
            "journal", "trace", "preuve", "verifi", "dernier", "precedent",
            "avant", "hier", "depuis", "date", "heure", "ameli", "appren",
            "compare", "difference", "diagnostic", "evolution", "mesure avant",
            "panne", "latence anormale", "plusieurs", "analyse"
        )
        if (complex.any { it in ascii }) return false
        val current = listOf(
            "etat", "statut", "actuel", "maintenant", "en ligne", "online",
            "disponible", "disponibilite", "combien", "ram", "batterie"
        ).any { it in ascii }
        val target = listOf(
            "reseau", "tailscale", "noeud", "mesh", "vps", "home",
            "pixel", "telephone", "appareil", "batterie", "ram", "runtime",
            "cerveau", "jade", "gpu", "cpu", "stockage"
        ).any { it in ascii }
        return current && target
    }
}
