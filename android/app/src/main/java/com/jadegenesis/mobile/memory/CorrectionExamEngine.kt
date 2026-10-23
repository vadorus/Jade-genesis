package com.jadegenesis.mobile.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

object CorrectionGate { val mutex = Mutex() }

/** Pure orchestration, tested with synthetic stores and a deterministic clock. */
class CorrectionExamEngine(
    private val dao: CorrectionDao,
    private val halted: suspend () -> Boolean,
    private val stopped: () -> Boolean,
    private val replay: suspend (UserCorrection, Long) -> String,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1000000 }
) {
    suspend fun run() = CorrectionGate.mutex.withLock {
        val paris = Instant.ofEpochMilli(epochMs()).atZone(ZoneId.of("Europe/Paris"))
        if (paris.hour !in 0..6 || (paris.hour == 6 && paris.minute >= 30) || (paris.hour == 0 && paris.minute < 30)) return@withLock
        val night = paris.toLocalDate().toString()
        val nightStart = paris.toLocalDate().atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        var stats = dao.examNight(night) ?: CorrectionExamNight(night)
        val active = CorrectionStore(dao).active().filter { it.lastExamAt < nightStart }
            .sortedWith(compareBy<UserCorrection> { it.lastExamAt }.thenBy { it.createdAt })
        if (active.isEmpty() || stats.attempts >= 10 || stats.usedMs >= CorrectionExamBudget.NIGHT_MS) return@withLock
        val started = elapsedMs()
        for (row in active) {
            if (stopped() || CorrectionExamBudget.slice(stats, elapsedMs() - started) < 5000) break
            if (halted()) {
                dao.saveNight(stats.copy(interrupted = stats.interrupted + 1, updatedAt = epochMs()))
                break
            }
            val reserved = CorrectionExamBudget.slice(stats, elapsedMs() - started)
            if (reserved < 5000 || stopped()) break
            val current = CorrectionStore(dao).active().firstOrNull { it.id == row.id } ?: continue
            stats = stats.copy(attempts = stats.attempts + 1, usedMs = stats.usedMs + reserved, updatedAt = epochMs())
            // Full slice charged before submission: a crash/retry cannot replenish the budget.
            dao.saveNight(stats)
            dao.markExam(current.id, epochMs())
            try {
                val callStarted = elapsedMs()
                val response = replay(current, reserved - 4000)
                check(elapsedMs() - callStarted < reserved - 4000) { "correction_exam_deadline" }
                if (stopped() || halted() || elapsedMs() - started >= CorrectionExamBudget.NIGHT_MS) {
                    stats = stats.copy(interrupted = stats.interrupted + 1, updatedAt = epochMs())
                    dao.saveNight(stats)
                    break
                }
                val result = CorrectionPolicy.passes(current.fact, response)
                stats = stats.copy(replayed = stats.replayed + 1,
                    evaluable = stats.evaluable + if (result != null) 1 else 0,
                    repeated = stats.repeated + if (result == false) 1 else 0, updatedAt = epochMs())
                dao.saveNight(stats)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                stats = stats.copy(unavailable = stats.unavailable + 1, updatedAt = epochMs())
                dao.saveNight(stats)
            }
        }
    }
}
