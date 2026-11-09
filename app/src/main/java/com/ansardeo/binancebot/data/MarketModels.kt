package com.ansardeo.binancebot.data

data class Ticker(
    val symbol: String,
    val lastPrice: Double?,
    val priceChangePercent: Double?,
    val quoteVolume: Double?,
    val markPrice: Double? = null,
    val highPrice: Double? = null,
    val lowPrice: Double? = null,
    val openPrice: Double? = null,
)

data class MarketOverview(
    val status: String,
    val live: Boolean,
    val stale: Boolean,
    val dataAvailable: Boolean,
    val lastUpdate: String?,
    val error: String?,
    val volumeLeaders: List<Ticker>,
    val gainers: List<Ticker>,
    val losers: List<Ticker>,
    val symbols: Map<String, Ticker>,
)

data class MarketStats(
    val symbol: String,
    val livePrice: Double?,
    val change24hPercent: Double?,
    val volume24h: Double?,
    val high24h: Double?,
    val low24h: Double?,
    val rsi24h: Double?,
    val high7d: Double?,
    val low7d: Double?,
    val rsi7d: Double?,
    val rsi24hInterval: String = "1h (RSI 14, Wilder)",
    val rsi7dInterval: String = "4h (RSI 14, Wilder)",
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    fun distanceToHigh24hPct(): Double? = pctDistance(livePrice, high24h)
    fun distanceToLow24hPct(): Double? = pctDistance(livePrice, low24h)
    fun distanceToHigh7dPct(): Double? = pctDistance(livePrice, high7d)
    fun distanceToLow7dPct(): Double? = pctDistance(livePrice, low7d)

    private fun pctDistance(from: Double?, to: Double?): Double? {
        if (from == null || to == null || !from.isFinite() || !to.isFinite() || from == 0.0) return null
        return ((to - from) / from) * 100.0
    }
}
