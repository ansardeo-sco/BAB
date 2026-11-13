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
     * Futures UI display formatting for cryptocurrency prices (display only; never use for calculations).
     * Always shows exactly 5 decimal places, never scientific notation. Returns "--" for null/NaN/Infinity.
     * Do not use for actual order submission prices (use raw/precise values to preserve tick-size precision).
     */
    fun formatPrice(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        return try {
            String.format(Locale.US, "%.5f", value)
        } catch (_: Exception) {
            "--"
        }
    }

    /**
     * Precise plain-string price for order/input prefills (not rounded to 5dp for display).
     * Keeps actual numeric precision intent; never scientific notation.
     */
    fun formatPriceInput(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return ""
        return try {
            val bd = BigDecimal.valueOf(value).stripTrailingZeros()
            val out = bd.toPlainString()
            if (out == "-0") "0" else out
        } catch (_: Exception) {
            value.toString()
        }
    }

    /** ROI/display percent: exactly 2 decimal places (e.g. +1.23%, -4.56%). */
    fun formatPercent(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.2f", value)}%"
    }

    fun formatUsdt(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "-- USDT"
        return "${String.format(Locale.US, "%,.2f", value)} USDT"
    }

    /** Unrealized/realized PNL display: exactly 2 decimal places (e.g. +12.35 USDT). */
    fun formatPnl(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "--"
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.2f", value)} USDT"
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
