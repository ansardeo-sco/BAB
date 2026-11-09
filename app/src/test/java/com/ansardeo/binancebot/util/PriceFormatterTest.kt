package com.ansardeo.binancebot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PriceFormatterTest {

    @Test
    fun dynamicDecimalPrecision_examples() {
        assertEquals("0.1234568", PriceFormatter.formatPrice(0.123456789))
        assertEquals("1.2345679", PriceFormatter.formatPrice(1.23456789))
        assertEquals("8.1234568", PriceFormatter.formatPrice(8.12345678))
        assertEquals("12.123457", PriceFormatter.formatPrice(12.12345678))
        assertEquals("123.12346", PriceFormatter.formatPrice(123.1234567))
        assertEquals("1234.1235", PriceFormatter.formatPrice(1234.123456))
        assertEquals("12345.123", PriceFormatter.formatPrice(12345.12345))
        assertEquals("75849.123", PriceFormatter.formatPrice(75849.12345))
        assertEquals("125000.12", PriceFormatter.formatPrice(125000.12345))
    }

    @Test
    fun dynamicDecimalPrecision_boundaries() {
        // 2 integer digits, 6 decimals max (use value that reliably rounds to 100 given IEEE 754)
        assertEquals("100", PriceFormatter.formatPrice(99.9999999))
        assertEquals("100.123457", PriceFormatter.formatPrice(100.1234567))
        // 3 integer digits, 5 decimals max
        assertEquals("999.12346", PriceFormatter.formatPrice(999.1234567))
        // 4 integer digits, 4 decimals max
        assertEquals("1000.1235", PriceFormatter.formatPrice(1000.123456))
        assertEquals("9999.1235", PriceFormatter.formatPrice(9999.123456))
        // 5 integer digits, 3 decimals max
        assertEquals("10000.123", PriceFormatter.formatPrice(10000.12345))
        assertEquals("99999.123", PriceFormatter.formatPrice(99999.12345))
        // 6+ integer digits, 2 decimals max
        assertEquals("100000.12", PriceFormatter.formatPrice(100000.12345))
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
        val cases = listOf(
            0.000000123456789,
            0.123456789,
            1.23456789,
            1234567.89,
            99999999.12345,
            -0.0000012345,
            -12345.6789
        )
        for (v in cases) {
            val out = PriceFormatter.formatPrice(v)
            assertFalse("scientific notation in $out for $v", out.contains("E", ignoreCase = true))
            assertEquals("-- sentinel only for invalid", v.isFinite(), out != "--")
        }
    }

    @Test
    fun handlesSmallAndNegative() {
        // small value < 1 => 1 integer digit class => 7 decimals max
        val small = PriceFormatter.formatPrice(0.0000001)
        assertFalse(small.contains("E", ignoreCase = true))
        assertEquals("0.0000001", small)
        // negative preserves sign, precision by abs integer digits
        assertEquals("-12.123457", PriceFormatter.formatPrice(-12.12345678))
    }
}
