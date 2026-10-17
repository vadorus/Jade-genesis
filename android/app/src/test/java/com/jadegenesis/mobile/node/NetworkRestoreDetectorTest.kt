package com.jadegenesis.mobile.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NetworkRestoreDetectorTest {

    /** Records schedule/cancel calls; the test fires the retry itself. */
    private class FakeScheduler : NetworkRestoreDetector.RetryScheduler {
        var scheduledDelayMs: Long? = null
        var cancelCount = 0

        override fun schedule(delayMs: Long) {
            scheduledDelayMs = delayMs
        }

        override fun cancel() {
            scheduledDelayMs = null
            cancelCount++
        }
    }

    private fun detector(scheduler: FakeScheduler, fired: AtomicInteger) =
        NetworkRestoreDetector(
            scheduler = scheduler,
            onRestored = { fired.incrementAndGet() },
            minIntervalMs = 15_000L
        )

    // --- start ---------------------------------------------------------------

    @Test
    fun startOnlineDoesNotTrigger() {
        val fired = AtomicInteger()
        val d = detector(FakeScheduler(), fired)
        d.onStart(validatedNow = true)
        // Android reports the existing network right after registering.
        d.onNetworkValidated(1_000L)
        assertEquals(0, fired.get())
    }

    @Test
    fun startWithoutNetworkTriggersOnFirstNetwork() {
        // Codex P2: no onLost is guaranteed when registering with no network.
        val fired = AtomicInteger()
        val d = detector(FakeScheduler(), fired)
        d.onStart(validatedNow = false)
        d.onNetworkValidated(5_000L)
        assertEquals(1, fired.get())
    }

    // --- loss / restore --------------------------------------------------------

    @Test
    fun validatedNetworkAfterLossTriggersOnce() {
        val fired = AtomicInteger()
        val d = detector(FakeScheduler(), fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(30_000L)
        d.onNetworkValidated(31_000L)
        d.onNetworkValidated(90_000L)
        assertEquals(1, fired.get())
    }

    @Test
    fun repeatedLossCallbacksStillTriggerOnlyOnce() {
        val fired = AtomicInteger()
        val d = detector(FakeScheduler(), fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkLost()
        d.onNetworkLost()
        d.onNetworkValidated(5_000L)
        d.onNetworkValidated(6_000L)
        assertEquals(1, fired.get())
    }

    // --- anti-burst interval (Codex P1) ------------------------------------------

    @Test
    fun reconnectionTooSoonIsRetriedAtEndOfInterval() {
        val fired = AtomicInteger()
        val scheduler = FakeScheduler()
        val d = detector(scheduler, fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(10_000L)              // trigger 1
        d.onNetworkLost()
        d.onNetworkValidated(13_000L)              // 3 s later: too soon
        assertEquals(1, fired.get())
        assertEquals(12_000L, scheduler.scheduledDelayMs)
        // No further callback from Android: only the scheduled retry runs.
        d.onRetryDue(25_000L)
        assertEquals(2, fired.get())
    }

    @Test
    fun retryIsDroppedWhenTheNetworkIsLostAgain() {
        val fired = AtomicInteger()
        val scheduler = FakeScheduler()
        val d = detector(scheduler, fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(10_000L)
        d.onNetworkLost()
        d.onNetworkValidated(13_000L)
        d.onNetworkLost()                          // gone again before the retry
        assertNull(scheduler.scheduledDelayMs)
        assertTrue(scheduler.cancelCount >= 1)
        d.onRetryDue(25_000L)                      // stale retry, if delivered
        assertEquals(1, fired.get())
        d.onNetworkValidated(40_000L)              // real return later
        assertEquals(2, fired.get())
    }

    @Test
    fun stopCancelsThePendingRetry() {
        val fired = AtomicInteger()
        val scheduler = FakeScheduler()
        val d = detector(scheduler, fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(10_000L)
        d.onNetworkLost()
        d.onNetworkValidated(13_000L)
        d.stop()
        assertNull(scheduler.scheduledDelayMs)
        d.onRetryDue(25_000L)
        assertEquals(1, fired.get())
    }

    @Test
    fun onlyOneRetryIsScheduledForSeveralEarlyReturns() {
        val scheduler = FakeScheduler()
        var schedules = 0
        val counting = object : NetworkRestoreDetector.RetryScheduler {
            override fun schedule(delayMs: Long) { schedules++; scheduler.schedule(delayMs) }
            override fun cancel() = scheduler.cancel()
        }
        val d = NetworkRestoreDetector(counting, onRestored = {}, minIntervalMs = 15_000L)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(10_000L)
        d.onNetworkLost()
        d.onNetworkValidated(12_000L)
        d.onNetworkValidated(13_000L)
        d.onNetworkValidated(14_000L)
        assertEquals(1, schedules)
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockForever() {
        val fired = AtomicInteger()
        val d = detector(FakeScheduler(), fired)
        d.onStart(validatedNow = true)
        d.onNetworkLost()
        d.onNetworkValidated(50_000L)
        d.onNetworkLost()
        d.onNetworkValidated(1_000L)
        assertEquals(2, fired.get())
    }

    @Test(expected = IllegalArgumentException::class)
    fun intervalMustBePositive() {
        NetworkRestoreDetector(FakeScheduler(), onRestored = {}, minIntervalMs = 0L)
    }

    // --- coalescing runner (Codex P1: restore during an active re-check) ---------

    @Test
    fun restoreDuringActiveRecheckRunsOneMoreRecheck() = runBlocking {
        val runs = AtomicInteger()
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val runner = CoalescingRunner(this) {
            started.send(runs.incrementAndGet())
            release.receive()
        }
        runner.request()
        assertEquals(1, withTimeout(2_000) { started.receive() })
        // Two more reconnections while run 1 is in progress.
        runner.request()
        runner.request()
        release.send(Unit)
        assertEquals(2, withTimeout(2_000) { started.receive() })
        release.send(Unit)
        delay(100)
        assertTrue(started.tryReceive().isFailure)
        assertEquals(2, runs.get())
    }

    @Test
    fun requestAfterCompletionStartsANewRun() = runBlocking {
        val runs = AtomicInteger()
        val done = Channel<Int>(Channel.UNLIMITED)
        val runner = CoalescingRunner(this) { done.send(runs.incrementAndGet()) }
        runner.request()
        assertEquals(1, withTimeout(2_000) { done.receive() })
        delay(50)
        runner.request()
        assertEquals(2, withTimeout(2_000) { done.receive() })
    }

    @Test
    fun failedRunReleasesTheRunner() = runBlocking {
        val runs = AtomicInteger()
        val ok = CompletableDeferred<Unit>()
        val supervisor = kotlinx.coroutines.CoroutineScope(
            coroutineContext + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
        )
        val runner = CoalescingRunner(supervisor) {
            if (runs.incrementAndGet() == 1) error("probe crashed") else ok.complete(Unit)
        }
        runner.request()
        delay(100)
        runner.request()
        withTimeout(2_000) { ok.await() }
        assertEquals(2, runs.get())
    }

    // --- patient retry after a restore (Codex P2: cooldown race) -----------------

    @Test
    fun markFromEarlierGenerationNeverBlocks() {
        // A refresh that began before the reconnection writes its mark after it.
        val staleMark = NodeRefreshPolicy.PatientRetryMark(atMs = 100_000L, generation = 0L)
        assertTrue(NodeRefreshPolicy.shouldRunPatientRetry(100_500L, staleMark, 1L, 60_000L))
    }

    @Test
    fun markFromSameGenerationKeepsTheCooldown() {
        val mark = NodeRefreshPolicy.PatientRetryMark(atMs = 100_000L, generation = 3L)
        assertFalse(NodeRefreshPolicy.shouldRunPatientRetry(159_999L, mark, 3L, 60_000L))
        assertTrue(NodeRefreshPolicy.shouldRunPatientRetry(160_000L, mark, 3L, 60_000L))
    }

    @Test
    fun olderRefreshDoesNotBypassANewerMark() {
        // A refresh still running from generation 1 sees a mark from generation 2.
        val mark = NodeRefreshPolicy.PatientRetryMark(atMs = 100_000L, generation = 2L)
        assertFalse(NodeRefreshPolicy.shouldRunPatientRetry(100_500L, mark, 1L, 60_000L))
    }

    @Test
    fun noMarkAllowsThePatientRetry() {
        assertTrue(NodeRefreshPolicy.shouldRunPatientRetry(5L, null, 0L, 60_000L))
    }
}
