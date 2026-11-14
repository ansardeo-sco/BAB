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
    @Test
    fun callbackUsesRoiAndFavorableExtreme() {
        assertEquals(104.685, TrailingStopCalculator.stopPrice(105.0, 10, Side.LONG, 3.0), 0.000001)
        assertEquals(95.285, TrailingStopCalculator.stopPrice(95.0, 10, Side.SHORT, 3.0), 0.000001)
    }
    @Test
    fun averagingChangesActivation() {
        val wap = TrailingStopCalculator.weightedAverage(listOf(100.0 to 1.0, 90.0 to 2.0))
        assertEquals(280.0 / 3.0, wap, 0.000001)
        assertEquals(wap * 1.033, TrailingStopCalculator.activationPrice(wap, 10, Side.LONG), 0.000001)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidLeverageRejected() {
        TrailingStopCalculator.activationPrice(100.0, 0, Side.LONG)
    }
}