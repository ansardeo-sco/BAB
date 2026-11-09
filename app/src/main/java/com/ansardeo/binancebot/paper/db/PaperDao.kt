package com.ansardeo.binancebot.paper.db

import androidx.room.*

@Dao
interface PaperDao {
    @Query("SELECT * FROM stage_wallets WHERE stage = :stage")
    suspend fun getWallet(stage: String): PaperWalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWallet(wallet: PaperWalletEntity)

    @Query("SELECT * FROM trading_cycles WHERE stage = :stage AND status IN ('ACTIVE','CLOSING_TP') ORDER BY createdAt DESC")
    suspend fun getActiveCycles(stage: String): List<PaperCycleEntity>

    @Query("SELECT * FROM trading_cycles WHERE stage = :stage ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentCycles(stage: String, limit: Int = 100): List<PaperCycleEntity>

    @Query("SELECT * FROM trading_cycles WHERE id = :id")
    suspend fun getCycle(id: Long): PaperCycleEntity?

    @Transaction
    @Query("SELECT * FROM trading_cycles WHERE id = :id")
    suspend fun getCycleWithEntries(id: Long): PaperCycleWithEntries?

    @Transaction
    @Query("SELECT * FROM trading_cycles WHERE stage = :stage AND status IN ('ACTIVE','CLOSING_TP') ORDER BY createdAt DESC")
    suspend fun getActiveCyclesWithEntries(stage: String): List<PaperCycleWithEntries>

    @Insert
    suspend fun insertCycle(cycle: PaperCycleEntity): Long

    @Update
    suspend fun updateCycle(cycle: PaperCycleEntity)

    @Query("SELECT * FROM trading_entries WHERE cycleId = :cycleId ORDER BY id ASC")
    suspend fun getEntries(cycleId: Long): List<PaperEntryEntity>

    @Insert
    suspend fun insertEntry(entry: PaperEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: PaperEntryEntity)

    @Insert
    suspend fun insertEvent(event: PaperEventEntity): Long

    @Query("SELECT * FROM trading_events WHERE stage = :stage ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getEvents(stage: String, limit: Int = 100): List<PaperEventEntity>

    @Query("SELECT * FROM trading_reentries WHERE stage = :stage AND status = 'WAITING' ORDER BY createdAt ASC")
    suspend fun getWaitingReentries(stage: String): List<PaperReentryEntity>

    @Insert
    suspend fun insertReentry(reentry: PaperReentryEntity): Long

    @Update
    suspend fun updateReentry(reentry: PaperReentryEntity)

    @Query("SELECT COUNT(*) FROM trading_cycles WHERE stage = :stage AND symbol = :symbol AND status IN ('ACTIVE','CLOSING_TP')")
    suspend fun countActiveForSymbol(stage: String, symbol: String): Int

    @Query("SELECT * FROM trading_orders WHERE stage = :stage AND status = 'ACTIVE' ORDER BY createdAt DESC")
    suspend fun getActiveOrders(stage: String): List<PaperOrderEntity>

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND status = 'ACTIVE'")
    suspend fun getActiveOrdersForCycle(cycleId: Long): List<PaperOrderEntity>

    @Query("SELECT * FROM trading_orders WHERE id = :id")
    suspend fun getOrder(id: Long): PaperOrderEntity?

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND orderType = :orderType AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveOrder(cycleId: Long, orderType: String): PaperOrderEntity?

    @Insert
    suspend fun insertOrder(order: PaperOrderEntity): Long

    @Update
    suspend fun updateOrder(order: PaperOrderEntity)

    @Query("UPDATE trading_orders SET status = :status, updatedAt = :updatedAt WHERE stage = :stage AND cycleId = :cycleId AND status = 'ACTIVE'")
    suspend fun cancelActiveOrdersForCycle(stage: String, cycleId: Long, status: String, updatedAt: Long)

    @Query("SELECT * FROM trading_plans WHERE stage = :stage AND status NOT IN ('ACTIVATED','EXECUTED','CANCELLED') ORDER BY createdAt DESC")
    suspend fun getPipelinePlans(stage: String): List<PaperPlanEntity>

    @Query("SELECT * FROM trading_plans WHERE cycleId = :cycleId ORDER BY createdAt ASC")
    suspend fun getPlansForCycle(cycleId: Long): List<PaperPlanEntity>

    @Query("SELECT * FROM trading_plans WHERE cycleId = :cycleId AND planType = :planType LIMIT 1")
    suspend fun getPlanForCycle(cycleId: Long, planType: String): PaperPlanEntity?

    @Query("SELECT * FROM trading_plans WHERE stage = :stage AND sourceCycleId = :sourceCycleId AND planType = 'REENTRY' AND status NOT IN ('CANCELLED') LIMIT 1")
    suspend fun getReentryPlanForSource(stage: String, sourceCycleId: Long): PaperPlanEntity?

    @Query("SELECT * FROM trading_plans WHERE id = :id")
    suspend fun getPlan(id: Long): PaperPlanEntity?

    @Query("SELECT COUNT(*) FROM trading_plans WHERE stage = :stage AND status NOT IN ('ACTIVATED','EXECUTED','CANCELLED')")
    suspend fun countPendingPlans(stage: String): Int

    @Insert
    suspend fun insertPlan(plan: PaperPlanEntity): Long

    @Update
    suspend fun updatePlan(plan: PaperPlanEntity)

    @Query("SELECT * FROM trading_plans WHERE stage = :stage AND status NOT IN ('ACTIVATED','EXECUTED','CANCELLED')")
    suspend fun getPendingPlans(stage: String): List<PaperPlanEntity>

    @Transaction
    suspend fun replaceActiveProtectiveOrders(stage: String, cycleId: Long, tp: PaperOrderEntity, sl: PaperOrderEntity, now: Long) {
        cancelActiveOrdersForCycle(stage, cycleId, "CANCELLED", now)
        insertOrder(tp)
        insertOrder(sl)
    }
}
