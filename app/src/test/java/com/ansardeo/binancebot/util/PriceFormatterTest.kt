package com.ansardeo.binancebot.util

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PriceFormatterTest {

    private fun bd(s: String) = BigDecimal(s)
    private fun expectFromDouble(v: String, decimals: Int): String {
        val d = bd(v).toDouble()
        val r = BigDecimal.valueOf(d).setScale(decimals, RoundingMode.HALF_UP)
        val symbols = java.text.DecimalFormatSymbols(java.util.Locale.US).apply { decimalSeparator = '.' }
        val pattern = if (decimals <= 0) "0" else "0." + "#".repeat(decimals)
        val df = java.text.DecimalFormat(pattern, symbols).apply { isGroupingUsed = false; roundingMode = RoundingMode.HALF_UP }
        val out = df.format(r)
        return if (out == "-0" || out == "-0.0") "0" else out
    }
    private fun fmt(v: String) = PriceFormatter.formatPrice(bd(v).toDouble())

    @Test fun dynamicDecimalPrecision_examples() {
        assertEquals(expectFromDouble("0.123456789", 7), fmt("0.123456789"))
        assertEquals(expectFromDouble("1.23456789", 7), fmt("1.23456789"))
        assertEquals(expectFromDouble("8.12345678", 7), fmt("8.12345678"))
        assertEquals(expectFromDouble("12.12345678", 6), fmt("12.12345678"))
        assertEquals(expectFromDouble("123.1234567", 5), fmt("123.1234567"))
        assertEquals(expectFromDouble("1234.123456", 4), fmt("1234.123456"))
        assertEquals(expectFromDouble("12345.12345", 3), fmt("12345.12345"))
        assertEquals(expectFromDouble("75849.12345", 3), fmt("75849.12345"))
        assertEquals(expectFromDouble("125000.12345", 2), fmt("125000.12345"))
    }

    @Test fun handlesSpecialValues() {
        assertEquals("0", PriceFormatter.formatPrice(0.0))
        assertEquals("--", PriceFormatter.formatPrice(null))
        assertEquals("--", PriceFormatter.formatPrice(Double.NaN))
        assertEquals("--", PriceFormatter.formatPrice(Double.POSITIVE_INFINITY))
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
