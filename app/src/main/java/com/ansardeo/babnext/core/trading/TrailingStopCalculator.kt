package com.ansardeo.babnext.core.trading

import com.ansardeo.babnext.core.model.Side

object TrailingStopCalculator {
    // ROI -> price movement %
    fun activationPriceMovementPct(leverage: Int): Double = 33.0 / leverage
    fun callbackPriceMovementPct(leverage: Int): Double = 3.0 / leverage
    fun callbackRate(leverage: Int): Double = 3.0 / leverage

    fun activationPrice(weightedAvgEntry: Double, leverage: Int, side: Side): Double {
        val move = activationPriceMovementPct(leverage) / 100.0
        return if (side == Side.LONG) weightedAvgEntry * (1 + move) else weightedAvgEntry * (1 - move)
    }
}