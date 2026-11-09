package com.ansardeo.binancebot.data

import org.junit.Assert.*
import org.junit.Test

class RsiMathTest {
    // Test RSI math via a lightweight instance; MarketRepository's RSI is pure/internal.
    private fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size < period + 1) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) { val d = closes[i]-closes[i-1]; if (d>=0) gain+=d else loss+=kotlin.math.abs(d) }
        var avgGain = gain/period; var avgLoss = loss/period
        for (i in period+1 until closes.size) { val d = closes[i]-closes[i-1]; val g = if (d>0) d else 0.0; val l = if (d<0) kotlin.math.abs(d) else 0.0; avgGain=((avgGain*(period-1))+g)/period; avgLoss=((avgLoss*(period-1))+l)/period }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain/avgLoss; return (100.0 - (100.0/(1.0+rs))).coerceIn(0.0,100.0)
    }

    @Test fun rsiWilderBasicUptrend() {
        val closes = listOf(44.0,44.34,44.09,43.61,44.33,44.83,45.10,45.42,45.84,46.08,45.89,46.03,45.61,46.28,46.28,46.00)
        val v = rsi(closes, 14)
        assertNotNull(v)
        assertTrue(v!! > 50.0 && v <= 100.0)
    }

    @Test fun rsiInsufficientDataReturnsNull() {
        assertNull(rsi(listOf(1.0,2.0,3.0), 14))
    }

    @Test fun rsiAllGainsIs100() {
        val closes = (1..20).map { it.toDouble() }
        assertEquals(100.0, rsi(closes, 14)!!, 1e-9)
    }
}
