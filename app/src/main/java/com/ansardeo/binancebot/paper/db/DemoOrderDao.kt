package com.ansardeo.binancebot.paper.db

import androidx.room.*

@Dao
interface DemoOrderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(order: DemoOrderEntity): Long

    @Update
    suspend fun update(order: DemoOrderEntity)

    @Query("SELECT * FROM demo_orders WHERE clientOrderId = :clientOrderId LIMIT 1")
    suspend fun getByClientOrderId(clientOrderId: String): DemoOrderEntity?

    @Query("SELECT * FROM demo_orders WHERE exchangeOrderId = :exchangeOrderId LIMIT 1")
    suspend fun getByExchangeOrderId(exchangeOrderId: String): DemoOrderEntity?

    @Query("SELECT * FROM demo_orders WHERE stage = :stage ORDER BY createdAt DESC")
    suspend fun getAll(stage: String): List<DemoOrderEntity>

    @Query("SELECT * FROM demo_orders WHERE stage = :stage AND status IN ('PENDING','NEW','PARTIALLY_FILLED','UNKNOWN') ORDER BY createdAt ASC")
    suspend fun getOpen(stage: String): List<DemoOrderEntity>

    @Query("SELECT * FROM demo_orders WHERE stage = :stage AND symbol = :symbol ORDER BY createdAt DESC")
    suspend fun getBySymbol(stage: String, symbol: String): List<DemoOrderEntity>

    @Query("UPDATE demo_orders SET status = :status, exchangeOrderId = COALESCE(:exchangeOrderId, exchangeOrderId), updatedAt = :now, lastError = :error WHERE clientOrderId = :clientOrderId")
    suspend fun updateStatus(clientOrderId: String, status: String, exchangeOrderId: String?, error: String?, now: Long)
}