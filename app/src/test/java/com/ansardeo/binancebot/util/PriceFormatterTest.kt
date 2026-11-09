package com.ansardeo.binancebot.util

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PriceFormatterTest {

    private fun bd(s: String) = BigDecimal(s)
    // Mirror PriceFormatter: Double -> BigDecimal.valueOf -> setScale(HALF_UP) -> DecimalFormat trim.
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

    @Test
    fun dynamicDecimalPrecision_examples() {
        // Rules: 1 int digit (0-9)->7, 2->6, 3->5, 4->4, 5->3, 6+->2
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

    @Test
    fun dynamicDecimalPrecision_boundaries() {
        assertEquals(expectFromDouble("99.999999", 6), fmt("99.999999"))
        assertEquals(expectFromDouble("100.1234567", 5), fmt("100.1234567"))
        assertEquals(expectFromDouble("999.1234567", 5), fmt("999.1234567"))
        assertEquals(expectFromDouble("1000.123456", 4), fmt("1000.123456"))
        assertEquals(expectFromDouble("9999.123456", 4), fmt("9999.123456"))
        assertEquals(expectFromDouble("10000.12345", 3), fmt("10000.12345"))
        assertEquals(expectFromDouble("99999.12345", 3), fmt("99999.12345"))
        assertEquals(expectFromDouble("100000.12345", 2), fmt("100000.12345"))
    }

    @Test
    fun handlesSpecialValues() {
        assertEquals("0", PriceFormatter.formatPrice(0.0))
        assertEquals("--", PriceFormatter.formatPrice(null))
        assertEquals("--", PriceFormatter.formatPrice(Double.NaN))
        assertEquals("--", PriceFormatter.formatPrice(Double.POSITIVE_INFINITY))
        assertEquals("--", PriceFormatter.formatPrice(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun neverUsesScientificNotation() {
        val cases = listOf(0.000000123456789, 0.123456789, 1.23456789, 1234567.89, 99999999.12345, -0.0000012345, -12345.6789)
        for (v in cases) {
            val out = PriceFormatter.formatPrice(v)
            assertFalse("scientific notation in $out for $v", out.contains("E", ignoreCase = true))
            assertTrue("should be valid for finite $v", out != "--")
        }
    }

    @Test
    fun handlesSmallAndNegative() {
        val small = PriceFormatter.formatPrice(0.0000001)
        assertFalse(small.contains("E", ignoreCase = true))
        assertEquals(expectFromDouble("0.0000001", 7), small)
        assertEquals(expectFromDouble("-12.12345678", 6), PriceFormatter.formatPrice(-12.12345678))
    }
}
