package com.ansardeo.binancebot.paper.db

import androidx.room.*

@Dao
interface PaperDao {
    @Query("SELECT * FROM stage_wallets WHERE stage = :stage LIMIT 1")
    suspend fun getWallet(stage: String): PaperWalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWallet(wallet: PaperWalletEntity)

    @Query("SELECT * FROM trading_cycles WHERE stage = :stage AND status IN ('PENDING_ENTRY','ACTIVE','CLOSING') ORDER BY createdAt DESC")
    suspend fun getActiveCycles(stage: String): List<PaperCycleEntity>

    @Query("SELECT * FROM trading_cycles WHERE stage = :stage ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentCycles(stage: String, limit: Int = 200): List<PaperCycleEntity>

    @Query("SELECT * FROM trading_cycles WHERE stage = :stage AND status = 'CLOSED' ORDER BY COALESCE(closedAt, updatedAt) DESC LIMIT :limit")
    suspend fun getClosedCycles(stage: String, limit: Int = 200): List<PaperCycleEntity>

    @Query("SELECT * FROM trading_cycles WHERE id = :id LIMIT 1")
    suspend fun getCycle(id: Long): PaperCycleEntity?

    @Transaction
    @Query("SELECT * FROM trading_cycles WHERE id = :id LIMIT 1")
    suspend fun getCycleWithEntries(id: Long): PaperCycleWithEntries?

    @Transaction
    @Query("SELECT * FROM trading_cycles WHERE stage = :stage AND status IN ('PENDING_ENTRY','ACTIVE','CLOSING') ORDER BY createdAt DESC")
    suspend fun getActiveCyclesWithEntries(stage: String): List<PaperCycleWithEntries>

    @Insert
    suspend fun insertCycle(cycle: PaperCycleEntity): Long

    @Update
    suspend fun updateCycle(cycle: PaperCycleEntity)

    @Query("SELECT * FROM trading_entries WHERE cycleId = :cycleId ORDER BY id ASC")
    suspend fun getEntries(cycleId: Long): List<PaperEntryEntity>

    @Query("SELECT * FROM trading_entries WHERE id = :id LIMIT 1")
    suspend fun getEntry(id: Long): PaperEntryEntity?

    @Query("SELECT * FROM trading_entries WHERE cycleId = :cycleId AND status = 'FILLED' AND remainingQuantity > 0.000000001 ORDER BY id ASC")
    suspend fun getOpenFilledEntries(cycleId: Long): List<PaperEntryEntity>

    @Query("SELECT * FROM trading_entries WHERE stage = :stage AND status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingEntries(stage: String): List<PaperEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: PaperEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIgnore(entry: PaperEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: PaperEntryEntity)

    @Query("SELECT * FROM trading_orders WHERE stage = :stage AND status = 'ACTIVE' ORDER BY createdAt DESC")
    suspend fun getActiveOrders(stage: String): List<PaperOrderEntity>

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND status = 'ACTIVE'")
    suspend fun getActiveOrdersForCycle(cycleId: Long): List<PaperOrderEntity>

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND status IN ('ACTIVE','CANCELLING')")
    suspend fun getLiveOrdersForCycle(cycleId: Long): List<PaperOrderEntity>

    @Query("SELECT * FROM trading_orders WHERE id = :id LIMIT 1")
    suspend fun getOrder(id: Long): PaperOrderEntity?

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND entryId = :entryId AND orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING') LIMIT 1")
    suspend fun getActiveSlForEntry(cycleId: Long, entryId: Long): PaperOrderEntity?

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND orderType = 'CYCLE_TP' AND status IN ('ACTIVE','CANCELLING') LIMIT 1")
    suspend fun getActiveTpForCycle(cycleId: Long): PaperOrderEntity?

    @Query("SELECT * FROM trading_orders WHERE cycleId = :cycleId AND orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING')")
    suspend fun getActiveSlOrdersForCycle(cycleId: Long): List<PaperOrderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrder(order: PaperOrderEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrderIgnore(order: PaperOrderEntity): Long

    @Update
    suspend fun updateOrder(order: PaperOrderEntity)

    @Query("UPDATE trading_orders SET status = :status, updatedAt = :updatedAt, cancelRequestedAt = COALESCE(cancelRequestedAt, :updatedAt) WHERE cycleId = :cycleId AND orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING')")
    suspend fun requestCancelAllSlForCycle(cycleId: Long, status: String, updatedAt: Long)

    @Query("UPDATE trading_orders SET status = 'CANCEL_VERIFIED', cancelVerifiedAt = :now, updatedAt = :now, lastError = NULL WHERE cycleId = :cycleId AND orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING','CANCELLED')")
    suspend fun verifyCancelAllSlForCycle(cycleId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM trading_orders WHERE cycleId = :cycleId AND orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING')")
    suspend fun countUnverifiedActiveSlForCycle(cycleId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryClose(history: PaperHistoryCloseEntity): Long

    @Query("SELECT * FROM history_closes WHERE stage = :stage ORDER BY exitTimestamp DESC, id DESC LIMIT :limit")
    suspend fun getHistoryCloses(stage: String, limit: Int = 500): List<PaperHistoryCloseEntity>

    @Query("SELECT COUNT(*) FROM history_closes WHERE stage = :stage")
    suspend fun countHistoryCloses(stage: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: PaperEventEntity): Long

    @Query("SELECT * FROM trading_events WHERE stage = :stage ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getEvents(stage: String, limit: Int = 150): List<PaperEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReentryPlan(plan: PaperReentryPlanEntity): Long

    @Update
    suspend fun updateReentryPlan(plan: PaperReentryPlanEntity)

    @Query("SELECT * FROM reentry_plans WHERE stage = :stage AND status IN ('WAITING','TRIGGERED','ORDER_PLACED') ORDER BY createdAt ASC")
    suspend fun getPendingReentryPlans(stage: String): List<PaperReentryPlanEntity>

    @Query("SELECT * FROM reentry_plans WHERE id = :id LIMIT 1")
    suspend fun getReentryPlan(id: Long): PaperReentryPlanEntity?

    @Query("SELECT * FROM reentry_plans WHERE sourceCycleId = :sourceCycleId AND status NOT IN ('CANCELLED','FAILED') LIMIT 1")
    suspend fun getOpenReentryPlanForSource(sourceCycleId: Long): PaperReentryPlanEntity?

    @Query("SELECT COUNT(*) FROM trading_cycles WHERE stage = :stage AND symbol = :symbol AND direction = :direction AND status IN ('PENDING_ENTRY','ACTIVE','CLOSING')")
    suspend fun countOpenCycles(stage: String, symbol: String, direction: String): Int

    @Query("SELECT COUNT(*) FROM trading_cycles WHERE stage = :stage AND symbol = :symbol AND status IN ('PENDING_ENTRY','ACTIVE','CLOSING')")
    suspend fun countOpenCyclesAnyDirection(stage: String, symbol: String): Int
}
