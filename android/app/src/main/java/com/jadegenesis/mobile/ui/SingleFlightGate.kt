package com.jadegenesis.mobile.ui

import java.util.concurrent.atomic.AtomicBoolean

/** Allows at most one caller to own a bounded UI operation at a time. */
internal class SingleFlightGate {
    private val running = AtomicBoolean(false)

    fun tryEnter(): Boolean = running.compareAndSet(false, true)

    fun leave() {
        running.set(false)
    }
}
