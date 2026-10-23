package com.jadegenesis.mobile

import com.jadegenesis.mobile.memory.*
import com.jadegenesis.mobile.model.*
import com.jadegenesis.mobile.brain.PrototypeBrain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CorrectionMemoryTest {
    private class Store : CorrectionDao {
        val facts = linkedMapOf<String, MemoryEntity>()
        val corrections = linkedMapOf<String, UserCorrection>()
        val nights = linkedMapOf<String, CorrectionExamNight>()
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
        override suspend fun markExam(id: String, at: Long) { corrections[id]?.let { corrections[id] = it.copy(lastExamAt = at) } }
        override suspend fun examNight(night: String) = nights[night]
        override suspend fun lastExamNight() = nights.values.maxByOrNull { it.night }
        override suspend fun saveNight(row: CorrectionExamNight) { nights[row.night] = row }
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
        repeat(80) { i -> dao.propose("Dossier orbital $i", "ref$i", "faux", "Valeur$i", "synthetic") }
        assertEquals(old.id, CorrectionStore(dao).relevant("Quel satellite ancien ?").single().id)
        repeat(8) { i -> dao.propose("Voie $i", "voie$i", "faux", "Comète$i", "synthetic") }
        // Shared lexical query, unrelated recent corrections cannot evict the matching set.
        assertEquals(5, CorrectionStore(dao).relevant("Dossier orbital").size)
        dao.forget(old.id)
        assertTrue(CorrectionStore(dao).relevant("Quel satellite ancien ?").isEmpty())
    }

    @Test fun oneSharedWordDoesNotRecallCorrectionButTwoDo() = runBlocking {
        val dao = Store()
        val row = dao.propose("Quel processeur équipe Orion ?", "ref", "faux", "Orion utilise Vega", "synthetic")
        val store = CorrectionStore(dao)
        assertTrue(store.relevant("Comparer ce processeur mobile").isEmpty())
        assertTrue(store.relevant("processeur processeur mobile").isEmpty())
        assertEquals(listOf(row.id), store.relevant("Comparer le processeur Orion").map { it.id })
    }

    @Test fun identicalOriginalQuestionRecallsEvenWithoutLexicalTerms() = runBlocking {
        val dao = Store()
        val row = dao.propose("Où ?", "ref", "faux", "Paris", "synthetic")
        assertEquals(listOf(row.id), CorrectionStore(dao).relevant("  OÙ ? ").map { it.id })
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
        // Every normal branch, including tool selection, survives the appended reminder.
        for (input in listOf("qui es-tu", "consolider", "file de tâches", "apprentissage",
            "nœuds", "ressources", "inspecte mon appareil", "Bonjour")) {
            val corrected = context.copy(userInput = input)
            val normal = brain.think(corrected.copy(corrections = emptyList()))
            val actual = brain.think(corrected)
            assertEquals(normal.copy(text = normal.text +
                "\n\nRappel — correction(s) d'Alexandre :\n• Europe 42"), actual)
        }
    }

    @Test fun exactLexicalIndicatorBoundariesAndUnknown() {
        assertEquals(false, CorrectionPolicy.passes("Mars 12", "Mars 112"))
        assertEquals(true, CorrectionPolicy.passes("Mars 12", "Mars : 12."))
        assertEquals(false, CorrectionPolicy.passes("Mars 12", "Mars 12.5"))
        assertEquals(false, CorrectionPolicy.passes("Mars 12", "Mars -12"))
        assertEquals(false, CorrectionPolicy.passes("Mars 12", "Mars 0.12"))
        assertEquals(false, CorrectionPolicy.passes("Europe 18/10/2026", "Europe 18/10/2025"))
        assertNull(CorrectionPolicy.passes("tout est possible", "oui"))
        // Deliberately documents the limit: lexical presence is not semantic truth.
        assertEquals(true, CorrectionPolicy.passes("Mars 12", "Mars n’a pas 12"))
    }

    private val epoch = Instant.parse("2026-10-18T01:29:00Z").toEpochMilli()
    private suspend fun populate(dao: Store, count: Int) {
        repeat(count) { i -> dao.propose("Question$i", "ref$i", "faux", "Nombre$i 42", "synthetic") }
    }

    @Test fun emptyNightDoesNotCheckHaltOrCallModel() = runBlocking {
        val dao = Store()
        CorrectionExamEngine(dao, { error("must not check") }, { false }, { _, _ -> error("must not call") }, { epoch }).run()
        assertTrue(dao.nights.isEmpty())
    }

    @Test fun tenMaximumDespiteRetryAndNoSecondReplaySameNight() = runBlocking {
        val dao = Store(); populate(dao, 15)
        var calls = 0
        fun engine() = CorrectionExamEngine(dao, { false }, { false }, { row, budget ->
            assertTrue(budget in 100..86000); calls++; row.fact
        }, { epoch })
        engine().run(); engine().run()
        assertEquals(10, calls)
        assertEquals(10, dao.examNight("2026-10-18")?.attempts)
        assertEquals(900000L, dao.examNight("2026-10-18")?.usedMs)
    }

    @Test fun haltBeforeOrAfterCallPreventsNextAndDoesNotRecordPass() = runBlocking {
        val dao = Store(); populate(dao, 3)
        var calls = 0; var halted = true
        fun engine() = CorrectionExamEngine(dao, { halted }, { false }, { row, _ -> calls++; halted = true; row.fact }, { epoch })
        engine().run(); assertEquals(0, calls)
        halted = false; engine().run(); assertEquals(1, calls)
        assertEquals(0, dao.examNight("2026-10-18")?.replayed)
        assertEquals(2, dao.examNight("2026-10-18")?.interrupted)
    }

    @Test fun globalBudgetAndLateResponseNotASuccess() = runBlocking {
        val dao = Store(); populate(dao, 3)
        var tick = 0L; var calls = 0
        CorrectionExamEngine(dao, { false }, { false }, { row, _ -> calls++; tick += 900000; row.fact }, { epoch }, { tick }).run()
        assertEquals(1, calls)
        assertEquals(0, dao.examNight("2026-10-18")?.replayed)
        assertEquals(1, dao.examNight("2026-10-18")?.unavailable)
    }

    @Test fun localStopAndDaytimeDoNotSubmit() = runBlocking {
        val dao = Store(); populate(dao, 1)
        CorrectionExamEngine(dao, { false }, { true }, { _, _ -> error("stopped") }, { epoch }).run()
        CorrectionExamEngine(dao, { false }, { false }, { _, _ -> error("daytime") }, { epoch + 12 * 3600000 }).run()
        assertTrue(dao.nights.isEmpty())
    }
}
