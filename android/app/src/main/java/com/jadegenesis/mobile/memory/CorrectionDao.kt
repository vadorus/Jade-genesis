package com.jadegenesis.mobile.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.util.UUID

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCorrection(row: UserCorrection)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFact(row: MemoryEntity)

    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC, id DESC")
    suspend fun rows(): List<UserCorrection>

    @Query("SELECT * FROM memory_events WHERE supersededBy IS NULL AND type = 'FACT'")
    suspend fun activeFacts(): List<MemoryEntity>

    @Query("UPDATE memory_events SET supersededBy = :replacement WHERE id IN (:ids) AND supersededBy IS NULL")
    suspend fun supersede(ids: List<String>, replacement: String): Int

    @Query("UPDATE user_corrections SET state = 'ACTIVE', targets = '|' WHERE id = :id AND state = 'PENDING'")
    suspend fun activate(id: String): Int

    @Query("DELETE FROM memory_events WHERE id = :id AND source = 'USER_CORRECTION'")
    suspend fun deleteFact(id: String)

    @Query("DELETE FROM user_corrections WHERE id = :id OR (state = 'PENDING' AND instr(targets, '|' || :id || '|') > 0)")
    suspend fun deleteLinks(id: String)

    @Query("UPDATE user_corrections SET lastExamAt = :at WHERE id = :id")
    suspend fun markExam(id: String, at: Long)

    @Query("SELECT * FROM correction_exam_nights WHERE night = :night")
    suspend fun examNight(night: String): CorrectionExamNight?

    @Query("SELECT * FROM correction_exam_nights ORDER BY night DESC LIMIT 1")
    suspend fun lastExamNight(): CorrectionExamNight?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNight(row: CorrectionExamNight)

    @Transaction
    suspend fun propose(question: String, answerRef: String, wrongAnswer: String, fact: String, origin: String): UserCorrection {
        require(question.isNotBlank() && answerRef.isNotBlank() && wrongAnswer.isNotBlank()) { "Réponse à corriger manquante." }
        require(fact.isNotBlank() && fact.length <= CorrectionPolicy.MAX_FACT && question.length <= 10000 && wrongAnswer.length <= 30000) { "Correction vide ou trop longue." }
        val linked = rows().associateBy { it.id }
        require(linked.size < 10000) { "Capacité des corrections atteinte ; aucune donnée supprimée." }
        val targets = activeFacts().filter { existing ->
            CorrectionPolicy.overlaps(question, fact, linked[existing.id]?.question.orEmpty(), existing.content)
        }.map { it.id }.sorted()
        val row = UserCorrection(UUID.randomUUID().toString(), question, answerRef, wrongAnswer,
            fact.trim(), System.currentTimeMillis(), if (targets.isEmpty()) "ACTIVE" else "PENDING",
            targets.joinToString("|", prefix = "|", postfix = "|"))
        insertCorrection(row)
        if (row.state == "ACTIVE") insertFact(row.asFact(origin))
        return row
    }

    /** Consent is bound to the proposal and the exact still-active target set. */
    @Transaction
    suspend fun confirm(id: String, origin: String, keepDistinct: Boolean = false): Boolean {
        val linked = rows().associateBy { it.id }
        val row = linked[id]?.takeIf { it.state == "PENDING" } ?: return false
        val targets = row.targets.split('|').filter { it.isNotBlank() }.sorted()
        val current = activeFacts().filter { existing ->
            CorrectionPolicy.overlaps(row.question, row.fact, linked[existing.id]?.question.orEmpty(), existing.content)
        }.map { it.id }.sorted()
        if (targets != current) return false
        insertFact(row.asFact(origin))
        if (!keepDistinct && targets.isNotEmpty()) check(supersede(targets, row.id) == targets.size)
        check(activate(id) == 1)
        return true
    }

    @Transaction
    suspend fun forget(id: String) {
        deleteLinks(id)
        deleteFact(id)
    }
}

private fun UserCorrection.asFact(origin: String) = MemoryEntity(id, "FACT", fact,
    CorrectionPolicy.SOURCE, 1.0, origin, createdAt)
