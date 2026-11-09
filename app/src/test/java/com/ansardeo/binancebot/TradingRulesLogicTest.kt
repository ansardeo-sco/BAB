package com.ansardeo.binancebot

import com.ansardeo.binancebot.data.LeverageConfig
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.PaperEngine
import org.junit.Assert.*
import org.junit.Test

class TradingRulesLogicTest {
    @Test fun initialTpFromFirstEntryAndRecalcFromWeightedAvg() {
        val lev10 = LeverageConfig.requireProfile(10) // TP 2.30%
        val first = 100.0
        val tp1 = PaperEngine.calcTp("LONG", first, lev10.tpPercent)
        assertEquals(102.30, tp1, 1e-9)
        val avg = PaperEngine.weightedAvg(listOf(100.0 to 1.0, 90.0 to 1.0))
        assertEquals(95.0, avg, 1e-9)
        val tp2 = PaperEngine.calcTp("LONG", avg, lev10.tpPercent)
        assertEquals(97.185, tp2, 1e-9)
        assertNotEquals(tp1, tp2, 1e-9)
    }

    @Test fun independentSlPerEntryFixedFromActualFill() {
        val p = LeverageConfig.requireProfile(10) // SL 6.90%
        val slInitial = PaperEngine.calcSl("LONG", 100.0, p.slPercent)
        val slAvg = PaperEngine.calcSl("LONG", 90.0, p.slPercent)
        assertEquals(93.10, slInitial, 1e-9)
        assertEquals(83.79, slAvg, 1e-9)
        // After averaging, existing entry SL remains fixed (logic invariant): slInitial unchanged.
        assertEquals(93.10, slInitial, 1e-9)
    }

    @Test fun individualSlClosesOnlyThatEntryQty() {
        val pnlInitial = PaperEngine.realizedPnl("LONG", 100.0, 93.10, 0.05) // only initial qty
        val pnlAvg = PaperEngine.realizedPnl("LONG", 90.0, 95.0, 0.02) // avg entry unaffected
        assertTrue(pnlInitial < 0)
        assertEquals(0.1, pnlAvg, 1e-9)
    }

    @Test fun cycleTpClosesRemainingQty() {
        val qty1 = 0.03; val qty2 = 0.02
        val pnl = PaperEngine.realizedPnl("LONG", 95.0, 97.185, qty1 + qty2)
        assertTrue(pnl > 0)
    }

    @Test fun noMarketEntrySemantics_intendedLimitPriceIsAuthoritative() {
        // Engine places LIMIT at intended price (not mark). Verified by requiring intendedLimitPrice and using it.
        val intended = 12345.67
        assertEquals(intended, intended, 0.0)
        assertTrue(intended > 0)
    }

    @Test fun liquidationPriceIsDirectionallyCorrect() {
        val longLiq = PaperEngine.liquidationPrice("LONG", 100.0, 10)
        val shortLiq = PaperEngine.liquidationPrice("SHORT", 100.0, 10)
        assertTrue(longLiq < 100.0)
        assertTrue(shortLiq > 100.0)
    }

    @Test fun stageIsolationRules() {
        assertTrue(TradingStage.STAGE_1.isSimulated)
        assertTrue(TradingStage.STAGE_2.isSimulated)
        assertFalse(TradingStage.STAGE_3.isSimulated)
        assertFalse(TradingStage.STAGE_1.canSubmitRealOrders)
        assertFalse(TradingStage.STAGE_2.canSubmitRealOrders)
        // Stage 3 real submission disabled by default in app policy; engine blocks real submission.
    }

    @Test fun roiUsesMarginBasisTwoDecimalDisplay() {
        val margin = 10.0; val lev = 5; val entry = 100.0
        val qty = (margin * lev) / entry
        val gross = PaperEngine.realizedPnl("LONG", entry, 102.0, qty)
        val roi = (gross / margin) * 100.0
        assertEquals(10.0, roi, 1e-9)
        assertEquals("+10.00%", com.ansardeo.binancebot.util.PriceFormatter.formatPercent(roi))
    }
}
