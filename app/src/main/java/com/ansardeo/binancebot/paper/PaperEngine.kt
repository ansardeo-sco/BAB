package com.ansardeo.binancebot.paper

import android.content.Context
import androidx.room.withTransaction
import com.ansardeo.binancebot.data.LeverageConfig
import com.ansardeo.binancebot.data.LocalCoinSettings
import com.ansardeo.binancebot.data.LocalCoinSettingsStore
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.max

/**
 * BAB trading engine (stage-isolated).
 * Phase 1/2: local simulation only, no real Binance orders. Phase 3: guarded/disabled in-app.
 * - All entries are LIMIT orders. Never MARKET.
 * - Fixed Take Profit removed. Every executed entry gets its own trailing stop (activation/callback by leverage).
 * - Every executed entry gets its own independent fixed SL from actual fill; never changed on averaging. SL hit closes only that entry; no re-entry.
 * - Averaging: -60% ROI once per cycle -> cancel pending LONG/SHORT entry orders (not positions), place two LIMIT averaging orders
 *   (LONG trigger-distance, SHORT trigger+distance) without side-open checks. Keep other pending if one executes. No second averaging.
 * - Same-side averaging fill: weighted avg recalculated, previous trailing stop replaced with new trailing stop based on avg (same activation/callback),
 *   original individual SLs/entry records retained.
 * - Trailing stop closure: place re-entry LIMITs per side-open checks (LONG close-dist, SHORT close+dist). Executed re-entry = new initial entry/cycle.
 */
class PaperEngine(private val db: PaperDatabase, val stage: TradingStage = TradingStage.STAGE_1) {
    private val dao: PaperDao = db.dao()
    private val mutex = Mutex()
    private val feeRate = 0.0004
    private val averagingRoiTriggerPercent = 60.0

    data class EngineResult(val ok: Boolean, val message: String, val cycleId: Long? = null, val entryId: Long? = null, val orderId: Long? = null)

    suspend fun getWallet(): PaperWalletEntity = mutex.withLock { db.withTransaction { ensureWallet() } }

    suspend fun hasPendingWork(): Boolean = mutex.withLock {
        db.withTransaction {
            dao.getActiveCycles(stage.id).isNotEmpty() || dao.getPendingReentryPlans(stage.id).isNotEmpty() || dao.getActiveOrders(stage.id).isNotEmpty()
        }
    }

