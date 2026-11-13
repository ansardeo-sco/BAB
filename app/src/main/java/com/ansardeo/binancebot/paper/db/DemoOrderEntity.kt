package com.ansardeo.binancebot.paper.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted Binance USDⓈ-M Futures Demo order record (Stage 2 only).
 * Tracks exchange order lifecycle for reconciliation and restart recovery.
 * Strategy cycles/entries for Stage 2 are managed separately; this table records
 * the raw exchange orders and their statuses.
 */
@Entity(
    tableName = "demo_orders",
    indices = [
        Index("stage"), Index("status"), Index("symbol"), Index("cycleId"), Index("entryId"),
        Index(value = ["clientOrderId"], unique = true),
        Index(value = ["exchangeOrderId"])
    ]
)
data class DemoOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_2",
    val cycleId: Long? = null,
    val entryId: Long? = null,
    val symbol: String,
    val direction: String, // LONG / SHORT
    val side: String, // BUY / SELL
    val orderType: String, // ENTRY_LIMIT, ENTRY_SL, ENTRY_TP, ENTRY_TRAILING, CLOSE
    val triggerPrice: Double = 0.0,
    val limitPrice: Double? = null,
    val quantity: Double = 0.0,
    val status: String, // PENDING, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED, UNKNOWN
    val clientOrderId: String,
    val exchangeOrderId: String? = null,
    val filledPrice: Double? = null,
    val filledQuantity: Double = 0.0,
    val reduceOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val filledAt: Long? = null,
    val closedAt: Long? = null,
    val lastError: String? = null,
)