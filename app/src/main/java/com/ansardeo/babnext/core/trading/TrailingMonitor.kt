package com.ansardeo.babnext.core.trading

import com.ansardeo.babnext.core.model.Side

/** Pure trailing decision; the caller must persist the result atomically with cycle updates. */
data class TrailingState(val extreme: Double? = null, val triggered: Boolean = false)

object TrailingMonitor {
    fun observe(
        state: TrailingState,
        price: Double,
        fresh: Boolean,
        weightedAverage: Double,
        leverage: Int,
        side: Side,
        activationRoi: Double,
        callbackRoi: Double
    ): TrailingState {
        if (!fresh || state.triggered) return state
        require(price.isFinite() && price > 0)
        val activation = TrailingStopCalculator.activationPrice(weightedAverage, leverage, side, activationRoi)
        val extreme = state.extreme ?: run {
            val activated = if (side == Side.LONG) price >= activation else price <= activation
            return if (activated) TrailingState(extreme = price) else state
        }
        require(extreme.isFinite() && extreme > 0)
        val nextExtreme = if (side == Side.LONG) maxOf(extreme, price) else minOf(extreme, price)
        val stop = TrailingStopCalculator.stopPrice(nextExtreme, leverage, side, callbackRoi)
        val triggered = if (side == Side.LONG) price <= stop else price >= stop
        return TrailingState(nextExtreme, triggered)
    }
}
