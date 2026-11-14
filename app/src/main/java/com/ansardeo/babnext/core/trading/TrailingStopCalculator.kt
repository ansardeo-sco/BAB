package com.ansardeo.babnext.core.trading

import com.ansardeo.babnext.core.model.Side

/** All ROI inputs are percentages; price movement is ROI divided by leverage. */
object TrailingStopCalculator {
    fun activationPriceMovementPct(leverage: Int): Double = movementPct(33.0, leverage)
    fun callbackPriceMovementPct(leverage: Int): Double = movementPct(3.0, leverage)
    fun callbackRate(leverage: Int): Double = callbackPriceMovementPct(leverage)

    private fun movementPct(roi: Double, leverage: Int): Double {
        require(roi.isFinite() && roi > 0 && leverage > 0)
        val movement = roi / leverage
        require(movement.isFinite())
        return movement
    }

    fun activationPrice(weightedAvgEntry: Double, leverage: Int, side: Side, activationRoi: Double = 33.0): Double {
        require(weightedAvgEntry.isFinite() && weightedAvgEntry > 0)
        val move = movementPct(activationRoi, leverage) / 100.0
        val result = weightedAvgEntry * if (side == Side.LONG) 1 + move else 1 - move
        require(result.isFinite() && result > 0)
        return result
    }

    fun stopPrice(extreme: Double, leverage: Int, side: Side, callbackRoi: Double = 3.0): Double {
        require(extreme.isFinite() && extreme > 0)
        val move = movementPct(callbackRoi, leverage) / 100.0
        val result = extreme * if (side == Side.LONG) 1 - move else 1 + move
        require(result.isFinite() && result > 0)
        return result
    }

    fun weightedAverage(entries: List<Pair<Double, Double>>): Double {
        require(entries.isNotEmpty())
        require(entries.all { (price, quantity) -> price.isFinite() && price > 0 && quantity.isFinite() && quantity > 0 })
        val total = entries.sumOf { it.second }
        val result = entries.sumOf { (price, quantity) -> price * quantity } / total
        require(total.isFinite() && result.isFinite())
        return result
    }
}
