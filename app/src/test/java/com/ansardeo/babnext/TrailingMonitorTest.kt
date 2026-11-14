package com.ansardeo.babnext

import com.ansardeo.babnext.core.model.Side
import com.ansardeo.babnext.core.trading.TrailingMonitor
import com.ansardeo.babnext.core.trading.TrailingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailingMonitorTest {
    private fun tick(state: TrailingState, price: Double, side: Side, fresh: Boolean = true) =
        TrailingMonitor.observe(state, price, fresh, 100.0, 10, side, 33.0, 3.0)

    @Test fun longArmsTracksHighAndClosesAtStop() {
        assertEquals(TrailingState(), tick(TrailingState(), 103.29, Side.LONG))
        val armed = tick(TrailingState(), 103.3, Side.LONG)
        val high = tick(armed, 105.0, Side.LONG)
        assertFalse(tick(high, 104.7, Side.LONG).triggered)
        assertTrue(tick(high, 104.685, Side.LONG).triggered)
    }
    @Test fun shortArmsTracksLowAndClosesAtStop() {
        val armed = tick(TrailingState(), 96.7, Side.SHORT)
        val low = tick(armed, 95.0, Side.SHORT)
        assertFalse(tick(low, 95.28, Side.SHORT).triggered)
        assertTrue(tick(low, 95.285, Side.SHORT).triggered)
    }
    @Test fun stalePricesNeverChangeState() {
        val armed = TrailingState(extreme = 105.0)
        assertEquals(armed, tick(armed, 100.0, Side.LONG, fresh = false))
        assertEquals(TrailingState(), tick(TrailingState(), 110.0, Side.LONG, fresh = false))
    }
}
