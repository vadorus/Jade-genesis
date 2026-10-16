package com.jadegenesis.mobile.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRestoreDetectorTest {

    @Test
    fun firstValidatedNetworkAtStartDoesNotTrigger() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        // The app already probes its nodes when it opens (0.1.24).
        assertFalse(detector.onNetworkValidated(1_000L))
    }

    @Test
    fun validatedNetworkAfterLossTriggersOnce() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        detector.onNetworkValidated(0L)
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(30_000L))
        // Repeated "still validated" callbacks without a new loss: nothing.
        assertFalse(detector.onNetworkValidated(31_000L))
        assertFalse(detector.onNetworkValidated(90_000L))
    }

    @Test
    fun repeatedLossCallbacksStillTriggerOnlyOnce() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        detector.onNetworkLost()
        detector.onNetworkLost()
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(5_000L))
        assertFalse(detector.onNetworkValidated(6_000L))
    }

    @Test
    fun flappingNetworkCannotCauseABurst() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(10_000L))
        detector.onNetworkLost()
        // Back again 3 s later: too soon, no second probe burst.
        assertFalse(detector.onNetworkValidated(13_000L))
    }

    @Test
    fun lossDuringQuietIntervalIsNotForgotten() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(10_000L))
        detector.onNetworkLost()
        assertFalse(detector.onNetworkValidated(13_000L))
        // Next validated callback after the interval: the reconnection is handled.
        assertTrue(detector.onNetworkValidated(26_000L))
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockForever() {
        val detector = NetworkRestoreDetector(minIntervalMs = 15_000L)
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(50_000L))
        detector.onNetworkLost()
        assertTrue(detector.onNetworkValidated(1_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun intervalMustBePositive() {
        NetworkRestoreDetector(minIntervalMs = 0L)
    }
}
