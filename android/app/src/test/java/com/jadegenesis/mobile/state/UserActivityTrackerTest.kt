package com.jadegenesis.mobile.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserActivityTrackerTest {

    @Test
    fun firstUseIsStored() {
        assertTrue(UserActivityPolicy.shouldStore(0L, 1_000_000L))
    }

    @Test
    fun repeatedUseWithinAMinuteIsNotRewritten() {
        assertFalse(UserActivityPolicy.shouldStore(1_000_000L, 1_059_999L))
        assertTrue(UserActivityPolicy.shouldStore(1_000_000L, 1_060_000L))
    }

    @Test
    fun clockSetBackwardsIsAcceptedSoTheValueNeverStaysInTheFuture() {
        assertTrue(UserActivityPolicy.shouldStore(5_000_000L, 1_000_000L))
    }

    @Test
    fun invalidClockIsIgnored() {
        assertFalse(UserActivityPolicy.shouldStore(1_000_000L, 0L))
        assertFalse(UserActivityPolicy.shouldStore(0L, -5L))
    }
}
