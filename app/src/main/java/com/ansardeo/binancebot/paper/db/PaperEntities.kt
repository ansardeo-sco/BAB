package com.ansardeo.binancebot.paper.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stage_wallets",
    indices = [Index(value = ["stage"], unique = true)]
)
data class PaperWalletEntity(
    @PrimaryKey val stage: String = "STAGE_1",
    val startingBalance: Double = 100.0,
    val availableBalance: Double = 100.0,
    val reservedMargin: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "trading_cycles",
    indices = [
        Index("stage"), Index("status"), Index("symbol"),
        Index(value = ["stage", "symbol", "direction", "status"]),
        Index("sourceCycleId")
    ]
)
data class PaperCycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val symbol: String,
    val direction: String, // LONG / SHORT
    val status: String, // PENDING_ENTRY, ACTIVE, CLOSING, CLOSED, ERROR, CANCELLED
    val leverage: Int,
    val initialMargin: Double,
    val averagingMargin: Double,
    val oppositeMargin: Double = 0.0,
    val tpPercent: Double,
    val slPercent: Double,
    val averagingPercent: Double,
    val reentryPercent: Double,
    val averagingEnabled: Boolean,
    val reentryEnabled: Boolean,
    val oppositeEnabled: Boolean = false,
    val averageEntryPrice: Double? = null,
    val totalQuantity: Double = 0.0,
    val totalRemainingQuantity: Double = 0.0,
    val takeProfitPrice: Double? = null,
    val cycleTpOrderId: Long? = null,
    val averagingDone: Boolean = false,
    val oppositeDone: Boolean = false,
    val closePrice: Double? = null,
    val realizedPnl: Double? = null,
    val closeReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val sourceCycleId: Long? = null,
    val sourceType: String? = null, // MANUAL, AVERAGING_OPPOSITE, REENTRY
    val lastProcessedMark: Double? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "trading_entries",
    indices = [
        Index("stage"), Index("cycleId"), Index("status"), Index("symbol"),
        Index(value = ["cycleId", "entryType", "status"]),
        Index(value = ["clientOrderId"], unique = true)
    ]
)
data class PaperEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long,
    val symbol: String,
    val direction: String, // LONG / SHORT
    val entryType: String, // INITIAL, AVERAGING, OPPOSITE, REENTRY
    val status: String, // PENDING, FILLED, CLOSED, CANCELLED, REJECTED
    val margin: Double,
    val leverage: Int,
    val intendedPrice: Double,
    val fillPrice: Double? = null,
    val quantity: Double = 0.0,
    val filledQuantity: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val fee: Double = 0.0,
    val slPrice: Double? = null,
    val slOrderId: Long? = null,
    val slFixed: Boolean = false,
    val clientOrderId: String,
    val fillEventId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val filledAt: Long? = null,
    val closedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val closeReason: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "trading_orders",
    indices = [
        Index("stage"), Index("status"), Index("symbol"), Index("cycleId"), Index("entryId"),
        Index(value = ["stage", "cycleId", "orderType", "status"]),
        Index(value = ["clientOrderId"], unique = true)
    ]
)
data class PaperOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long,
    val entryId: Long? = null, // null for cycle-level TP
    val symbol: String,
    val direction: String,
    val side: String, // BUY/SELL (execution side)
    val orderType: String, // ENTRY_LIMIT, CYCLE_TP, ENTRY_SL
    val triggerPrice: Double,
    val limitPrice: Double? = null,
    val quantity: Double,
    val status: String, // ACTIVE, FILLED, CANCELLING, CANCELLED, CANCEL_VERIFIED, REJECTED, FAILED
    val clientOrderId: String,
    val exchangeOrderId: String? = null,
    val replaceOfOrderId: Long? = null,
    val filledPrice: Double? = null,
    val filledQuantity: Double = 0.0,
    val cancelRequestedAt: Long? = null,
    val cancelVerifiedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "history_closes",
    indices = [
        Index("stage"), Index("cycleId"), Index("symbol"), Index("exitTimestamp"),
        Index(value = ["idempotencyKey"], unique = true)
    ]
)
data class PaperHistoryCloseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long,
    val entryId: Long,
    val symbol: String,
    val direction: String,
    val entryFillPrice: Double,
    val exitFillPrice: Double,
    val entryMargin: Double,
    val leverage: Int,
    val closedQuantity: Double,
    val realizedPnlUsdt: Double,
    val realizedPnlPercent: Double,
    val closingReason: String, // TP, SL, LIQUIDATION
    val entryTimestamp: Long,
    val exitTimestamp: Long,
    val idempotencyKey: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "trading_events",
    indices = [Index("stage"), Index("cycleId"), Index("createdAt"), Index("dedupeKey")]
)
data class PaperEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long?,
    val symbol: String?,
    val type: String,
    val message: String,
    val price: Double? = null,
    val dedupeKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "reentry_plans",
    indices = [
        Index("stage"), Index("status"), Index("symbol"),
        Index(value = ["stage", "sourceCycleId", "status"]),
        Index(value = ["clientPlanId"], unique = true)
    ]
)
data class PaperReentryPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val sourceCycleId: Long,
    val symbol: String,
    val direction: String,
    val leverage: Int,
    val margin: Double,
    val referenceClosePrice: Double,
    val triggerPrice: Double,
    val status: String, // WAITING, TRIGGERED, ORDER_PLACED, EXECUTED, CANCELLED, FAILED
    val newCycleId: Long? = null,
    val entryOrderId: Long? = null,
    val clientPlanId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val triggeredAt: Long? = null,
    val executedAt: Long? = null,
    val lastError: String? = null,
)

data class PaperCycleWithEntries(
    @androidx.room.Embedded val cycle: PaperCycleEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "cycleId", entity = PaperEntryEntity::class)
    val entries: List<PaperEntryEntity>
)
