package com.ansardeo.binancebot.util

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PriceFormatterTest {

    private fun bd(s: String) = BigDecimal(s)
    private fun fmt(v: String) = PriceFormatter.formatPrice(bd(v).toDouble())

    @Test fun priceExactlyFiveDecimals_examples() {
        assertEquals("2.12346", fmt("2.12345678"))
        assertEquals("2.10000", fmt("2.1"))
        assertEquals("0.01235", fmt("0.012345678"))
        assertEquals("0.50000", fmt("0.5"))
        assertEquals("123.12346", fmt("123.1234567"))
        assertEquals("75849.12345", fmt("75849.12345"))
    }

    @Test fun handlesSpecialValues() {
        assertEquals("0.00000", PriceFormatter.formatPrice(0.0))
        assertEquals("--", PriceFormatter.formatPrice(null))
        assertEquals("--", PriceFormatter.formatPrice(Double.NaN))
        assertEquals("--", PriceFormatter.formatPrice(Double.POSITIVE_INFINITY))
    }

    @Test fun formatPriceInput_preservesPrecisionForOrders() {
        assertEquals("2.12345678", PriceFormatter.formatPriceInput(2.12345678))
        assertEquals("0.012345678", PriceFormatter.formatPriceInput(0.012345678))
        assertEquals("", PriceFormatter.formatPriceInput(null))
    }

    @Test fun neverUsesScientificNotation() {
        val cases = listOf(0.000000123456789, 0.123456789, 1.23456789, 1234567.89, 99999999.12345, -0.0000012345, -12345.6789)
        for (v in cases) {
            val out = PriceFormatter.formatPrice(v)
            assertFalse("scientific notation in $out for $v", out.contains("E", ignoreCase = true))
            assertTrue(out != "--")
        }
    }

    @Test fun pnlExactlyTwoDecimals() {
        assertEquals("+12.35 USDT", PriceFormatter.formatPnl(12.345))
        assertEquals("-3.21 USDT", PriceFormatter.formatPnl(-3.214))
        assertEquals("0.00 USDT", PriceFormatter.formatPnl(0.0))
        assertEquals("--", PriceFormatter.formatPnl(null))
    }

    @Test fun roiExactlyTwoDecimalsWithPercent() {
        assertEquals("+1.23%", PriceFormatter.formatPercent(1.234))
        assertEquals("-4.56%", PriceFormatter.formatPercent(-4.556))
        assertEquals("0.00%", PriceFormatter.formatPercent(0.0))
    }
}
