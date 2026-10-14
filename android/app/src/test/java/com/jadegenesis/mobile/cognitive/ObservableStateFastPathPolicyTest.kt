package com.jadegenesis.mobile.cognitive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableStateFastPathPolicyTest {
    @Test fun shortObservableQuestionUsesFastPath() {
        assertTrue(ObservableStateFastPathPolicy.eligible(
            "Explique-moi l'état actuel du réseau entre Pixel, home et VPS."
        ))
        assertTrue(ObservableStateFastPathPolicy.eligible("État réel de Jade"))
        assertTrue(ObservableStateFastPathPolicy.eligible("Statut batterie et RAM ?"))
    }

    @Test fun historyRootCauseAndAuditMustNotUseSnapshotAlone() {
        assertFalse(ObservableStateFastPathPolicy.eligible(
            "Pourquoi home a-t-il eu une erreur et à quelle date ?"
        ))
        assertFalse(ObservableStateFastPathPolicy.eligible(
            "Compare les réponses précédentes et vérifie le journal."
        ))
        assertFalse(ObservableStateFastPathPolicy.eligible(
            "Quelles améliorations as-tu apprises hier sur le VPS ?"
        ))
    }

    @Test fun unrelatedOrOversizedQueriesMustNotMatch() {
        assertFalse(ObservableStateFastPathPolicy.eligible("Comment fonctionne Tailscale ?"))
        assertFalse(ObservableStateFastPathPolicy.eligible(""))
        assertFalse(ObservableStateFastPathPolicy.eligible("État du réseau " + "x".repeat(300)))
    }
}
