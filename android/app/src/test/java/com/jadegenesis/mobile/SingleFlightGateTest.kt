package com.jadegenesis.mobile

import com.jadegenesis.mobile.ui.SingleFlightGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightGateTest {

    @Test
    fun secondEntryIsRejectedUntilOwnerLeaves() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.leave()
        assertTrue(gate.tryEnter())
    }
}
