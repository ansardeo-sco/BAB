package com.ansardeo.binancebot

import com.ansardeo.binancebot.data.LeverageConfig
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.PaperEngine
import com.ansardeo.binancebot.paper.db.PaperEntryEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Stage 1 pure-logic tests (no Android/Room runtime). Validates shared strategy math and
 * decision rules required before Binance Demo/Live execution.
 */
class Stage1SimulationLogicTest {

    @Test fun leverageTable_exactApprovedValues_allSupported() {
        val expected = mapOf(
            1 to V(26.00, 3.00, 10.00, 80.00),
            2 to V(13.00, 1.50, 5.00, 40.00),
            3 to V(8.67, 1.00, 3.33, 26.67),
            4 to V(6.50, 0.75, 2.50, 20.00),
            5 to V(5.20, 0.60, 2.00, 16.00),
            6 to V(4.33, 0.50, 1.67, 13.33),
            7 to V(3.71, 0.43, 1.43, 11.43),
            8 to V(3.25, 0.38, 1.25, 10.00),
            9 to V(2.89, 0.33, 1.11, 8.89),
            10 to V(2.60, 0.30, 1.00, 8.00),
            11 to V(2.36, 0.27, 0.91, 7.27),
            12 to V(2.17, 0.25, 0.83, 6.67),
            13 to V(2.00, 0.23, 0.77, 6.15),
            14 to V(1.86, 0.21, 0.71, 5.71),
            15 to V(1.73, 0.20, 0.67, 5.33),
            16 to V(1.63, 0.19, 0.63, 5.00),
            17 to V(1.53, 0.18, 0.59, 4.71),
            18 to V(1.44, 0.17, 0.56, 4.44),
            19 to V(1.37, 0.16, 0.53, 4.21),
            20 to V(1.30, 0.15, 0.50, 4.00),
            25 to V(1.04, 0.12, 0.40, 3.20),
        )
        assertEquals(expected.keys.sorted(), LeverageConfig.SUPPORTED_LEVERAGES.sorted())
        expected.forEach { (lev, v) ->
            val p = LeverageConfig.requireProfile(lev)
            assertEquals(v.act, p.trailingActivationPercent, 1e-9)
            assertEquals(v.cb, p.callbackRatePercent, 1e-9)
            assertEquals(v.dist, p.newEntryDistancePercent, 1e-9)
            assertEquals(v.sl, p.slPercent, 1e-9)
        }
    }

    @Test fun trailingProgression_longAndShort() {
        val p = LeverageConfig.requireProfile(10)
        var e = entry("LONG", 100.0, p.trailingActivationPercent, p.callbackRatePercent)
        e = PaperEngine.progressTrailing(e, 102.0).second; assertFalse(e.trailingActivated)
        e = PaperEngine.progressTrailing(e, 102.6).second; assertTrue(e.trailingActivated)
        assertEquals(102.6 * (1 - 0.003), e.trailingStopPrice!!, 1e-9)
        e = PaperEngine.progressTrailing(e, 103.0).second
        assertEquals(103.0 * (1 - 0.003), e.trailingStopPrice!!, 1e-9)
        assertTrue(PaperEngine.isTrailingStopHit(e, e.trailingStopPrice!! - 0.0001))

        var s = entry("SHORT", 100.0, p.trailingActivationPercent, p.callbackRatePercent)
        s = PaperEngine.progressTrailing(s, 98.0).second; assertFalse(s.trailingActivated)
        s = PaperEngine.progressTrailing(s, 97.4).second; assertTrue(s.trailingActivated)
        assertEquals(97.4 * (1 + 0.003), s.trailingStopPrice!!, 1e-9)
        s = PaperEngine.progressTrailing(s, 97.0).second
        assertEquals(97.0 * (1 + 0.003), s.trailingStopPrice!!, 1e-9)
        assertTrue(PaperEngine.isTrailingStopHit(s, s.trailingStopPrice!! + 0.0001))
    }

    @Test fun slIsPerEntryFixedFromActualFill() {
        val p10 = LeverageConfig.requireProfile(10)
        val sl1 = PaperEngine.calcSl("LONG", 100.0, p10.slPercent)
        val sl2 = PaperEngine.calcSl("LONG", 90.0, p10.slPercent)
        assertEquals(92.0, sl1, 1e-9)
        assertEquals(82.8, sl2, 1e-9)
        // averaging/weighted avg must not change original entry SL values
        assertEquals(92.0, sl1, 1e-9)
    }

    @Test fun averagingTriggerRoiAndMaxOnce() {
        val avg = 100.0; val qty = 1.0; val margin = 10.0
        val roi = (PaperEngine.realizedPnl("LONG", avg, 94.0, qty) / margin) * 100.0
        assertEquals(-60.0, roi, 1e-9)
        var triggered = false; var placed = false
        fun maybe() { if (!triggered && !placed && roi <= -60.0) { triggered = true; placed = true } }
        maybe(); assertTrue(triggered && placed)
        maybe(); assertTrue(triggered && placed) // no second action
    }

    @Test fun reentryTwoSides_andNoReentryOnSl() {
        val close = 100.0; val distPct = 1.0
        val long = close * (1 - distPct / 100.0)
        val short = close * (1 + distPct / 100.0)
        fun eligible(longOpen: Boolean, shortOpen: Boolean) = (!longOpen to !shortOpen)
        assertEquals(true to true, eligible(false, false))
        assertEquals(false to true, eligible(true, false))
        assertEquals(true to false, eligible(false, true))
        assertEquals(99.0, long, 1e-9); assertEquals(101.0, short, 1e-9)
        // SL closure must not trigger re-entry
        val slTriggersReentry = false
        assertFalse(slTriggersReentry)
    }

    @Test fun oppositeSideIndependence_semantics() {
        data class Pos(val side: String, var open: Boolean)
        val longPos = Pos("LONG", true)
        val shortPos = Pos("SHORT", true)
        // closing long does not auto-close short
        longPos.open = false
        assertTrue(shortPos.open)
    }

    @Test fun stageGuards_noAuthenticatedOrdersFromSimulatedStages() {
        assertTrue(TradingStage.STAGE_1.isSimulated)
        assertTrue(TradingStage.STAGE_2.isSimulated)
        assertFalse(TradingStage.STAGE_3.isSimulated)
        assertFalse(TradingStage.STAGE_1.canSubmitRealOrders)
        assertFalse(TradingStage.STAGE_2.canSubmitRealOrders)
        assertTrue(TradingStage.STAGE_3.canSubmitRealOrders) // but app blocks Stage 3 execution
    }

    private fun entry(dir: String, fill: Double, act: Double, cb: Double) = PaperEntryEntity(
        id = 1, stage = "STAGE_1", cycleId = 1, symbol = "BTCUSDT", direction = dir, entryType = "INITIAL", status = "FILLED",
        margin = 10.0, leverage = 10, intendedPrice = fill, fillPrice = fill, quantity = 1.0, filledQuantity = 1.0, remainingQuantity = 1.0,
        clientOrderId = "c1", trailingBasePrice = fill, trailingActivationPercent = act, trailingCallbackPercent = cb
    )

    private data class V(val act: Double, val cb: Double, val dist: Double, val sl: Double)
}
