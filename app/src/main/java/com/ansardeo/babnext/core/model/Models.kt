package com.ansardeo.babnext.core.model

enum class Side { LONG, SHORT }
enum class OrderFamily { REGULAR, ALGO }
enum class OrderType { LIMIT, MARKET, STOP, STOP_MARKET, TAKE_PROFIT, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET }
enum class OrderStatus { NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED, TRIGGERED }
enum class CycleStatus { OPEN, TRAILING_ARMED, CLOSED_TRAILING, CLOSED_MANUAL, CLOSED_LIQUIDATION }
enum class EntryType { INITIAL, AVERAGING, OPPOSITE, REENTRY }
enum class TradingMode { VIRTUAL, DEMO, LIVE }
enum class PositionMode { ONE_WAY, HEDGE }

data class TradingCycle(
    val id: String,
    val symbol: String,
    val side: Side,
    val status: CycleStatus,
    val weightedAvgEntry: Double,
    val totalQty: Double,
    val marginUsed: Double,
    val leverage: Int,
    val activationPrice: Double?,
    val averagingUsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class Entry(
    val id: String,
    val cycleId: String,
    val type: EntryType,
    val clientOrderId: String,
    val exchangeOrderId: String? = null,
    val side: Side,
    val requestedPrice: Double,
    val fillPrice: Double? = null,
    val qty: Double,
    val filledQty: Double = 0.0,
    val margin: Double,
    val leverage: Int,
    val status: OrderStatus = OrderStatus.NEW
)

data class MarketTicker(
    val symbol: String,
    val price: Double,
    val priceChangePercent: Double,
    val volume: Double,
    val high24h: Double,
    val low24h: Double
)

data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)