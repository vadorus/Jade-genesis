package com.jadegenesis.mobile.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId


/** Pure orchestration, tested with synthetic stores and a deterministic clock. */
class CorrectionExamEngine(
    private val dao: CorrectionDao,
    private val halted: suspend () -> Boolean,
    private val stopped: () -> Boolean,
    private val replay: suspend (UserCorrection, Long) -> String,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1000000 }
) {
    private companion object {
        // Serialize exams only. Conversation/mutation callers never acquire this mutex.
        val examMutex = Mutex()
    }

    suspend fun run() = examMutex.withLock {
        val paris = Instant.ofEpochMilli(epochMs()).atZone(ZoneId.of("Europe/Paris"))
        if (paris.hour !in 0..6 || (paris.hour == 6 && paris.minute >= 30) || (paris.hour == 0 && paris.minute < 30)) return@withLock
        val night = paris.toLocalDate().toString()
        val nightStart = paris.toLocalDate().atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        val (initialStats, active) = CorrectionGate.mutex.withLock {
            (dao.examNight(night) ?: CorrectionExamNight(night)) to
                CorrectionStore(dao).active().filter { it.lastExamAt < nightStart }
                    .sortedWith(compareBy<UserCorrection> { it.lastExamAt }.thenBy { it.createdAt })
        }
        var stats = initialStats
        if (active.isEmpty() || stats.attempts >= 10 || stats.usedMs >= CorrectionExamBudget.NIGHT_MS) return@withLock
        val started = elapsedMs()
        for (row in active) {
            if (stopped() || CorrectionExamBudget.slice(stats, elapsedMs() - started) < 5000) break
            if (halted()) {
                CorrectionGate.mutex.withLock {
                    dao.saveNight(stats.copy(interrupted = stats.interrupted + 1, updatedAt = epochMs()))
                }
                break
            }
            if (stopped()) break
            val reservation = CorrectionGate.mutex.withLock {
                val reserved = CorrectionExamBudget.slice(stats, elapsedMs() - started)
                val current = CorrectionStore(dao).active().firstOrNull {
                    it.id == row.id && it.lastExamAt < nightStart
                }
                if (reserved < 5000 || current == null) null else {
                    stats = stats.copy(attempts = stats.attempts + 1, usedMs = stats.usedMs + reserved, updatedAt = epochMs())
                    // Full slice charged before submission: a crash/retry cannot replenish it.
                    dao.saveNight(stats)
                    dao.markExam(current.id, epochMs())
                    current to reserved
                }
            } ?: continue
            val (current, reserved) = reservation
            try {
                val callStarted = elapsedMs()
                // Never hold CorrectionGate across replay or the network HALT projection.
                val response = replay(current, reserved - 4000)
                check(elapsedMs() - callStarted < reserved - 4000) { "correction_exam_deadline" }
                val haltedAfterReplay = halted()
                val interrupted = CorrectionGate.mutex.withLock {
                    // Waiting for a concurrent conversation's DB gate also spends the budget.
                    val interrupted = stopped() || haltedAfterReplay || elapsedMs() - started >= CorrectionExamBudget.NIGHT_MS
                    val expired = elapsedMs() - callStarted >= reserved - 4000
                    // Forgetting, a pending conflict or replacement during inference invalidates it.
                    val stillActive = CorrectionStore(dao).active().any {
                        it.id == current.id && it.fact == current.fact && it.question == current.question
                    }
                    stats = when {
                        interrupted -> stats.copy(interrupted = stats.interrupted + 1, updatedAt = epochMs())
                        !stillActive || expired -> stats.copy(unavailable = stats.unavailable + 1, updatedAt = epochMs())
                        else -> {
                            val result = CorrectionPolicy.passes(current.fact, response)
                            stats.copy(replayed = stats.replayed + 1,
                                evaluable = stats.evaluable + if (result != null) 1 else 0,
                                repeated = stats.repeated + if (result == false) 1 else 0, updatedAt = epochMs())
                        }
                    }
                    dao.saveNight(stats)
                    interrupted
                }
                if (interrupted) break
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                stats = stats.copy(unavailable = stats.unavailable + 1, updatedAt = epochMs())
                CorrectionGate.mutex.withLock { dao.saveNight(stats) }
            }
        }
    }
}
