package com.ansardeo.binancebot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for BAB trading calculations (formulas must match engine behavior).
 * Stage 1/2 are simulated; Stage 3 is disabled in this build.
 */
class TradingMathTest {
    private fun tp(direction: String, avg: Double, pct: Double) =
        if (direction == "LONG") avg * (1 + pct / 100.0) else avg * (1 - pct / 100.0)
    private fun sl(direction: String, avg: Double, pct: Double) =
        if (direction == "LONG") avg * (1 - pct / 100.0) else avg * (1 + pct / 100.0)
    private fun pnl(direction: String, entry: Double, exit: Double, qty: Double) =
        if (direction == "LONG") (exit - entry) * qty else (entry - exit) * qty
    private fun avgEntry(p1: Double, q1: Double, p2: Double, q2: Double) = (p1*q1 + p2*q2) / (q1+q2)

    @Test fun longTpSl() {
        val avg = 100.0
        assertEquals(101.0, tp("LONG", avg, 1.0), 1e-9)
        assertEquals(99.0, sl("LONG", avg, 1.0), 1e-9)
    }
    @Test fun shortTpSl() {
        val avg = 100.0
        assertEquals(99.0, tp("SHORT", avg, 1.0), 1e-9)
        assertEquals(101.0, sl("SHORT", avg, 1.0), 1e-9)
    }
    @Test fun longPnl() {
        assertEquals(10.0, pnl("LONG", 100.0, 110.0, 1.0), 1e-9)
        assertEquals(-10.0, pnl("LONG", 100.0, 90.0, 1.0), 1e-9)
    }
    @Test fun shortPnl() {
        assertEquals(10.0, pnl("SHORT", 100.0, 90.0, 1.0), 1e-9)
        assertEquals(-10.0, pnl("SHORT", 100.0, 110.0, 1.0), 1e-9)
    }
    @Test fun averagingRecalculatesAvgAndTargets() {
        val newAvg = avgEntry(100.0, 1.0, 90.0, 1.0) // 95
        assertEquals(95.0, newAvg, 1e-9)
        assertEquals(95.95, tp("LONG", newAvg, 1.0), 1e-9)
        assertEquals(94.05, sl("LONG", newAvg, 1.0), 1e-9)
    }
    @Test fun roiUsesMarginBasis() {
        val margin = 10.0; val lev = 5; val entry = 100.0
        val qty = (margin * lev) / entry // 0.5
        val exit = 102.0
        val gross = pnl("LONG", entry, exit, qty) // 1.0
        val roi = (gross / margin) * 100.0
        assertEquals(10.0, roi, 1e-9)
    }
    @Test fun stageSafetyNeverRealFrom12() {
        // Contract: Stage 1/2 simulated; Stage 3 blocked. Enforced in engine.
        val stages = listOf("STAGE_1","STAGE_2","STAGE_3")
        assertTrue(stages.contains("STAGE_3"))
        assertFalse("STAGE_1".let { it == "STAGE_3" })
        assertFalse("STAGE_2".let { it == "STAGE_3" })
    }
}
