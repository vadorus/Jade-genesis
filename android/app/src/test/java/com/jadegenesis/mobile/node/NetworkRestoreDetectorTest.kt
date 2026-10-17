package com.jadegenesis.mobile.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    // --- physical networks, VPN excluded (0.1.26, real Pixel test 17/10) --------

    private class Recorder {
        val events = mutableListOf<String>()
        var losses = 0
        val log: (String, Map<String, Any?>) -> Unit = { event, _ -> events += event }
    }

    private fun monitor(fired: AtomicInteger, recorder: Recorder, scheduler: FakeScheduler = FakeScheduler()) =
        PhysicalNetworkMonitor(
            detector = detector(scheduler, fired),
            onPhysicalLost = { recorder.losses++ },
            log = recorder.log
        )

    @Test
    fun cutOfWifiAndDataIsSeenEvenThoughTheVpnStaysUp() {
        // The VPN is never reported to the monitor (NOT_VPN request): only the
        // physical networks count, so the cut and the return are both visible.
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("wifi-100"))
        m.onNetworkChanged("wifi-100", usable = true, transport = "wifi", nowMs = 1_000L)
        m.onNetworkLost("wifi-100", 2_000L)
        assertEquals(1, rec.losses)
        assertEquals(listOf(PhysicalNetworkMonitor.EVENT_PHYSICAL_LOST), rec.events)
        m.onNetworkChanged("wifi-242", usable = false, transport = "wifi", nowMs = 30_000L)
        assertEquals(0, fired.get())                    // connected, not validated yet
        m.onNetworkChanged("wifi-242", usable = true, transport = "wifi", nowMs = 32_000L)
        assertEquals(1, fired.get())
        assertEquals(PhysicalNetworkMonitor.EVENT_PHYSICAL_BACK, rec.events.last())
    }

    @Test
    fun handoverWithOverlapIsNotALoss() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("wifi-1"))
        m.onNetworkChanged("cell-2", usable = true, transport = "cellular", nowMs = 1_000L)
        m.onNetworkLost("wifi-1", 2_000L)
        assertEquals(0, rec.losses)
        assertEquals(0, fired.get())
        assertTrue(rec.events.isEmpty())
        assertEquals(1, m.validatedCount)
    }

    @Test
    fun lastNetworkLosingValidationCountsAsALoss() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("wifi-1"))
        m.onNetworkChanged("wifi-1", usable = false, transport = "wifi", nowMs = 1_000L)
        assertEquals(1, rec.losses)
        m.onNetworkChanged("wifi-1", usable = true, transport = "wifi", nowMs = 20_000L)
        assertEquals(1, fired.get())
    }

    @Test
    fun openingWithoutPhysicalNetworkTriggersOnTheFirstOne() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(emptyList())
        m.onNetworkChanged("cell-7", usable = true, transport = "cellular", nowMs = 5_000L)
        assertEquals(1, fired.get())
        // No loss callback at start: nothing was lost while the app watched.
        assertEquals(0, rec.losses)
    }

    @Test
    fun onlyTransitionsAreLoggedNotEveryCallback() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("wifi-1"))
        repeat(5) { m.onNetworkChanged("wifi-1", usable = true, transport = "wifi", nowMs = 1_000L + it) }
        m.onNetworkLost("wifi-1", 2_000L)
        m.onNetworkLost("wifi-1", 2_001L)                 // duplicate callback
        assertEquals(listOf(PhysicalNetworkMonitor.EVENT_PHYSICAL_LOST), rec.events)
        assertEquals(1, rec.losses)
        assertEquals(0, fired.get())
    }

    @Test
    fun deferredReturnIsReportedThenFiredByTheRetry() {
        val fired = AtomicInteger()
        val deferred = mutableListOf<Long>()
        val scheduler = FakeScheduler()
        val d = NetworkRestoreDetector(
            scheduler = scheduler,
            onRestored = { fired.incrementAndGet() },
            minIntervalMs = 15_000L,
            onDeferred = { deferred += it }
        )
        val m = PhysicalNetworkMonitor(d)
        m.start(listOf("wifi-1"))
        m.onNetworkLost("wifi-1", 1_000L)
        m.onNetworkChanged("wifi-2", usable = true, transport = "wifi", nowMs = 10_000L)   // fire 1
        m.onNetworkLost("wifi-2", 11_000L)
        m.onNetworkChanged("wifi-3", usable = true, transport = "wifi", nowMs = 13_000L)   // too soon
        assertEquals(listOf(12_000L), deferred)
        assertEquals(1, fired.get())
        d.onRetryDue(25_000L)
        assertEquals(2, fired.get())
    }

    // --- Codex review of #79, P1: network gone between snapshot and registration --

    @Test
    fun networkVanishedBeforeRegistrationIsNotCountedForever() {
        // Codex reproduction: snapshot [A], A disappears before the callback is
        // registered (no callback for A, ever), then B validated, B lost, C validated.
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("A"))
        m.reconcileAfterRegistration(presentNow = emptyList(), nowMs = 1_000L)
        assertEquals(0, m.validatedCount)
        assertEquals(1, rec.losses)                      // nodes shown "à vérifier"
        m.onNetworkChanged("B", usable = true, transport = "wifi", nowMs = 5_000L)
        assertEquals(1, fired.get())                     // one re-check request
        m.onNetworkLost("B", 30_000L)
        assertEquals(2, rec.losses)
        m.onNetworkChanged("C", usable = true, transport = "cellular", nowMs = 60_000L)
        assertEquals(2, fired.get())
        assertEquals(1, m.validatedCount)
    }

    @Test
    fun callbackArrivingBeforeReconciliationIsKept() {
        // The registration thread and the callback thread race: B is reported
        // before the post-registration read, which does not list A any more.
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("A"))
        m.onNetworkChanged("B", usable = true, transport = "wifi", nowMs = 1_000L)
        m.reconcileAfterRegistration(presentNow = listOf("B"), nowMs = 1_100L)
        assertEquals(1, m.validatedCount)
        assertEquals(0, rec.losses)                      // B covered the whole time
        m.onNetworkLost("B", 2_000L)
        assertEquals(1, rec.losses)
        m.onNetworkChanged("C", usable = true, transport = "wifi", nowMs = 20_000L)
        assertEquals(1, fired.get())
    }

    @Test
    fun snapshotNetworkConfirmedByACallbackIsNeverDroppedByReconciliation() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("A"))
        m.onNetworkChanged("A", usable = true, transport = "wifi", nowMs = 1_000L)
        m.reconcileAfterRegistration(presentNow = emptyList(), nowMs = 1_100L)
        assertEquals(1, m.validatedCount)
        assertEquals(0, rec.losses)
        m.onNetworkLost("A", 2_000L)                     // its real loss still arrives
        assertEquals(1, rec.losses)
    }

    @Test
    fun snapshotNetworkStillPresentStaysCountedUntilItsOwnLoss() {
        val fired = AtomicInteger()
        val rec = Recorder()
        val m = monitor(fired, rec)
        m.start(listOf("A"))
        m.reconcileAfterRegistration(presentNow = listOf("A"), nowMs = 1_000L)
        assertEquals(1, m.validatedCount)
        assertEquals(0, rec.losses)
        assertEquals(0, fired.get())
    }

    // --- Codex review of #79, P2: causal order of the network log ---------------

    @Test
    fun logChainIsWrittenInEmissionOrderEvenWhenTheFirstWriteIsSlow() = runBlocking<Unit> {
        val written = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val firstWriteMayFinish = java.util.concurrent.CountDownLatch(1)
        val writerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        var clock = 0L
        val log = OrderedEventWriter(writerScope, clock = { ++clock }) { event, metadata ->
            if (event == "network_physical_back") firstWriteMayFinish.await()
            written += event to (metadata["seq"] as Long)
        }
        // Emitted from the ConnectivityManager thread, in this order.
        val callbackThread = Thread {
            log.emit("network_physical_back", mapOf("validated_networks" to 1))
            log.emit("network_restore_triggered")
        }
        callbackThread.start()
        callbackThread.join()
        // The re-check coroutine waits for its own entry, hence for the earlier ones.
        val recheck = async(kotlinx.coroutines.Dispatchers.Default) {
            log.emitAndWait("network_recheck_started")
            written += "node_probe_online" to -1L          // logged by NodeManager's own logger
            log.emitAndWait("network_recheck_probe", mapOf("attempt" to 1))
        }
        delay(100)
        assertTrue(written.isEmpty())                     // first write still blocked
        assertFalse(recheck.isCompleted)
        firstWriteMayFinish.countDown()
        withTimeout(2_000) { recheck.await() }
        assertEquals(
            listOf(
                "network_physical_back", "network_restore_triggered", "network_recheck_started",
                "node_probe_online", "network_recheck_probe"
            ),
            written.map { it.first }
        )
        val seqs = written.filter { it.second > 0 }.map { it.second }
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs)
        writerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun failedWriteDoesNotBlockTheQueue() = runBlocking<Unit> {
        val written = java.util.Collections.synchronizedList(mutableListOf<String>())
        val writerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val log = OrderedEventWriter(writerScope) { event, _ ->
            if (event == "boom") error("disk full")
            written += event
        }
        log.emit("boom")
        withTimeout(2_000) { log.emitAndWait("after") }
        assertEquals(listOf("after"), written.toList())
        writerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
