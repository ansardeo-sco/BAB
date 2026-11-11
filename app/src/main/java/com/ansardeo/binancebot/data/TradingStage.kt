package com.ansardeo.binancebot.data

/**
 * BAB trading stages - strictly isolated. Data must never mix across stages.
 */
enum class TradingStage(val id: String, val title: String, val shortLabel: String) {
    STAGE_1("STAGE_1", "Stage 1 - Live Market + Virtual Balance", "Stage 1"),
    STAGE_2("STAGE_2", "Stage 2 - Demo Trading", "Stage 2"),
    STAGE_3("STAGE_3", "Stage 3 - Live Trading", "Stage 3");

    val isSimulated: Boolean get() = this == STAGE_1 || this == STAGE_2
    val usesLiveMarketPrices: Boolean get() = this == STAGE_1 || this == STAGE_3 // Stage 2 demo can also show live; simulated execution only
    val canSubmitRealOrders: Boolean get() = this == STAGE_3

    companion object {
        fun fromId(id: String?): TradingStage = entries.firstOrNull { it.id == id } ?: STAGE_1
    }
}
