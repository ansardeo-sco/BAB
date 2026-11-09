package com.ansardeo.binancebot.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

object PriceFormatter {
    /**
     * Dynamic display formatting for market/order prices (display only; never use for calculations).
     * Decimal precision is selected by the number of digits in the integer part of |value|:
     * 1 digit (0-9) -> 7 decimals, 2 -> 6, 3 -> 5, 4 -> 4, 5 -> 3, 6+ -> 2.
     * Never uses scientific notation. Locale-independent (period decimal separator).
     * Returns "--" for null, NaN, and Infinity.
     */
    fun formatPrice(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        return try {
            val absVal = abs(value)
            val decimals = decimalsForPrice(absVal)
            val bd = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP)
            formatPlain(bd, decimals)
        } catch (_: Exception) {
            "--"
        }
    }

    internal fun decimalsForPrice(absValue: Double): Int {
        if (!absValue.isFinite() || absValue < 0.0) return 7
        val intDigits = if (absValue < 1.0) 1 else (floor(log10(absValue)).toInt() + 1)
        return when {
            intDigits <= 1 -> 7
            intDigits == 2 -> 6
            intDigits == 3 -> 5
            intDigits == 4 -> 4
            intDigits == 5 -> 3
            else -> 2
        }
    }

    private fun formatPlain(bd: BigDecimal, maxDecimals: Int): String {
        // Format with fixed scale then trim unnecessary trailing zeros (keep at most maxDecimals).
        val symbols = DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        val pattern = if (maxDecimals <= 0) "0" else "0." + "#".repeat(maxDecimals)
        val df = DecimalFormat(pattern, symbols).apply {
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }
        val out = df.format(bd)
        return if (out == "-0" || out == "-0.0") "0" else out
    }

    fun formatPercent(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.2f", value)}%"
    }

    fun formatUsdt(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "-- USDT"
        return "${String.format(Locale.US, "%.4f", value)} USDT"
    }

    fun formatPnl(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.4f", value)} USDT"
    }

    fun formatQty(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        val a = abs(value)
        val s = when {
            a >= 1 -> String.format(Locale.US, "%.6f", value)
            a >= 0.0001 -> String.format(Locale.US, "%.8f", value)
            else -> BigDecimal.valueOf(value).toPlainString()
        }
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
