package com.ansardeo.binancebot.data

import org.junit.Assert.*
import org.junit.Test

class LeverageConfigTest {
    @Test fun acceptsSupportedLeverages() {
        val expected = listOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,25)
        assertEquals(expected, LeverageConfig.SUPPORTED_LEVERAGES)
        for (lev in expected) assertTrue("expected $lev supported", LeverageConfig.isSupported(lev))
    }

    @Test fun rejectsUnsupportedLeverages() {
        listOf(Int.MIN_VALUE, -1, 0, 21, 22, 23, 24, 26, 125).forEach {
            assertFalse("$it should be rejected", LeverageConfig.isSupported(it))
            try { LeverageConfig.requireSupported(it); fail("should reject $it") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun exactApprovedTable() {
        val expected = mapOf(
            1 to R(26.00, 3.00, 10.00, 80.00), 2 to R(13.00, 1.50, 5.00, 40.00), 3 to R(8.67, 1.00, 3.33, 26.67),
            4 to R(6.50, 0.75, 2.50, 20.00), 5 to R(5.20, 0.60, 2.00, 16.00), 6 to R(4.33, 0.50, 1.67, 13.33),
            7 to R(3.71, 0.43, 1.43, 11.43), 8 to R(3.25, 0.38, 1.25, 10.00), 9 to R(2.89, 0.33, 1.11, 8.89),
            10 to R(2.60, 0.30, 1.00, 8.00), 11 to R(2.36, 0.27, 0.91, 7.27), 12 to R(2.17, 0.25, 0.83, 6.67),
            13 to R(2.00, 0.23, 0.77, 6.15), 14 to R(1.86, 0.21, 0.71, 5.71), 15 to R(1.73, 0.20, 0.67, 5.33),
            16 to R(1.63, 0.19, 0.63, 5.00), 17 to R(1.53, 0.18, 0.59, 4.71), 18 to R(1.44, 0.17, 0.56, 4.44),
            19 to R(1.37, 0.16, 0.53, 4.21), 20 to R(1.30, 0.15, 0.50, 4.00), 25 to R(1.04, 0.12, 0.40, 3.20),
        )
        assertEquals(21, expected.size)
        expected.forEach { (lev, e) ->
            val p = LeverageConfig.requireProfile(lev)
            assertEquals(e.act, p.trailingActivationPercent, 1e-9)
            assertEquals(e.cb, p.callbackRatePercent, 1e-9)
            assertEquals(e.dist, p.newEntryDistancePercent, 1e-9)
            assertEquals(e.sl, p.slPercent, 1e-9)
        }
    }

    @Test fun settingsDerivePercentagesFromLeverage() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 10)
        assertEquals(2.60, s.trailingActivationPercent, 1e-9)
        assertEquals(0.30, s.callbackRatePercent, 1e-9)
        assertEquals(1.00, s.newEntryDistancePercent, 1e-9)
        assertEquals(8.00, s.slPercent, 1e-9)
    }

    @Test fun settingsSanitizeInvalidLeverageToSupportedDefault() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 125)
        assertTrue(LeverageConfig.isSupported(s.leverage))
        assertEquals(6, s.leverage)
    }

    private data class R(val act: Double, val cb: Double, val dist: Double, val sl: Double)
}
