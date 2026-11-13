package com.ansardeo.babnext

import com.ansardeo.babnext.core.model.Side
import com.ansardeo.babnext.core.trading.TrailingStopCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingStopCalculatorTest {
    @Test
    fun activationAt10x() {
        assertEquals(103.3, TrailingStopCalculator.activationPrice(100.0, 10, Side.LONG), 0.001)
        assertEquals(96.7, TrailingStopCalculator.activationPrice(100.0, 10, Side.SHORT), 0.001)
    }
    @Test
    fun callbackRate() {
        assertEquals(0.3, TrailingStopCalculator.callbackRate(10), 0.0001)
        assertEquals(0.6, TrailingStopCalculator.callbackRate(5), 0.0001)
        assertEquals(0.15, TrailingStopCalculator.callbackRate(20), 0.0001)
    }
}