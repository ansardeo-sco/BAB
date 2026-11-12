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

    fun validatePrice(price: Double): String? {
        if (!price.isFinite() || price <= 0.0) return "Invalid price"
        val ts = tickSize ?: return null
        if (ts <= BigDecimal.ZERO) return null
        val p = BigDecimal.valueOf(price)
        val rem = p.remainder(ts).abs()
        val ok = rem.compareTo(BigDecimal.ZERO) == 0 || rem.compareTo(ts) == 0
        return if (ok) null else "Price does not conform to tickSize ${ts.toPlainString()}"
    }

    fun validateQty(qty: Double): String? {
        if (!qty.isFinite() || qty <= 0.0) return "Invalid quantity"
        stepSize?.let { ss ->
            if (ss > BigDecimal.ZERO) {
                val q = BigDecimal.valueOf(qty)
                val rem = q.remainder(ss).abs()
                val ok = rem.compareTo(BigDecimal.ZERO) == 0 || rem.compareTo(ss) == 0
                if (!ok) return "Quantity does not conform to stepSize ${ss.toPlainString()}"
            }
        }
        minQty?.let { if (BigDecimal.valueOf(qty) < it) return "Quantity below minQty ${it.toPlainString()}" }
        maxQty?.let { if (BigDecimal.valueOf(qty) > it) return "Quantity above maxQty ${it.toPlainString()}" }
        return null
    }

    fun validateNotional(price: Double, qty: Double): String? {
        val mn = minNotional ?: return null
        val notional = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty))
        return if (notional < mn) "Notional below minNotional ${mn.toPlainString()}" else null
    }

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
