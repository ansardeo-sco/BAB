package com.ansardeo.binancebot.data

/**
 * BAB trading stages - strictly isolated. Data must never mix across stages.
 */
enum class TradingStage(val id: String, val title: String, val shortLabel: String) {
    STAGE_1("STAGE_1", "Stage 1 - Live Market + Virtual Balance", "Stage 1"),
    STAGE_2("STAGE_2", "Stage 2 - Binance Futures Demo", "Stage 2"),
    STAGE_3("STAGE_3", "Stage 3 - Live Trading", "Stage 3");

    /** Stage 1 is local simulation. Stage 2 is real Binance USDⓈ-M Futures Demo (API-backed) when configured. */
    val isLocalSimulation: Boolean get() = this == STAGE_1
    val isDemoTrading: Boolean get() = this == STAGE_2
    val usesLiveMarketPrices: Boolean get() = this == STAGE_1 || this == STAGE_2 || this == STAGE_3
    val canSubmitRealOrders: Boolean get() = this == STAGE_3 // Live real funds only (disabled)

    companion object {
        fun fromId(id: String?): TradingStage = entries.firstOrNull { it.id == id } ?: STAGE_1
    }
}
