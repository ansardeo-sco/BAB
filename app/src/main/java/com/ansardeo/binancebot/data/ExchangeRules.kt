package com.ansardeo.binancebot.data

import java.math.BigDecimal
import java.math.RoundingMode

data class SymbolFilterRules(
    val symbol: String,
    val tickSize: BigDecimal? = null,
    val stepSize: BigDecimal? = null,
    val minQty: BigDecimal? = null,
    val maxQty: BigDecimal? = null,
    val minNotional: BigDecimal? = null,
    val pricePrecision: Int? = null,
    val quantityPrecision: Int? = null,
) {
    fun roundPrice(price: Double, mode: RoundingMode = RoundingMode.DOWN): Double? = roundToStep(price, tickSize, mode)
    fun roundQty(qty: Double, mode: RoundingMode = RoundingMode.DOWN): Double? = roundToStep(qty, stepSize, mode)

    /**
     * Phase 1 hardening: validation is NEVER silently skipped. If exchange filters (tickSize/stepSize)
     * are missing or invalid, validation returns a clear error instead of null (pass).
     */
    fun validatePrice(price: Double): String? {
        if (!price.isFinite() || price <= 0.0) return "Invalid price"
        val ts = tickSize ?: return "Exchange price filter (tickSize) unavailable; cannot validate price. Refresh symbol rules."
        if (ts <= BigDecimal.ZERO) return "Invalid tickSize ${ts.toPlainString()}"
        val p = BigDecimal.valueOf(price)
        val rem = p.remainder(ts).abs()
        val ok = rem.compareTo(BigDecimal.ZERO) == 0 || rem.compareTo(ts) == 0
        return if (ok) null else "Price does not conform to tickSize ${ts.toPlainString()}"
    }

    fun validateQty(qty: Double): String? {
        if (!qty.isFinite() || qty <= 0.0) return "Invalid quantity"
        val ss = stepSize ?: return "Exchange quantity filter (stepSize) unavailable; cannot validate quantity. Refresh symbol rules."
        if (ss <= BigDecimal.ZERO) return "Invalid stepSize ${ss.toPlainString()}"
        val q = BigDecimal.valueOf(qty)
        val rem = q.remainder(ss).abs()
        val ok = rem.compareTo(BigDecimal.ZERO) == 0 || rem.compareTo(ss) == 0
        if (!ok) return "Quantity does not conform to stepSize ${ss.toPlainString()}"
        minQty?.let { if (q < it) return "Quantity below minQty ${it.toPlainString()}" }
        maxQty?.let { if (q > it) return "Quantity above maxQty ${it.toPlainString()}" }
        return null
    }

    fun validateNotional(price: Double, qty: Double): String? {
        if (!price.isFinite() || price <= 0.0 || !qty.isFinite() || qty <= 0.0) return "Invalid price/quantity"
        val mn = minNotional ?: return "Exchange notional filter (minNotional) unavailable; cannot validate notional. Refresh symbol rules."
        val notional = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty))
        return if (notional < mn) "Notional below minNotional ${mn.toPlainString()}" else null
    }

    /** True if all required filters are present and usable. */
    fun hasRequiredFilters(): Boolean =
        tickSize != null && tickSize!! > BigDecimal.ZERO &&
            stepSize != null && stepSize!! > BigDecimal.ZERO &&
            minQty != null && maxQty != null && minNotional != null

    private fun roundToStep(value: Double, step: BigDecimal?, mode: RoundingMode): Double? {
        if (!value.isFinite() || value <= 0.0) return null
        val s = step ?: return value
        if (s <= BigDecimal.ZERO) return value
        val v = BigDecimal.valueOf(value)
        val units = v.divide(s, 0, mode)
        return units.multiply(s).stripTrailingZeros().toDouble()
    }
}

data class SymbolRulesResult(val rules: SymbolFilterRules?, val stale: Boolean = false, val error: String? = null)
