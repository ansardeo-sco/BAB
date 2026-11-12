package com.ansardeo.binancebot.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class ExchangeRulesTest {
    private fun rules(
        tick: String = "0.10",
        step: String = "0.001",
        minQty: String = "0.001",
        maxQty: String = "1000",
        minNotional: String = "5"
    ) = SymbolFilterRules(
        symbol = "BTCUSDT",
        tickSize = BigDecimal(tick),
        stepSize = BigDecimal(step),
        minQty = BigDecimal(minQty),
        maxQty = BigDecimal(maxQty),
        minNotional = BigDecimal(minNotional)
    )

    @Test fun roundsPriceToTickSizeDown() {
        val r = rules(tick = "0.10")
        assertEquals(100.10, r.roundPrice(100.19, RoundingMode.DOWN)!!, 1e-9)
        assertEquals(100.20, r.roundPrice(100.19, RoundingMode.UP)!!, 1e-9)
    }

    @Test fun roundsQtyToStepSizeDown() {
        val r = rules(step = "0.001")
        assertEquals(0.123, r.roundQty(0.1239, RoundingMode.DOWN)!!, 1e-9)
    }

    @Test fun validatesTickAndStepConformance() {
        val r = rules(tick = "0.5", step = "0.01")
        assertNull(r.validatePrice(100.5))
        assertNotNull(r.validatePrice(100.3))
        assertNull(r.validateQty(1.23))
        assertNotNull(r.validateQty(1.234))
    }

    @Test fun validatesMinQtyMaxQtyAndNotional() {
        val r = rules(step = "0.001", minQty = "0.01", maxQty = "10", minNotional = "5")
        assertNotNull(r.validateQty(0.005))
        assertNotNull(r.validateQty(11.0))
        assertNull(r.validateQty(0.05))
        assertNotNull(r.validateNotional(100.0, 0.01)) // 1 < 5
        assertNull(r.validateNotional(100.0, 0.1)) // 10 >= 5
    }

    @Test fun doesNotAssumeFixedDecimalPrecision() {
        val r1 = rules(tick = "0.00001", step = "0.1")
        val r2 = rules(tick = "1", step = "0.000001")
        assertEquals(0.12345, r1.roundPrice(0.123459, RoundingMode.DOWN)!!, 1e-12)
        assertEquals(123.0, r2.roundPrice(123.9, RoundingMode.DOWN)!!, 1e-12)
        assertNull(r1.validatePrice(0.12345))
        assertNull(r2.validatePrice(123.0))
    }
}
