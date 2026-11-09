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
    val status: String, // ACTIVE, CLOSING_TP, CLOSED, ERROR
    val leverage: Int,
    val initialMargin: Double,
    val averagingMargin: Double,
    val tpPercent: Double,
    val slPercent: Double,
    val averagingPercent: Double,
    val reentryPercent: Double,
    val averagingEnabled: Boolean,
    val reentryEnabled: Boolean,
    val averageEntryPrice: Double? = null,
    val totalQuantity: Double = 0.0,
    val takeProfitPrice: Double? = null,
    val stopLossPrice: Double? = null,
    val averagingDone: Boolean = false,
    val closePrice: Double? = null,
    val realizedPnl: Double? = null,
    val closeReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val warningPercent: Double = 6.0,
    val oppositeEnabled: Boolean = false,
    val oppositePercent: Double = 7.0,
    val oppositeMargin: Double = 0.0,
    val oppositeDone: Boolean = false,
    val sourceCycleId: Long? = null,
    val sourceType: String? = null,
)

@Entity(
    tableName = "trading_entries",
    indices = [Index("stage"), Index("cycleId"), Index("status")]
)
data class PaperEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long,
    val symbol: String,
    val direction: String,
    val entryType: String, // INITIAL, AVERAGING
    val status: String, // FILLED, CLOSED
    val margin: Double,
    val leverage: Int,
    val price: Double,
    val quantity: Double,
    val remainingQuantity: Double,
    val fee: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
)

@Entity(
    tableName = "trading_events",
    indices = [Index("stage"), Index("cycleId"), Index("createdAt")]
)
data class PaperEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long?,
    val symbol: String?,
    val type: String,
    val message: String,
    val price: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "trading_reentries",
    indices = [Index("stage"), Index("status"), Index("symbol")]
)
data class PaperReentryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val sourceCycleId: Long,
    val symbol: String,
    val direction: String,
    val referenceClosePrice: Double,
    val triggerPrice: Double,
    val status: String,
    val newCycleId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val triggeredAt: Long? = null,
)

@Entity(
    tableName = "trading_orders",
    indices = [
        Index("stage"), Index("status"), Index("symbol"), Index("cycleId"),
        Index(value = ["stage", "cycleId", "orderType", "status"])
    ]
)
data class PaperOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long,
    val symbol: String,
    val direction: String,
    val orderType: String, // TAKE_PROFIT, STOP_LOSS, REENTRY
    val triggerPrice: Double,
    val limitPrice: Double? = null,
    val quantity: Double,
    val status: String, // ACTIVE, EXECUTED, CANCELLED, REJECTED
    val sourcePlanId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
)

@Entity(
    tableName = "trading_plans",
    indices = [
        Index("stage"), Index("status"), Index("symbol"), Index("cycleId"), Index("planType"),
        Index(value = ["stage", "sourceCycleId", "planType", "status"])
    ]
)
data class PaperPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stage: String = "STAGE_1",
    val cycleId: Long? = null,
    val sourceCycleId: Long? = null,
    val symbol: String,
    val direction: String,
    val planType: String, // AVERAGING, OPPOSITE_SIDE, REENTRY
    val status: String, // WAITING, WARNING, TRIGGER_REACHED, READY, ACTIVATED, EXECUTED, CANCELLED
    val triggerPercent: Double? = null,
    val warningPercent: Double? = null,
    val referencePrice: Double? = null,
    val triggerPrice: Double? = null,
    val quantity: Double? = null,
    val margin: Double? = null,
    val leverage: Int? = null,
    val activeOrderId: Long? = null,
    val resultCycleId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val activatedAt: Long? = null,
    val executedAt: Long? = null,
)

data class PaperCycleWithEntries(
    @androidx.room.Embedded val cycle: PaperCycleEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "cycleId", entity = PaperEntryEntity::class)
    val entries: List<PaperEntryEntity>
)
