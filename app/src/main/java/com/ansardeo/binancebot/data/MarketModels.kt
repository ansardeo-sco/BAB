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
