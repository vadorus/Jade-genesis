package com.jadegenesis.mobile

import com.jadegenesis.mobile.runtime.RuntimeManager
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeVersionTest {
    @Test
    fun expectedNodeRuntimeMatches021Runtime() {
        assertEquals("0.1.9", RuntimeManager.EXPECTED_RUNTIME_VERSION)
    }
}