    suspend fun resetDemo(starting: Double = 100.0): EngineResult = mutex.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            dao.upsertWallet(PaperWalletEntity(stage = stage.id, startingBalance = starting, availableBalance = starting, reservedMargin = 0.0, realizedPnl = 0.0, updatedAt = now))
            val msg = when (stage) {
                TradingStage.STAGE_1 -> "Virtual balance reset to $starting USDT (Stage 1, simulated)."
                TradingStage.STAGE_2 -> "Demo wallet state reset in BAB scaffold (Stage 2). Real demo balance requires Binance Demo API sync."
                TradingStage.STAGE_3 -> "Stage 3 is disabled by default; no live balance reset performed."
            }
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = null, symbol = null, type = "RESET", message = msg, dedupeKey = "reset-${stage.id}-$now"))
            EngineResult(stage != TradingStage.STAGE_3, msg)
        }
    }

    suspend fun openInitialLimit(context: Context, symbol: String, direction: String, margin: Double, leverage: Int, intendedLimitPrice: Double): EngineResult {
        if (stage == TradingStage.STAGE_3) return EngineResult(false, "Stage 3 Live Trading is disabled by default. No real order submitted.")
        return mutex.withLock {
            db.withTransaction { placeInitialLimitLocked(context, symbol, direction, margin, leverage, intendedLimitPrice, sourceType = "MANUAL", sourceCycleId = null) }
        }
    }

    suspend fun openInitial(context: Context, symbol: String, direction: String, margin: Double, leverage: Int, price: Double): PaperCycleEntity? {
        val r = openInitialLimit(context, symbol, direction, margin, leverage, intendedLimitPrice = price)
        return r.cycleId?.let { dao.getCycle(it) }
    }

    suspend fun processAll(context: Context, prices: Map<String, Double>) {
        if (prices.isEmpty()) return
        if (stage == TradingStage.STAGE_3) return // guarded; real sync/execution not implemented
        mutex.withLock {
            db.withTransaction {
                val now = System.currentTimeMillis()
                ensureWallet()
                dao.getPendingEntries(stage.id).forEach { entry ->
                    val mark = prices[entry.symbol.uppercase()] ?: return@forEach
                    tryFillPendingEntryLocked(context, entry, mark, now)
                }
                dao.getPendingReentryPlans(stage.id).forEach { plan ->
                    processReentryPlanLocked(context, plan, now)
                }
                dao.getActiveCycles(stage.id).forEach { c ->
                    val mark = prices[c.symbol.uppercase()] ?: return@forEach
                    processActiveCycleLocked(context, c.copy(lastProcessedMark = mark, updatedAt = now), mark, now)
                }
            }
        }
    }

    private suspend fun placeInitialLimitLocked(
        context: Context, symbol: String, direction: String, margin: Double, leverage: Int, intendedLimitPrice: Double,
        sourceType: String, sourceCycleId: Long?,
    ): EngineResult {
        val sym = symbol.uppercase().trim()
        val dir = direction.uppercase().trim()
        require(dir == "LONG" || dir == "SHORT") { "Direction must be LONG or SHORT" }
        val lev = LeverageConfig.requireSupported(leverage)
        require(intendedLimitPrice.isFinite() && intendedLimitPrice > 0.0) { "Intended entry price must be greater than 0" }
        require(margin.isFinite() && margin > 0.0) { "Margin must be greater than 0" }
        val settings = runCatching { LocalCoinSettingsStore.load(context, sym) }.getOrNull()
        val profile = LeverageConfig.requireProfile(lev)
        val now = System.currentTimeMillis()
        val wallet = ensureWallet()
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) return EngineResult(false, "Insufficient simulated balance. Required ~${fmt(required)} USDT.")
        val open = dao.countOpenCycles(stage.id, sym, dir)
        if (open > 0) return EngineResult(false, "An open/pending $dir cycle already exists for $sym in ${stage.shortLabel}.")
        val cycle = PaperCycleEntity(
            stage = stage.id, symbol = sym, direction = dir, status = "PENDING_ENTRY", leverage = lev,
            initialMargin = margin, averagingMargin = settings?.averagingMargin?.takeIf { it > 0 } ?: margin,
            oppositeMargin = settings?.oppositeMargin?.takeIf { it > 0 } ?: margin,
            trailingActivationPercent = profile.trailingActivationPercent, callbackRatePercent = profile.callbackRatePercent,
            newEntryDistancePercent = profile.newEntryDistancePercent, slPercent = profile.slPercent,
            averagingEnabled = settings?.averagingEnabled ?: true, reentryEnabled = settings?.reentryEnabled ?: true, oppositeEnabled = settings?.oppositeEnabled ?: true,
            sourceCycleId = sourceCycleId, sourceType = sourceType, createdAt = now, updatedAt = now
        )
        val cycleId = dao.insertCycle(cycle)
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + margin, updatedAt = now))
        val clientOrderId = "entry-${stage.id}-$cycleId-initial-$now-${UUID.randomUUID()}"
        val entry = PaperEntryEntity(
            stage = stage.id, cycleId = cycleId, symbol = sym, direction = dir, entryType = "INITIAL", status = "PENDING",
            margin = margin, leverage = lev, intendedPrice = intendedLimitPrice, clientOrderId = clientOrderId,
            trailingActivationPercent = profile.trailingActivationPercent, trailingCallbackPercent = profile.callbackRatePercent,
            createdAt = now, updatedAt = now
        )
        val entryId = dao.insertEntry(entry)
        val order = PaperOrderEntity(
            stage = stage.id, cycleId = cycleId, entryId = entryId, symbol = sym, direction = dir,
            side = if (dir == "LONG") "BUY" else "SELL", orderType = "ENTRY_LIMIT", triggerPrice = intendedLimitPrice,
            limitPrice = intendedLimitPrice, quantity = 0.0, status = "ACTIVE", clientOrderId = clientOrderId,
            createdAt = now, updatedAt = now
        )
        val orderId = dao.insertOrder(order)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycleId, symbol = sym, type = "ENTRY_LIMIT_PLACED",
            message = "Placed INITIAL LIMIT $dir for $sym @ ${fmtPx(intendedLimitPrice)} (${lev}x). ${stage.shortLabel} simulated; no real order submitted.",
            price = intendedLimitPrice, dedupeKey = "place-$clientOrderId"))
        return EngineResult(true, "Initial LIMIT entry placed for $sym $dir @ ${fmtPx(intendedLimitPrice)}.", cycleId, entryId, orderId)
    }

    private suspend fun tryFillPendingEntryLocked(context: Context, entry: PaperEntryEntity, mark: Double, now: Long) {
        if (entry.status != "PENDING" || !mark.isFinite() || mark <= 0.0) return
        val trigger = entry.intendedPrice
        val fillable = when (entry.direction) { "LONG" -> mark <= trigger + 1e-12; "SHORT" -> mark >= trigger - 1e-12; else -> false }
        if (!fillable) return
        val cycle = dao.getCycle(entry.cycleId) ?: return
        val order = dao.getActiveOrdersForCycle(entry.cycleId).firstOrNull { it.entryId == entry.id && it.orderType == "ENTRY_LIMIT" }
        val fillPrice = trigger
        val qty = max((entry.margin * entry.leverage) / fillPrice, 0.0)
        val fee = qty * fillPrice * feeRate
        val fillKey = "fill-${entry.clientOrderId}"
        val filledEntry = entry.copy(status = "FILLED", fillPrice = fillPrice, quantity = qty, filledQuantity = qty, remainingQuantity = qty, fee = fee, filledAt = now, updatedAt = now, fillEventId = fillKey)
        dao.updateEntry(filledEntry)
        if (order != null) dao.updateOrder(order.copy(status = "FILLED", filledPrice = fillPrice, filledQuantity = qty, updatedAt = now, executedAt = now))
        val wallet = ensureWallet()
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - max(fee - (entry.margin * entry.leverage * feeRate), 0.0), updatedAt = now))
        applyEntryFilledLocked(context, cycle, filledEntry, now)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_FILLED",
            message = "${entry.entryType} LIMIT filled ${cycle.symbol} ${cycle.direction} @ ${fmtPx(fillPrice)} qty ${fmtQty(qty)}.", price = fillPrice, dedupeKey = fillKey))
    }

    private suspend fun applyEntryFilledLocked(context: Context, cycle: PaperCycleEntity, filledEntry: PaperEntryEntity, now: Long) {
        val fillPrice = filledEntry.fillPrice ?: return
        // Independent fixed SL per entry (from actual fill)
        val slPrice = calcSl(filledEntry.direction, fillPrice, cycle.slPercent)
        val slClientId = "sl-${stage.id}-${cycle.id}-${filledEntry.id}-${UUID.randomUUID()}"
        val slOrder = PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = filledEntry.id, symbol = cycle.symbol, direction = filledEntry.direction,
            side = if (filledEntry.direction == "LONG") "SELL" else "BUY", orderType = "ENTRY_SL", triggerPrice = slPrice, limitPrice = null,
            quantity = filledEntry.remainingQuantity, status = "ACTIVE", clientOrderId = slClientId, createdAt = now, updatedAt = now)
        val slOrderId = dao.insertOrder(slOrder)

        val baseEntry = filledEntry.copy(
            slPrice = slPrice, slOrderId = slOrderId, slFixed = true,
            trailingBasePrice = fillPrice, trailingActivationPercent = cycle.trailingActivationPercent,
            trailingCallbackPercent = cycle.callbackRatePercent, trailingActivated = false,
            trailingExtremePrice = null, trailingStopPrice = null, trailingOrderId = null, updatedAt = now
        )
        dao.updateEntry(baseEntry)

        val allEntries = dao.getEntries(cycle.id).map { if (it.id == baseEntry.id) baseEntry else it }
        val openFilled = allEntries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
        val totalQty = openFilled.sumOf { it.remainingQuantity }
        val avg = if (totalQty > 1e-12) openFilled.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / totalQty else fillPrice

        if (filledEntry.entryType == "AVERAGING") {
            // Replace trailing stop for all open same-side entries based on weighted avg; retain original SLs/records
            openFilled.forEach { e ->
                val updated = (if (e.id == baseEntry.id) baseEntry else e).copy(
                    trailingBasePrice = avg, trailingActivationPercent = cycle.trailingActivationPercent,
                    trailingCallbackPercent = cycle.callbackRatePercent, trailingActivated = false,
                    trailingExtremePrice = null, trailingStopPrice = null, updatedAt = now
                )
                dao.updateEntry(updated)
                updated.trailingOrderId?.let { oid -> dao.getOrder(oid)?.let { o -> if (o.status in setOf("ACTIVE","CANCELLING")) dao.updateOrder(o.copy(status="CANCEL_VERIFIED", cancelVerifiedAt = now, updatedAt = now)) } }
            }
            dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(status = "ACTIVE", averageEntryPrice = avg,
                totalQuantity = allEntries.filter { it.status == "FILLED" || it.status == "CLOSED" }.sumOf { it.filledQuantity },
                totalRemainingQuantity = totalQty, averagingDone = true, oppositeDone = true, updatedAt = now, lastError = null))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_FILLED",
                message = "Averaging filled @ ${fmtPx(fillPrice)}. Weighted avg ${fmtPx(avg)}. Trailing stops replaced based on avg (act ${cycle.trailingActivationPercent}%, cb ${cycle.callbackRatePercent}%). Original SLs retained.",
                price = fillPrice, dedupeKey = "avg-filled-${filledEntry.clientOrderId}"))
            val pendingOther = dao.getEntries(cycle.id).count { it.entryType == "AVERAGING" && it.status == "PENDING" && it.id != filledEntry.id }
            if (pendingOther > 0) dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_PENDING_KEPT",
                message = "One averaging order executed; other averaging order remains pending.", dedupeKey = "avg-keep-${cycle.id}-$now"))
        } else {
            dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(status = "ACTIVE", averageEntryPrice = avg,
                totalQuantity = allEntries.filter { it.status == "FILLED" || it.status == "CLOSED" }.sumOf { it.filledQuantity },
                totalRemainingQuantity = totalQty, updatedAt = now, lastError = null))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_ACTIVE",
                message = "${filledEntry.entryType} active @ ${fmtPx(fillPrice)}. Independent SL ${fmtPx(slPrice)}. Trailing armed (act ${cycle.trailingActivationPercent}%, cb ${cycle.callbackRatePercent}%).",
                price = fillPrice, dedupeKey = "active-${filledEntry.clientOrderId}"))
        }
    }

    private suspend fun processActiveCycleLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        if (!mark.isFinite() || mark <= 0.0) return
        if (cycle.status !in setOf("ACTIVE", "CLOSING")) return

        maybeTriggerAveragingAtRoiLocked(context, dao.getCycle(cycle.id) ?: cycle, mark, now)

        // Liquidation (per entry)
        for (entry in dao.getOpenFilledEntries(cycle.id)) {
            val liq = liquidationPrice(entry.direction, entry.fillPrice ?: continue, entry.leverage)
            val hitLiq = if (entry.direction == "LONG") mark <= liq else mark >= liq
            if (hitLiq) closeSingleEntryLocked(context, dao.getCycle(cycle.id) ?: cycle, dao.getEntry(entry.id) ?: entry, liq, "LIQUIDATION", now, "liq-${entry.id}-${entry.filledAt}", triggerReentry = false)
        }
        var current = dao.getCycle(cycle.id) ?: return
        if (dao.getOpenFilledEntries(current.id).isEmpty()) { finalizeIfEmptyLocked(current, mark, now, "LIQUIDATION"); return }

        // Individual SL (no re-entry). SL hit must not auto-cancel other pending averaging orders.
        for (entry in dao.getOpenFilledEntries(current.id)) {
            val sl = entry.slPrice ?: continue
            val hitSl = if (entry.direction == "LONG") mark <= sl else mark >= sl
            if (hitSl) closeSingleEntryLocked(context, dao.getCycle(current.id) ?: current, dao.getEntry(entry.id) ?: entry, sl, "SL", now, "sl-${entry.id}-${entry.slOrderId}", triggerReentry = false)
        }
        current = dao.getCycle(cycle.id) ?: return
        if (dao.getOpenFilledEntries(current.id).isEmpty()) { finalizeIfEmptyLocked(current, mark, now, current.closeReason ?: "COMPLETED"); return }

        // Trailing stop (per entry). Closure triggers re-entry logic.
        for (entry in dao.getOpenFilledEntries(current.id)) {
            val eNow = dao.getEntry(entry.id) ?: continue
            val progressed = progressTrailing(eNow, mark)
            if (progressed.first) dao.updateEntry(progressed.second)
            val e2 = progressed.second
            if (isTrailingStopHit(e2, mark)) {
                val exit = e2.trailingStopPrice ?: mark
                closeSingleEntryLocked(context, dao.getCycle(current.id) ?: current, dao.getEntry(entry.id) ?: e2, exit, "TRAILING_STOP", now, "trail-${entry.id}-$now", triggerReentry = true)
            }
        }

        current = dao.getCycle(cycle.id) ?: return
        val remaining = dao.getOpenFilledEntries(current.id).sumOf { it.remainingQuantity }
        val pending = dao.getEntries(current.id).any { it.status == "PENDING" }
        dao.updateCycle(current.copy(totalRemainingQuantity = remaining, averageEntryPrice = weightedAverageOfOpen(current.id), updatedAt = now))
        if (remaining <= 1e-12 && !pending) finalizeIfEmptyLocked(dao.getCycle(current.id) ?: current, mark, now, (dao.getCycle(current.id)?.closeReason ?: "COMPLETED"))
    }

    private suspend fun maybeTriggerAveragingAtRoiLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        if (!cycle.averagingEnabled) return
        if (cycle.averagingTriggeredAtRoi || cycle.averagingOrdersPlaced || cycle.averagingDone) return
        if (cycle.status !in listOf("ACTIVE", "CLOSING")) return
        val open = dao.getOpenFilledEntries(cycle.id)
        if (open.isEmpty()) return
        val qty = open.sumOf { it.remainingQuantity }
        if (qty <= 1e-12) return
        val avg = weightedAverageOfOpen(cycle.id) ?: return
        val unreal = realizedPnl(cycle.direction, avg, mark, qty)
        val marginBasis = open.sumOf { e -> e.margin * (e.remainingQuantity / max(e.filledQuantity, 1e-12)) }
        if (marginBasis <= 1e-12) return
        val roiPct = (unreal / marginBasis) * 100.0
        if (roiPct > -averagingRoiTriggerPercent + 1e-9) return

        // Cancel existing pending LONG and SHORT entry orders (do not close positions)
        dao.getActiveOrders(stage.id).filter { it.orderType == "ENTRY_LIMIT" && it.status in setOf("ACTIVE","CANCELLING") }.forEach { o ->
            dao.updateOrder(o.copy(status = "CANCEL_VERIFIED", cancelVerifiedAt = now, cancelRequestedAt = o.cancelRequestedAt ?: now, updatedAt = now, lastError = "Cancelled by averaging trigger"))
            o.entryId?.let { eid -> dao.getEntry(eid)?.let { e -> if (e.status == "PENDING") dao.updateEntry(e.copy(status = "CANCELLED", closeReason = "AVG_TRIGGER_CANCEL", closedAt = now, updatedAt = now)) } }
        }
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVG_TRIGGER_CANCEL",
            message = "Averaging trigger at ${fmt(roiPct)}% ROI: cancelled existing pending LONG/SHORT entry orders. Positions unchanged.", price = mark, dedupeKey = "avg-trig-cancel-${cycle.id}"))

        val dist = cycle.newEntryDistancePercent / 100.0
        placeAveragingEntryOrderLocked(cycle, "LONG", mark * (1.0 - dist), now)
        placeAveragingEntryOrderLocked(cycle, "SHORT", mark * (1.0 + dist), now)
        dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(averagingTriggeredAtRoi = true, averagingOrdersPlaced = true, updatedAt = now))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVG_TRIGGER_PLACED",
            message = "Averaging orders placed at -60% ROI (once/cycle): LONG ${fmtPx(mark*(1-dist))}, SHORT ${fmtPx(mark*(1+dist))}.", price = mark, dedupeKey = "avg-trig-${cycle.id}"))
    }

    private suspend fun placeAveragingEntryOrderLocked(cycle: PaperCycleEntity, direction: String, price: Double, now: Long) {
        val margin = cycle.averagingMargin
        val lev = cycle.leverage
        val wallet = ensureWallet()
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVG_ORDER_SKIPPED",
                message = "Averaging $direction LIMIT not placed: insufficient simulated balance.", price = price, dedupeKey = "avg-skip-${cycle.id}-$direction-$now"))
            return
        }
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + margin, updatedAt = now))
        val clientId = "avg-${stage.id}-${cycle.id}-$direction-$now-${UUID.randomUUID()}"
        val entry = PaperEntryEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, direction = direction, entryType = "AVERAGING",
            status = "PENDING", margin = margin, leverage = lev, intendedPrice = price, clientOrderId = clientId,
            trailingActivationPercent = cycle.trailingActivationPercent, trailingCallbackPercent = cycle.callbackRatePercent, createdAt = now, updatedAt = now)
        val entryId = dao.insertEntry(entry)
        dao.insertOrder(PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = entryId, symbol = cycle.symbol, direction = direction,
            side = if (direction == "LONG") "BUY" else "SELL", orderType = "ENTRY_LIMIT", triggerPrice = price, limitPrice = price,
            quantity = 0.0, status = "ACTIVE", clientOrderId = clientId, createdAt = now, updatedAt = now))
    }

    private suspend fun closeSingleEntryLocked(context: Context, cycle: PaperCycleEntity, entry: PaperEntryEntity, exitPrice: Double, reason: String, now: Long, idempotencySeed: String, triggerReentry: Boolean) {
        if (entry.status != "FILLED" || entry.remainingQuantity <= 1e-12) return
        val closeQty = entry.remainingQuantity
        val gross = realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, closeQty)
        val feePortion = entry.fee * (closeQty / max(entry.filledQuantity, 1e-12)) / 2.0
        val pnl = gross - feePortion
        val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
        val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
        val idem = "${stage.id}|${cycle.id}|${entry.id}|$reason|$idempotencySeed"
        dao.insertHistoryClose(PaperHistoryCloseEntity(stage = stage.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol, direction = entry.direction,
            entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice, entryMargin = marginPart, leverage = entry.leverage, closedQuantity = closeQty,
            realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = reason, entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem))
        val wallet = ensureWallet()
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + marginPart + pnl, reservedMargin = max(wallet.reservedMargin - marginPart, 0.0), realizedPnl = wallet.realizedPnl + pnl, updatedAt = now))
        entry.slOrderId?.let { slId -> dao.getOrder(slId)?.let { o -> if (o.status in setOf("ACTIVE","CANCELLING")) dao.updateOrder(o.copy(status = "CANCEL_VERIFIED", cancelRequestedAt = o.cancelRequestedAt ?: now, cancelVerifiedAt = now, updatedAt = now)) } }
        entry.trailingOrderId?.let { tid -> dao.getOrder(tid)?.let { o -> if (o.status in setOf("ACTIVE","CANCELLING")) dao.updateOrder(o.copy(status = "CANCEL_VERIFIED", cancelRequestedAt = now, updatedAt = now)) } }
        // IMPORTANT: SL hit must not auto-cancel other pending averaging orders.
        dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = reason, closedAt = now, updatedAt = now))
        val freshCycle = dao.getCycle(cycle.id) ?: cycle
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        val hasPending = dao.getEntries(cycle.id).any { it.status == "PENDING" }
        val closeReasonCycle = if (remaining <= 1e-12 && !hasPending) reason else freshCycle.closeReason
        dao.updateCycle(freshCycle.copy(totalRemainingQuantity = remaining, realizedPnl = (freshCycle.realizedPnl ?: 0.0) + pnl, updatedAt = now, closeReason = closeReasonCycle, averageEntryPrice = weightedAverageOfOpen(cycle.id)))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_CLOSED",
            message = "Entry #${entry.id} ${entry.entryType} ${entry.direction} closed by $reason @ ${fmtPx(exitPrice)}. PNL ${fmt(pnl)} USDT." + if (reason == "SL") " No re-entry." else "",
            price = exitPrice, dedupeKey = "close-$idem"))

        if (triggerReentry && reason == "TRAILING_STOP") {
            createReentryPlansAfterTrailingLocked(context, dao.getCycle(cycle.id) ?: freshCycle, entry.direction, exitPrice, now)
        }
    }

    private suspend fun createReentryPlansAfterTrailingLocked(context: Context, cycle: PaperCycleEntity, closedDirection: String, closePrice: Double, now: Long) {
        if (!cycle.reentryEnabled) return
        val dist = cycle.newEntryDistancePercent / 100.0
        val longTrigger = closePrice * (1.0 - dist)
        val shortTrigger = closePrice * (1.0 + dist)
        // Exclude the source cycle being closed so same-side re-entry is not incorrectly blocked.
        val longOpen = dao.countOpenCyclesExcluding(stage.id, cycle.symbol, "LONG", cycle.id) > 0
        val shortOpen = dao.countOpenCyclesExcluding(stage.id, cycle.symbol, "SHORT", cycle.id) > 0
        if (!longOpen) insertReentryPlanLocked(cycle, "LONG", longTrigger, closePrice, now)
        if (!shortOpen) insertReentryPlanLocked(cycle, "SHORT", shortTrigger, closePrice, now)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "REENTRY_PLANNED",
            message = "Trailing-stop close @ ${fmtPx(closePrice)} ($closedDirection). Re-entry LIMITs: ${if (!longOpen) "LONG ${fmtPx(longTrigger)}" else "LONG skipped(open)"}; ${if (!shortOpen) "SHORT ${fmtPx(shortTrigger)}" else "SHORT skipped(open)"}.",
            price = closePrice, dedupeKey = "re-plan-${cycle.id}-$closedDirection-$now"))
    }

    private suspend fun insertReentryPlanLocked(cycle: PaperCycleEntity, direction: String, trigger: Double, closePrice: Double, now: Long) {
        val existing = dao.getPendingReentryPlans(stage.id).firstOrNull {
            it.sourceCycleId == cycle.id && it.direction == direction && it.symbol == cycle.symbol && it.status in setOf("WAITING", "TRIGGERED", "ORDER_PLACED")
        }
        if (existing != null) return
        val plan = PaperReentryPlanEntity(stage = stage.id, sourceCycleId = cycle.id, symbol = cycle.symbol, direction = direction, leverage = cycle.leverage,
            margin = cycle.initialMargin, referenceClosePrice = closePrice, triggerPrice = trigger, status = "WAITING",
            clientPlanId = "re-${stage.id}-${cycle.id}-$direction-$now-${UUID.randomUUID()}", createdAt = now, updatedAt = now)
        dao.insertReentryPlan(plan)
    }

    private suspend fun processReentryPlanLocked(context: Context, plan: PaperReentryPlanEntity, now: Long) {
        if (plan.status !in setOf("WAITING","TRIGGERED","ORDER_PLACED","FAILED")) return
        val sideOpen = dao.countOpenCycles(stage.id, plan.symbol, plan.direction) > 0
        if (sideOpen) {
            if (plan.status != "CANCELLED") {
                dao.updateReentryPlan(plan.copy(status = "CANCELLED", lastError = "Cancelled: ${plan.direction} side already open", updatedAt = now))
                dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = plan.newCycleId, symbol = plan.symbol, type = "REENTRY_CANCELLED",
                    message = "Re-entry ${plan.direction} cancelled for ${plan.symbol}: side already open.", dedupeKey = "re-cancel-${plan.id}-$now"))
            }
            return
        }
        if (plan.status == "ORDER_PLACED" && plan.newCycleId != null) return
        val res = placeInitialLimitLocked(context, plan.symbol, plan.direction, plan.margin, plan.leverage, plan.triggerPrice, sourceType = "REENTRY", sourceCycleId = plan.sourceCycleId)
        if (res.ok) {
            dao.updateReentryPlan(plan.copy(status = "ORDER_PLACED", newCycleId = res.cycleId, entryOrderId = res.orderId, triggeredAt = plan.triggeredAt ?: now, updatedAt = now, lastError = null))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = res.cycleId, symbol = plan.symbol, type = "REENTRY_PLACED",
                message = "Re-entry LIMIT placed as NEW cycle #${res.cycleId} ${plan.direction} @ ${fmtPx(plan.triggerPrice)} (pending).", price = plan.triggerPrice, dedupeKey = "re-placed-${plan.id}"))
        } else {
            dao.updateReentryPlan(plan.copy(status = "WAITING", lastError = res.message, updatedAt = now))
        }
    }

    private suspend fun finalizeIfEmptyLocked(cycle: PaperCycleEntity, mark: Double, now: Long, reason: String) {
        val pending = dao.getEntries(cycle.id).any { it.status == "PENDING" }
        val openFilled = dao.getOpenFilledEntries(cycle.id).isNotEmpty()
        if (pending || openFilled) return
        if (cycle.status == "CLOSED") return
        dao.getActiveOrdersForCycle(cycle.id).forEach { if (it.status in setOf("ACTIVE","CANCELLING")) dao.updateOrder(it.copy(status = "CANCEL_VERIFIED", cancelVerifiedAt = now, updatedAt = now)) }
        dao.updateCycle(cycle.copy(status = "CLOSED", closeReason = reason, closePrice = mark, closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0, averageEntryPrice = weightedAverageOfOpen(cycle.id)))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "CYCLE_CLOSED",
            message = "Cycle #${cycle.id} closed ($reason).", price = mark, dedupeKey = "cycle-close-${cycle.id}-$reason-$now"))
    }

    private suspend fun weightedAverageOfOpen(cycleId: Long): Double? {
        val open = dao.getOpenFilledEntries(cycleId)
        val qty = open.sumOf { it.remainingQuantity }
        if (qty <= 1e-12) return null
        return open.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / qty
    }

    private suspend fun ensureWallet(): PaperWalletEntity = dao.getWallet(stage.id) ?: PaperWalletEntity(stage = stage.id).also { dao.upsertWallet(it) }

    companion object {
        fun calcSl(direction: String, entry: Double, pct: Double): Double = if (direction == "LONG") entry * (1 - pct / 100.0) else entry * (1 + pct / 100.0)
        fun realizedPnl(direction: String, entry: Double, exit: Double, qty: Double): Double = if (direction == "LONG") (exit - entry) * qty else (entry - exit) * qty
        fun weightedAvg(entries: List<Pair<Double, Double>>): Double {
            val qty = entries.sumOf { it.second }
            if (qty <= 1e-12) return entries.lastOrNull()?.first ?: 0.0
            return entries.sumOf { it.first * it.second } / qty
        }
        fun liquidationPrice(direction: String, entry: Double, leverage: Int): Double {
            val lev = LeverageConfig.requireSupported(leverage)
            val move = (1.0 / lev.toDouble()) * 0.93
            return if (direction == "LONG") entry * (1.0 - move) else entry * (1.0 + move)
        }

        fun progressTrailing(entry: PaperEntryEntity, mark: Double): Pair<Boolean, PaperEntryEntity> {
            if (entry.status != "FILLED" || entry.remainingQuantity <= 1e-12) return false to entry
            val base = entry.trailingBasePrice ?: entry.fillPrice ?: return false to entry
            val act = entry.trailingActivationPercent / 100.0
            val cb = entry.trailingCallbackPercent / 100.0
            if (act <= 0.0 || cb <= 0.0) return false to entry
            var e = entry
            var changed = false
            if (entry.direction == "LONG") {
                val activationPrice = base * (1.0 + act)
                if (!entry.trailingActivated) {
                    if (mark + 1e-9 >= activationPrice) {
                        val extreme = mark
                        e = e.copy(trailingActivated = true, trailingExtremePrice = extreme, trailingStopPrice = extreme * (1.0 - cb))
                        changed = true
                    }
                } else {
                    val extreme = max(entry.trailingExtremePrice ?: mark, mark)
                    val stop = extreme * (1.0 - cb)
                    if (extreme != entry.trailingExtremePrice || stop != entry.trailingStopPrice) { e = e.copy(trailingExtremePrice = extreme, trailingStopPrice = stop); changed = true }
                }
            } else {
                val activationPrice = base * (1.0 - act)
                if (!entry.trailingActivated) {
                    if (mark - 1e-9 <= activationPrice) {
                        val extreme = mark
                        e = e.copy(trailingActivated = true, trailingExtremePrice = extreme, trailingStopPrice = extreme * (1.0 + cb))
                        changed = true
                    }
                } else {
                    val extreme = kotlin.math.min(entry.trailingExtremePrice ?: mark, mark)
                    val stop = extreme * (1.0 + cb)
                    if (extreme != entry.trailingExtremePrice || stop != entry.trailingStopPrice) { e = e.copy(trailingExtremePrice = extreme, trailingStopPrice = stop); changed = true }
                }
            }
            return changed to e
        }

        fun isTrailingStopHit(entry: PaperEntryEntity, mark: Double): Boolean {
            if (!entry.trailingActivated) return false
            val stop = entry.trailingStopPrice ?: return false
            return if (entry.direction == "LONG") mark <= stop + 1e-9 else mark >= stop - 1e-9
        }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
    private fun fmtPx(v: Double) = String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    private fun fmtQty(v: Double) = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
}
