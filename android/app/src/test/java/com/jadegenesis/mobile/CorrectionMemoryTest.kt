package com.jadegenesis.mobile

import com.jadegenesis.mobile.memory.*
import com.jadegenesis.mobile.model.*
import com.jadegenesis.mobile.brain.PrototypeBrain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CorrectionMemoryTest {
    private class Store : CorrectionDao {
        val facts = linkedMapOf<String, MemoryEntity>()
        val corrections = linkedMapOf<String, UserCorrection>()
        override suspend fun insertCorrection(row: UserCorrection) { check(row.id !in corrections); corrections[row.id] = row }
        override suspend fun insertFact(row: MemoryEntity) { check(row.id !in facts); facts[row.id] = row }
        override suspend fun rows() = corrections.values.toList()
        override suspend fun activeFacts() = facts.values.filter { it.supersededBy == null && it.type == "FACT" }
        override suspend fun supersede(ids: List<String>, replacement: String): Int {
            val current = ids.filter { facts[it]?.supersededBy == null && it in facts }
            current.forEach { facts[it] = facts.getValue(it).copy(supersededBy = replacement) }
            return current.size
        }
        override suspend fun activate(id: String): Int {
            val row = corrections[id]?.takeIf { it.state == "PENDING" } ?: return 0
            corrections[id] = row.copy(state = "ACTIVE", targets = "|"); return 1
        }
        override suspend fun deleteFact(id: String) { facts.remove(id) }
        override suspend fun deleteLinks(id: String) {
            corrections.entries.removeAll { it.key == id || (it.value.state == "PENDING" && "|$id|" in it.value.targets) }
        }
    }

    @Test fun captureOnlyExplicitPrefix() {
        assertEquals("corrige" to "Mars 42", CorrectionPolicy.command("corrige : Mars 42"))
        assertEquals("oublie" to "Mars 42", CorrectionPolicy.command("OUBLIE: Mars 42"))
        assertNull(CorrectionPolicy.command("non, Mars 42"))
        assertNull(CorrectionPolicy.command("je corrige : Mars 42"))
    }

    @Test fun privateLinkAndFactSeparated() = runBlocking {
        val dao = Store()
        val row = dao.propose("question privée", "ref", "réponse fausse", "Mars 42", "synthetic")
        val fact = dao.facts.getValue(row.id)
        assertEquals("Mars 42", fact.content)
        assertEquals("FACT", fact.type)
        assertEquals("USER_CORRECTION", fact.source)
        assertEquals(1.0, fact.confidence, 0.0)
        assertEquals("réponse fausse", dao.corrections.getValue(row.id).wrongAnswer)
    }

    @Test fun conflictNeverReplacesBeforeExactConfirmation() = runBlocking {
        val dao = Store()
        val old = dao.propose("Combien de lunes ?", "ref1", "0", "Mars 2", "synthetic")
        val next = dao.propose("Combien de lunes ?", "ref2", "3", "Mars 4", "synthetic")
        assertEquals("PENDING", next.state)
        assertNull(dao.facts[old.id]?.supersededBy)
        assertNull(dao.facts[next.id])
        assertTrue(CorrectionStore(dao).relevant("Combien de lunes ?").isEmpty())
        assertTrue(dao.confirm(next.id, "synthetic"))
        assertEquals(next.id, dao.facts[old.id]?.supersededBy)
        assertFalse(dao.confirm(next.id, "synthetic"))
        assertEquals(listOf("Mars 4"), CorrectionStore(dao).relevant("Combien de lunes ?").map { it.content })
    }

    @Test fun staleConfirmationRejectedAndForgetNeverResurrects() = runBlocking {
        val dao = Store()
        val old = dao.propose("Combien de lunes ?", "r1", "0", "Mars 2", "synthetic")
        val next = dao.propose("Combien de lunes ?", "r2", "0", "Mars 4", "synthetic")
        dao.facts[old.id] = dao.facts.getValue(old.id).copy(supersededBy = "another")
        assertFalse(dao.confirm(next.id, "synthetic"))
        dao.forget(next.id)
        assertEquals("another", dao.facts[old.id]?.supersededBy)
        assertTrue(CorrectionStore(dao).visible().isEmpty())
    }

    @Test fun oldRelevantCorrectionAndOwnQuotaFive() = runBlocking {
        val dao = Store()
        val old = dao.propose("Quel satellite ancien ?", "ref", "faux", "Europe 42", "synthetic")
        dao.corrections[old.id] = old.copy(createdAt = 1)
        repeat(80) { i -> dao.propose("Sujet distinct $i", "ref$i", "faux", "Valeur$i", "synthetic") }
        assertEquals(old.id, CorrectionStore(dao).relevant("Quel satellite ancien ?").single().id)
        repeat(8) { i -> dao.propose("Voie $i", "voie$i", "faux", "Comète$i", "synthetic") }
        // Shared lexical query, unrelated recent corrections cannot evict the matching set.
        assertEquals(5, CorrectionStore(dao).relevant("Sujet distinct").size)
        dao.forget(old.id)
        assertTrue(CorrectionStore(dao).relevant("Quel satellite ancien ?").isEmpty())
    }

    @Test fun sameDedicatedFactsReachAnswerVerifyReviseAndPixelFallback() = runBlocking {
        val dao = Store()
        dao.propose("Quel satellite ancien ?", "PRIVATE_REF", "PRIVATE_WRONG_ANSWER", "Europe 42", "synthetic")
        val facts = CorrectionStore(dao).relevant("Quel satellite ancien ?")
        val device = DeviceProfile("synthetic", "test", "test", "test", 1, "test", "test", emptyList(),
            1, 1.0, 1.0, false, 1, 1.0, 1.0, 1.0, 1.0, 100, true, false, false, "test", 0)
        val brain = PrototypeBrain()
        val self = SelfModel(JadeIdentity("synthetic", version = "test", createdAt = 0), "synthetic", device,
            ResourceBudget(ResourceMode.ECO, emptyList(), 0.0, 1, 1, false, false, 1, 0),
            brain.info, emptyList(), null, emptyList(), emptyList())
        val context = BrainContext("Quel satellite ancien ?", self, emptyList(), emptyList(), corrections = facts)
        for (operation in listOf("answer", "verify", "revise")) {
            val copy = context.copy(operation = operation)
            val payload = CorrectionContextPayload.encode(copy.corrections).toString()
            assertTrue(payload.contains("Europe 42"))
            assertFalse(payload.contains("PRIVATE_WRONG_ANSWER"))
            assertFalse(payload.contains("PRIVATE_REF"))
            assertFalse(payload.contains("satellite"))
            assertTrue(brain.think(copy).text.contains("Europe 42"))
        }
    }

}
