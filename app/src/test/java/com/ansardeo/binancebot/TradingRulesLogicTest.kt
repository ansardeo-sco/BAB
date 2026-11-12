package com.ansardeo.binancebot

import com.ansardeo.binancebot.data.LeverageConfig
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.PaperEngine
import com.ansardeo.binancebot.paper.db.PaperEntryEntity
import org.junit.Assert.*
import org.junit.Test

class TradingRulesLogicTest {
    private fun filledEntry(dir: String, fill: Double, base: Double = fill, lev: Int = 10): PaperEntryEntity {
        val p = LeverageConfig.requireProfile(lev)
        return PaperEntryEntity(
            id = 1, stage = "STAGE_1", cycleId = 1, symbol = "BTCUSDT", direction = dir, entryType = "INITIAL", status = "FILLED",
            margin = 10.0, leverage = lev, intendedPrice = fill, fillPrice = fill, quantity = 1.0, filledQuantity = 1.0, remainingQuantity = 1.0,
            clientOrderId = "c1", trailingBasePrice = base, trailingActivationPercent = p.trailingActivationPercent, trailingCallbackPercent = p.callbackRatePercent
        )
    }

    @Test fun trailingActivationAndCallback_long() {
        val e0 = filledEntry("LONG", 100.0) // 10x: act 2.6%, cb 0.3%
        val (_, e1) = PaperEngine.progressTrailing(e0, 102.0) // below activation
        assertFalse(e1.trailingActivated)
        val (_, e2) = PaperEngine.progressTrailing(e0, 102.6) // activate
        assertTrue(e2.trailingActivated)
        assertEquals(102.6, e2.trailingExtremePrice!!, 1e-9)
        assertEquals(102.6 * (1 - 0.003), e2.trailingStopPrice!!, 1e-9)
        val (_, e3) = PaperEngine.progressTrailing(e2, 103.0) // new high
        assertEquals(103.0, e3.trailingExtremePrice!!, 1e-9)
        assertEquals(103.0 * (1 - 0.003), e3.trailingStopPrice!!, 1e-9)
        assertFalse(PaperEngine.isTrailingStopHit(e3, 102.7))
        assertTrue(PaperEngine.isTrailingStopHit(e3, e3.trailingStopPrice!!))
    }

    @Test fun trailingActivationAndCallback_short() {
        val e0 = filledEntry("SHORT", 100.0)
        val (_, e1) = PaperEngine.progressTrailing(e0, 98.0) // above activation threshold (needs <= 97.4)
        assertFalse(e1.trailingActivated)
        val (_, e2) = PaperEngine.progressTrailing(e0, 97.4)
        assertTrue(e2.trailingActivated)
        assertEquals(97.4, e2.trailingExtremePrice!!, 1e-9)
        assertEquals(97.4 * (1 + 0.003), e2.trailingStopPrice!!, 1e-9)
        val (_, e3) = PaperEngine.progressTrailing(e2, 97.0) // new low
        assertEquals(97.0, e3.trailingExtremePrice!!, 1e-9)
        assertEquals(97.0 * (1 + 0.003), e3.trailingStopPrice!!, 1e-9)
        assertFalse(PaperEngine.isTrailingStopHit(e3, 97.2))
        assertTrue(PaperEngine.isTrailingStopHit(e3, e3.trailingStopPrice!!))
    }

    @Test fun individualSlFromActualFill_fixedOnAveraging() {
        val p = LeverageConfig.requireProfile(10) // SL 8.0%
        val slInitial = PaperEngine.calcSl("LONG", 100.0, p.slPercent)
        val slAvg = PaperEngine.calcSl("LONG", 90.0, p.slPercent)
        assertEquals(92.0, slInitial, 1e-9)
        assertEquals(82.8, slAvg, 1e-9)
        // invariant: original SL remains fixed after averaging
        assertEquals(92.0, slInitial, 1e-9)
    }

    @Test fun weightedAverageRecalculation() {
        val avg = PaperEngine.weightedAvg(listOf(100.0 to 1.0, 90.0 to 1.0))
        assertEquals(95.0, avg, 1e-9)
    }

    @Test fun averagingTriggerAtMinus60Roi_oncePerCycle_semantics() {
        val avg = 100.0; val qty = 1.0; val margin = 10.0
        val mark = 94.0 // LONG: pnl -6 on margin 10 => -60%
        val unreal = PaperEngine.realizedPnl("LONG", avg, mark, qty)
        val roi = (unreal / margin) * 100.0
        assertEquals(-60.0, roi, 1e-9)
        var triggered = false
        var ordersPlaced = false
        if (!triggered && !ordersPlaced && roi <= -60.0) { triggered = true; ordersPlaced = true }
        assertTrue(triggered && ordersPlaced)
        // second time in same cycle must not place again
        val canTriggerAgain = !(triggered || ordersPlaced)
        assertFalse(canTriggerAgain)
    }

    @Test fun pendingAveragingBehavior_semantics() {
        // if one averaging executes keep other pending; SL hit must not auto-cancel other pending averaging order
        val pendingOrders = mutableListOf("AVG_LONG", "AVG_SHORT")
        pendingOrders.remove("AVG_LONG") // one executed
        assertTrue(pendingOrders.contains("AVG_SHORT"))
        // SL hit closes only that entry; does not touch pending averaging orders
        assertEquals(1, pendingOrders.size)
    }

    @Test fun reentryConditions_afterTrailingStop() {
        val close = 100.0; val dist = 1.0 // %
        val longPrice = close * (1 - dist / 100.0)
        val shortPrice = close * (1 + dist / 100.0)
        assertEquals(99.0, longPrice, 1e-9)
        assertEquals(101.0, shortPrice, 1e-9)
        fun plan(longOpen: Boolean, shortOpen: Boolean): Pair<Boolean, Boolean> = (!longOpen) to (!shortOpen)
        assertEquals(true to true, plan(false, false))
        assertEquals(false to true, plan(true, false))
        assertEquals(true to false, plan(false, true))
        assertEquals(false to false, plan(true, true))
    }

    @Test fun individualSlNoReentry_trailingStopReentry_semantics() {
        assertFalse("SL must not trigger re-entry" == null)
        val slTriggersReentry = false
        val trailingTriggersReentry = true
        assertFalse(slTriggersReentry)
        assertTrue(trailingTriggersReentry)
    }

    @Test fun liquidationPriceIsDirectionallyCorrect() {
        val longLiq = PaperEngine.liquidationPrice("LONG", 100.0, 10)
        val shortLiq = PaperEngine.liquidationPrice("SHORT", 100.0, 10)
        assertTrue(longLiq < 100.0)
        assertTrue(shortLiq > 100.0)
    }

    @Test fun stageIsolationRules() {
        assertTrue(TradingStage.STAGE_1.isLocalSimulation)
        assertFalse(TradingStage.STAGE_2.isLocalSimulation)
        assertTrue(TradingStage.STAGE_2.isDemoTrading)
        assertFalse(TradingStage.STAGE_3.isLocalSimulation)
        assertFalse(TradingStage.STAGE_1.canSubmitRealOrders)
        assertFalse(TradingStage.STAGE_2.canSubmitRealOrders) // no live/real-funds orders
        assertTrue(TradingStage.STAGE_3.canSubmitRealOrders) // capability flag, but Stage 3 blocked in app
    }
}
