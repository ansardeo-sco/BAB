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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BAB trading engine (stage-isolated, local simulation for Stage 1/2).
 * - All entries are LIMIT orders (manual, averaging, opposite, re-entry). Never MARKET.
 * - One cycle per symbol/direction (open/pending/active).
 * - Max 1 averaging per cycle. After averaging: TP recalculated from weighted avg of actual filled qtys; existing entry SLs remain fixed.
 * - Every executed entry gets its own independent fixed SL (from actual fill price + leverage SL%).
 * - Individual SL closes only that entry's remaining qty. Other entries remain.
 * - One cycle-level TP closes remaining cycle qty. On cycle TP, cancel + verify all remaining entry SL orders.
 * - Re-entry after TP creates a new cycle (new Cycle ID), after pullback, as LIMIT.
 * - Opposite-side on averaging fill: at averaging fill price, equal margin, only if no opposite-side open position for symbol and opposite enabled.
 * - Stage 1/2 never submit real exchange orders. Stage 3 is disabled/blocked.
 */
class PaperEngine(private val db: PaperDatabase, val stage: TradingStage = TradingStage.STAGE_1) {
    private val dao: PaperDao = db.dao()
    private val mutex = Mutex()
    private val feeRate = 0.0004

    data class OpenLimitRequest(
        val symbol: String,
        val direction: String,
        val intendedLimitPrice: Double,
        val margin: Double,
        val leverage: Int,
        val sourceType: String = "MANUAL",
        val sourceCycleId: Long? = null,
    )

    data class EngineResult(val ok: Boolean, val message: String, val cycleId: Long? = null, val entryId: Long? = null, val orderId: Long? = null)

    suspend fun getWallet(): PaperWalletEntity = mutex.withLock {
        db.withTransaction { ensureWallet() }
    }

    suspend fun hasPendingWork(): Boolean = mutex.withLock {
        db.withTransaction {
            dao.getActiveCycles(stage.id).isNotEmpty() ||
                dao.getPendingReentryPlans(stage.id).isNotEmpty() ||
                dao.getActiveOrders(stage.id).isNotEmpty()
        }
    }

    suspend fun resetDemo(starting: Double = 100.0): EngineResult = mutex.withLock {
        db.withTransaction {
            val now = System.currentTimeMillis()
            dao.upsertWallet(PaperWalletEntity(stage = stage.id, startingBalance = starting, availableBalance = starting, reservedMargin = 0.0, realizedPnl = 0.0, updatedAt = now))
            val msg = if (stage == TradingStage.STAGE_1) "Virtual balance reset to $starting USDT (Stage 1)." else "Demo balance reset to $starting USDT (Stage 2, simulated)."
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = null, symbol = null, type = "RESET", message = msg, dedupeKey = "reset-${stage.id}-$now"))
            EngineResult(true, msg)
        }
    }

    /** Place a manual initial LIMIT entry (never MARKET). */
    suspend fun openInitialLimit(
        context: Context,
        symbol: String,
        direction: String,
        margin: Double,
        leverage: Int,
        intendedLimitPrice: Double,
    ): EngineResult {
        assertSimulatedExecutionAllowed()
        return mutex.withLock {
            db.withTransaction {
                placeInitialLimitLocked(context, symbol, direction, margin, leverage, intendedLimitPrice, sourceType = "MANUAL", sourceCycleId = null)
            }
        }
    }

    /** Legacy-compatible entry point from old UI; treated as LIMIT at provided price (must be user/intended price). */
    suspend fun openInitial(context: Context, symbol: String, direction: String, margin: Double, leverage: Int, price: Double): PaperCycleEntity? {
        val r = openInitialLimit(context, symbol, direction, margin, leverage, intendedLimitPrice = price)
        return r.cycleId?.let { dao.getCycle(it) }
    }

    suspend fun processAll(context: Context, prices: Map<String, Double>) {
        assertSimulatedExecutionAllowed()
        if (prices.isEmpty()) return
        mutex.withLock {
            db.withTransaction {
                val now = System.currentTimeMillis()
                ensureWallet()
                // Pending entry LIMIT orders (initial/averaging/opposite/reentry)
                dao.getPendingEntries(stage.id).forEach { entry ->
                    val mark = prices[entry.symbol.uppercase()] ?: return@forEach
                    tryFillPendingEntryLocked(context, entry, mark, now)
                }
                // Re-entry plans waiting for pullback
                dao.getPendingReentryPlans(stage.id).forEach { plan ->
                    val mark = prices[plan.symbol.uppercase()] ?: return@forEach
                    processReentryPlanLocked(context, plan, mark, now)
                }
                // Active cycles: cycle TP, entry SLs, liquidation
                dao.getActiveCycles(stage.id).forEach { c ->
                    val mark = prices[c.symbol.uppercase()] ?: return@forEach
                    processActiveCycleLocked(context, c.copy(lastProcessedMark = mark, updatedAt = now), mark, now)
                }
            }
        }
    }

    private suspend fun placeInitialLimitLocked(
        context: Context,
        symbol: String,
        direction: String,
        margin: Double,
        leverage: Int,
        intendedLimitPrice: Double,
        sourceType: String,
        sourceCycleId: Long?,
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
            tpPercent = profile.tpPercent, slPercent = profile.slPercent, averagingPercent = profile.averagingPercent, reentryPercent = profile.reentryPullbackPercent,
            averagingEnabled = settings?.averagingEnabled ?: true, reentryEnabled = settings?.reentryEnabled ?: true, oppositeEnabled = settings?.oppositeEnabled ?: true,
            sourceCycleId = sourceCycleId, sourceType = sourceType, createdAt = now, updatedAt = now
        )
        val cycleId = dao.insertCycle(cycle)
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + margin, updatedAt = now))
        val clientOrderId = "entry-${stage.id}-$cycleId-initial-$now-${UUID.randomUUID()}"
        val entry = PaperEntryEntity(
            stage = stage.id, cycleId = cycleId, symbol = sym, direction = dir, entryType = "INITIAL", status = "PENDING",
            margin = margin, leverage = lev, intendedPrice = intendedLimitPrice, clientOrderId = clientOrderId,
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
            message = "Placed INITIAL LIMIT $dir for $sym @ ${fmtPx(intendedLimitPrice)} (margin $margin, ${lev}x). ${stage.shortLabel} simulated only; no real order submitted.",
            price = intendedLimitPrice, dedupeKey = "place-$clientOrderId"))
        return EngineResult(true, "Initial LIMIT entry placed for $sym $dir @ ${fmtPx(intendedLimitPrice)}.", cycleId, entryId, orderId)
    }

    private suspend fun tryFillPendingEntryLocked(context: Context, entry: PaperEntryEntity, mark: Double, now: Long) {
        if (entry.status != "PENDING" || !mark.isFinite() || mark <= 0.0) return
        val trigger = entry.intendedPrice
        val fillable = when (entry.direction) {
            "LONG" -> mark <= trigger + 1e-12
            "SHORT" -> mark >= trigger - 1e-12
            else -> false
        }
        if (!fillable) return
        val order = dao.getActiveOrdersForCycle(entry.cycleId).firstOrNull { it.entryId == entry.id && it.orderType == "ENTRY_LIMIT" }
        val fillPrice = trigger // LIMIT fill at intended price in simulation
        val qty = max((entry.margin * entry.leverage) / fillPrice, 0.0)
        val fee = qty * fillPrice * feeRate
        val fillKey = "fill-${entry.clientOrderId}"
        val filledEntry = entry.copy(status = "FILLED", fillPrice = fillPrice, quantity = qty, filledQuantity = qty, remainingQuantity = qty, fee = fee, filledAt = now, updatedAt = now, fillEventId = fillKey)
        dao.updateEntry(filledEntry)
        if (order != null) dao.updateOrder(order.copy(status = "FILLED", filledPrice = fillPrice, filledQuantity = qty, updatedAt = now, executedAt = now))
        val cycle = dao.getCycle(entry.cycleId) ?: return
        val wallet = ensureWallet()
        // reserved margin already taken at placement; adjust for fee difference approximation
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - max(fee - (entry.margin * entry.leverage * feeRate), 0.0), updatedAt = now))
        applyEntryFilledLocked(context, cycle, filledEntry, now)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_FILLED",
            message = "${entry.entryType} LIMIT filled ${cycle.symbol} ${cycle.direction} @ ${fmtPx(fillPrice)} qty ${fmtQty(qty)}.",
            price = fillPrice, dedupeKey = fillKey))
    }

    private suspend fun applyEntryFilledLocked(context: Context, cycle: PaperCycleEntity, filledEntry: PaperEntryEntity, now: Long) {
        // Create independent fixed SL for this entry (from actual fill)
        val slPrice = calcSl(cycle.direction, filledEntry.fillPrice!!, cycle.slPercent)
        val slClientId = "sl-${stage.id}-${cycle.id}-${filledEntry.id}-${UUID.randomUUID()}"
        val slOrder = PaperOrderEntity(
            stage = stage.id, cycleId = cycle.id, entryId = filledEntry.id, symbol = cycle.symbol, direction = cycle.direction,
            side = if (cycle.direction == "LONG") "SELL" else "BUY", orderType = "ENTRY_SL", triggerPrice = slPrice, limitPrice = null,
            quantity = filledEntry.remainingQuantity, status = "ACTIVE", clientOrderId = slClientId, createdAt = now, updatedAt = now
        )
        val slOrderId = dao.insertOrder(slOrder)
        dao.updateEntry(filledEntry.copy(slPrice = slPrice, slOrderId = slOrderId, slFixed = true, updatedAt = now))

        val openEntries = dao.getOpenFilledEntries(cycle.id)
        val totalQty = openEntries.sumOf { it.remainingQuantity }
        val avg = if (totalQty > 1e-12) openEntries.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / totalQty else filledEntry.fillPrice
        val tpPrice = calcTp(cycle.direction, avg!!, cycle.tpPercent)
        var updatedCycle = cycle.copy(
            status = "ACTIVE", averageEntryPrice = avg, totalQuantity = dao.getEntries(cycle.id).filter { it.status == "FILLED" || it.status == "CLOSED" }.sumOf { it.filledQuantity },
            totalRemainingQuantity = totalQty, takeProfitPrice = tpPrice, updatedAt = now, lastError = null
        )

        if (filledEntry.entryType == "AVERAGING") {
            updatedCycle = updatedCycle.copy(averagingDone = true)
            // TP recalculated from weighted avg (done above). Existing SLs remain fixed - do not touch them.
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_FILLED",
                message = "Averaging filled @ ${fmtPx(filledEntry.fillPrice!!)}. New avg ${fmtPx(avg)}. Cycle TP recalculated to ${fmtPx(tpPrice)}. Existing entry SLs unchanged.",
                price = filledEntry.fillPrice, dedupeKey = "avg-${filledEntry.clientOrderId}"))
            maybePlaceOppositeOnAveragingLocked(context, updatedCycle, filledEntry, now)
        } else if (filledEntry.entryType == "INITIAL") {
            updatedCycle = updatedCycle.copy(takeProfitPrice = tpPrice)
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "INITIAL_ACTIVE",
                message = "Initial entry active @ ${fmtPx(filledEntry.fillPrice!!)}. Cycle TP ${fmtPx(tpPrice)}. Independent SL ${fmtPx(slPrice)} (fixed).",
                price = filledEntry.fillPrice, dedupeKey = "init-${filledEntry.clientOrderId}"))
        }
        dao.updateCycle(updatedCycle)
        upsertCycleTpLocked(updatedCycle, now)
        // As soon as initial entry is filled, automatically create same-direction Averaging LIMIT (once per cycle)
        if (filledEntry.entryType == "INITIAL") {
            placeAveragingLimitIfNeededLocked(context, dao.getCycle(cycle.id) ?: updatedCycle, now)
        }
    }

    private suspend fun upsertCycleTpLocked(cycle: PaperCycleEntity, now: Long) {
        val tpPrice = cycle.takeProfitPrice ?: return
        val qty = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        if (qty <= 1e-12) return
        val existing = dao.getActiveTpForCycle(cycle.id)
        val side = if (cycle.direction == "LONG") "SELL" else "BUY"
        if (existing == null) {
            val clientId = "tp-${stage.id}-${cycle.id}-${UUID.randomUUID()}"
            val tp = PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = null, symbol = cycle.symbol, direction = cycle.direction, side = side,
                orderType = "CYCLE_TP", triggerPrice = tpPrice, limitPrice = null, quantity = qty, status = "ACTIVE", clientOrderId = clientId, createdAt = now, updatedAt = now)
            val id = dao.insertOrder(tp)
            dao.updateCycle(dao.getCycle(cycle.id)!!.copy(cycleTpOrderId = id, updatedAt = now))
        } else {
            dao.updateOrder(existing.copy(triggerPrice = tpPrice, quantity = qty, updatedAt = now, lastError = null))
        }
    }

    private suspend fun maybePlaceOppositeOnAveragingLocked(context: Context, cycle: PaperCycleEntity, avgEntry: PaperEntryEntity, now: Long) {
        if (!cycle.oppositeEnabled || cycle.oppositeDone) return
        // Only if no opposite-side position for that symbol is already open
        val oppositeDir = if (cycle.direction == "LONG") "SHORT" else "LONG"
        val hasOppositeDir = dao.countOpenCycles(stage.id, cycle.symbol, oppositeDir) > 0
        if (hasOppositeDir) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_SKIPPED",
                message = "Opposite-side entry skipped: an opposite-side position for ${cycle.symbol} is already open.", dedupeKey = "opp-skip-${cycle.id}"))
            dao.updateCycle(cycle.copy(oppositeDone = true, updatedAt = now))
            return
        }
        val fillPrice = avgEntry.fillPrice ?: return
        // Equal margin (averaging margin / filled averaging entry margin)
        val margin = if (avgEntry.margin > 0.0) avgEntry.margin else cycle.averagingMargin
        val lev = cycle.leverage
        val wallet = ensureWallet()
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_SKIPPED",
                message = "Opposite-side entry skipped: insufficient simulated balance.", dedupeKey = "opp-bal-${cycle.id}-$now"))
            dao.updateCycle(cycle.copy(oppositeDone = true, updatedAt = now))
            return
        }
        val res = placeInitialLimitLocked(context, cycle.symbol, oppositeDir, margin, lev, fillPrice, sourceType = "AVERAGING_OPPOSITE", sourceCycleId = cycle.id)
        if (res.ok) {
            dao.updateCycle(dao.getCycle(cycle.id)!!.copy(oppositeDone = true, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_PLACED",
                message = "Opposite-side LIMIT $oppositeDir created at averaging fill ${fmtPx(fillPrice)} with equal margin ${fmt(margin)} USDT (pending LIMIT, ready for execution).", price = fillPrice, dedupeKey = "opp-${cycle.id}"))
        } else {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_FAILED",
                message = "Opposite-side entry failed: ${res.message}", dedupeKey = "opp-fail-${cycle.id}-$now"))
        }
    }

    private suspend fun processActiveCycleLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        if (!mark.isFinite() || mark <= 0.0) return
        if (cycle.status !in setOf("ACTIVE", "CLOSING")) return
        val openEntries = dao.getOpenFilledEntries(cycle.id)
        if (openEntries.isEmpty()) {
            finalizeIfEmptyLocked(cycle, mark, now, cycle.closeReason ?: "COMPLETED")
            return
        }
        // Liquidation checks per entry (individual) - before TP/SL to be safe
        for (entry in openEntries) {
            val liq = liquidationPrice(entry.direction, entry.fillPrice ?: continue, entry.leverage)
            val hitLiq = if (entry.direction == "LONG") mark <= liq else mark >= liq
            if (hitLiq) {
                closeSingleEntryLocked(cycle, entry, liq, "LIQUIDATION", now, "liq-${entry.id}-${entry.filledAt}")
                // affected entry only; its SL cancelled/verified inside
            }
        }
        val afterLiq = dao.getOpenFilledEntries(cycle.id)
        if (afterLiq.isEmpty()) { finalizeIfEmptyLocked(dao.getCycle(cycle.id) ?: cycle, mark, now, "LIQUIDATION"); return }

        // Cycle TP (closes remaining qty)
        val tp = cycle.takeProfitPrice
        if (tp != null) {
            val hitTp = if (cycle.direction == "LONG") mark >= tp else mark <= tp
            if (hitTp) {
                executeCycleTakeProfitLocked(context, dao.getCycle(cycle.id) ?: cycle, tp, now)
                return
            }
        }
        // Individual SLs
        for (entry in dao.getOpenFilledEntries(cycle.id)) {
            val sl = entry.slPrice ?: continue
            val hitSl = if (cycle.direction == "LONG") mark <= sl else mark >= sl
            if (hitSl) {
                closeSingleEntryLocked(dao.getCycle(cycle.id) ?: cycle, entry, sl, "SL", now, "sl-${entry.id}-${entry.slOrderId}")
            }
        }
        // Averaging trigger (max 1) - place LIMIT averaging entry if reached and not done/pending
        maybeTriggerAveragingLocked(context, dao.getCycle(cycle.id) ?: cycle, mark, now)
        // Update TP qty
        val c2 = dao.getCycle(cycle.id) ?: return
        if (c2.status == "ACTIVE") upsertCycleTpLocked(c2, now)
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        if (remaining <= 1e-12 && dao.getPendingEntries(stage.id).none { it.cycleId == cycle.id }) {
            finalizeIfEmptyLocked(c2, mark, now, c2.closeReason ?: "COMPLETED")
        }
    }

    private suspend fun maybeTriggerAveragingLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        // Averaging LIMIT is auto-created immediately after initial entry fill.
        // Safe retry in case placement was skipped (e.g. insufficient balance) earlier.
        placeAveragingLimitIfNeededLocked(context, cycle, now)
    }

    /**
     * Auto-create same-direction Averaging LIMIT order (max 1/cycle) at the approved
     * leverage-based averaging price as soon as initial entry is filled.
     */
    private suspend fun placeAveragingLimitIfNeededLocked(context: Context, cycle: PaperCycleEntity, now: Long) {
        if (!cycle.averagingEnabled || cycle.averagingDone) return
        if (cycle.status !in listOf("ACTIVE", "PENDING_ENTRY")) return
        val entries = dao.getEntries(cycle.id)
        if (entries.any { it.entryType == "AVERAGING" && (it.status == "PENDING" || it.status == "FILLED" || it.status == "PARTIALLY_FILLED") }) return
        val hasInitialFilled = entries.any { it.entryType == "INITIAL" && it.status == "FILLED" }
        if (!hasInitialFilled) return
        val avg = cycle.averageEntryPrice ?: run {
            val open = entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            val q = open.sumOf { it.remainingQuantity }; if (q > 1e-12) open.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / q else null
        } ?: return
        val trigger = if (cycle.direction == "LONG") avg * (1.0 - cycle.averagingPercent / 100.0) else avg * (1.0 + cycle.averagingPercent / 100.0)
        val wallet = ensureWallet()
        val margin = cycle.averagingMargin
        val lev = cycle.leverage
        val required = margin + (margin * lev * feeRate)
        if (wallet.availableBalance + 1e-9 < required) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_SKIPPED",
                message = "Averaging LIMIT not placed: insufficient simulated balance.", price = trigger, dedupeKey = "avg-skip-${cycle.id}"))
            return
        }
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - required, reservedMargin = wallet.reservedMargin + required, updatedAt = now))
        val clientId = "avg-${stage.id}-${cycle.id}"
        val avgEntry = PaperEntryEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, direction = cycle.direction,
            entryType = "AVERAGING", intendedPrice = trigger, fillPrice = null, requestedQuantity = 0.0, filledQuantity = 0.0,
            remainingQuantity = 0.0, margin = margin, leverage = lev, notional = 0.0, status = "PENDING", isAveraging = true,
            clientOrderId = clientId, createdAt = now, updatedAt = now)
        val entryId = dao.insertEntryIgnore(avgEntry)
        val resolvedEntryId = if (entryId > 0) entryId else (entries.firstOrNull { it.entryType == "AVERAGING" && it.clientOrderId == clientId }?.id ?: dao.getEntries(cycle.id).firstOrNull { it.entryType == "AVERAGING" }?.id ?: -1L)
        if (resolvedEntryId <= 0) return
        dao.insertOrderIgnore(PaperOrderEntity(stage = stage.id, cycleId = cycle.id, entryId = resolvedEntryId, symbol = cycle.symbol, direction = cycle.direction,
            side = if (cycle.direction == "LONG") "BUY" else "SELL", orderType = "ENTRY_LIMIT", triggerPrice = trigger, limitPrice = trigger,
            quantity = 0.0, status = "ACTIVE", clientOrderId = clientId, createdAt = now, updatedAt = now))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_PLACED",
            message = "Averaging LIMIT auto-created @ ${fmtPx(trigger)} (1 per cycle max).", price = trigger, dedupeKey = "avg-placed-${cycle.id}"))
    }
    private suspend fun closeSingleEntryLocked(cycle: PaperCycleEntity, entry: PaperEntryEntity, exitPrice: Double, reason: String, now: Long, idempotencySeed: String) {
        if (entry.status != "FILLED" || entry.remainingQuantity <= 1e-12) return
        val closeQty = entry.remainingQuantity
        val pnl = realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, closeQty) - (entry.fee * (closeQty / max(entry.filledQuantity, 1e-12)) / 2.0)
        val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
        val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
        val idem = "${stage.id}|${cycle.id}|${entry.id}|$reason|$idempotencySeed"
        dao.insertHistoryClose(PaperHistoryCloseEntity(
            stage = stage.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol, direction = cycle.direction,
            entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice, entryMargin = marginPart, leverage = entry.leverage,
            closedQuantity = closeQty, realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = reason,
            entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem
        ))
        val wallet = ensureWallet()
        dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + marginPart + pnl, reservedMargin = max(wallet.reservedMargin - marginPart, 0.0), realizedPnl = wallet.realizedPnl + pnl, updatedAt = now))
        // Cancel+verify this entry's SL
        entry.slOrderId?.let { slId ->
            dao.getOrder(slId)?.let { o -> if (o.status in setOf("ACTIVE", "CANCELLING")) dao.updateOrder(o.copy(status = "CANCEL_VERIFIED", cancelRequestedAt = o.cancelRequestedAt ?: now, cancelVerifiedAt = now, updatedAt = now)) }
        }
        dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = reason, closedAt = now, updatedAt = now))
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        dao.updateCycle((dao.getCycle(cycle.id) ?: cycle).copy(totalRemainingQuantity = remaining, realizedPnl = ((dao.getCycle(cycle.id)?.realizedPnl ?: 0.0) + pnl), updatedAt = now, status = if (remaining <= 1e-12) cycle.status else "ACTIVE"))
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_CLOSED",
            message = "Entry #${entry.id} ${entry.entryType} closed by $reason @ ${fmtPx(exitPrice)}. PNL ${fmt(pnl)} USDT. Other entries/TP unaffected (unless cycle completed).",
            price = exitPrice, dedupeKey = "close-$idem"))
    }

    private suspend fun executeCycleTakeProfitLocked(context: Context, cycle: PaperCycleEntity, tpPrice: Double, now: Long) {
        val tpOrder = dao.getActiveTpForCycle(cycle.id)
        tpOrder?.let { dao.updateOrder(it.copy(status = "FILLED", filledPrice = tpPrice, filledQuantity = it.quantity, executedAt = now, updatedAt = now)) }
        val entries = dao.getOpenFilledEntries(cycle.id)
        var totalPnl = cycle.realizedPnl ?: 0.0
        for (entry in entries) {
            val closeQty = entry.remainingQuantity
            if (closeQty <= 1e-12) continue
            val pnl = realizedPnl(entry.direction, entry.fillPrice ?: tpPrice, tpPrice, closeQty) - (entry.fee * (closeQty / max(entry.filledQuantity, 1e-12)) / 2.0)
            val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
            val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
            val idem = "${stage.id}|${cycle.id}|${entry.id}|TP|cycle-tp-$now"
            dao.insertHistoryClose(PaperHistoryCloseEntity(stage = stage.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol, direction = cycle.direction,
                entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = tpPrice, entryMargin = marginPart, leverage = entry.leverage, closedQuantity = closeQty,
                realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = "TP", entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem))
            totalPnl += pnl
            val wallet = ensureWallet()
            dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + marginPart + pnl, reservedMargin = max(wallet.reservedMargin - marginPart, 0.0), realizedPnl = wallet.realizedPnl + pnl, updatedAt = now))
            dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = "TP", closedAt = now, updatedAt = now))
        }
        // Cancel ALL remaining individual SL orders and VERIFY
        dao.requestCancelAllSlForCycle(cycle.id, "CANCELLING", now)
        dao.verifyCancelAllSlForCycle(cycle.id, now)
        val unverified = dao.countUnverifiedActiveSlForCycle(cycle.id)
        if (unverified > 0) {
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "SL_CANCEL_UNVERIFIED",
                message = "Cycle TP executed but $unverified SL order(s) could not be verification-confirmed cancelled; retry required.", price = tpPrice, dedupeKey = "sl-unv-${cycle.id}-$now"))
        }
        // Cancel pending entry orders (e.g. unfilled averaging) for this cycle
        dao.getActiveOrdersForCycle(cycle.id).filter { it.orderType == "ENTRY_LIMIT" }.forEach { dao.updateOrder(it.copy(status = "CANCEL_VERIFIED", cancelVerifiedAt = now, updatedAt = now)) }
        val closed = cycle.copy(status = "CLOSED", closePrice = tpPrice, realizedPnl = totalPnl, closeReason = "TAKE_PROFIT", closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0)
        dao.updateCycle(closed)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "CYCLE_TP",
            message = "Cycle TP executed @ ${fmtPx(tpPrice)}. Remaining qty closed. All remaining entry SL orders cancelled and verified. PNL ${fmt(totalPnl)} USDT.",
            price = tpPrice, dedupeKey = "tp-${cycle.id}-$now"))
        if (cycle.reentryEnabled) createReentryPlanLocked(cycle, tpPrice, now)
    }

    private suspend fun createReentryPlanLocked(cycle: PaperCycleEntity, closePrice: Double, now: Long) {
        val existing = dao.getOpenReentryPlanForSource(cycle.id)
        if (existing != null) return
        val trigger = if (cycle.direction == "LONG") closePrice * (1.0 - cycle.reentryPercent / 100.0) else closePrice * (1.0 + cycle.reentryPercent / 100.0)
        val plan = PaperReentryPlanEntity(stage = stage.id, sourceCycleId = cycle.id, symbol = cycle.symbol, direction = cycle.direction, leverage = cycle.leverage,
            margin = cycle.initialMargin, referenceClosePrice = closePrice, triggerPrice = trigger, status = "WAITING", clientPlanId = "re-${stage.id}-${cycle.id}", createdAt = now, updatedAt = now)
        dao.insertReentryPlan(plan)
        dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = cycle.id, symbol = cycle.symbol, type = "REENTRY_WAITING",
            message = "Re-entry waiting for pullback to ${fmtPx(trigger)} (${cycle.reentryPercent}% from TP close). New cycle will be created after trigger; never merged.",
            price = trigger, dedupeKey = "re-wait-${cycle.id}"))
    }

    private suspend fun processReentryPlanLocked(context: Context, plan: PaperReentryPlanEntity, mark: Double, now: Long) {
        if (plan.status !in setOf("WAITING", "TRIGGERED")) return
        val reached = if (plan.direction == "LONG") mark <= plan.triggerPrice else mark >= plan.triggerPrice
        if (!reached && plan.status == "WAITING") return
        val updated = plan.copy(status = "TRIGGERED", triggeredAt = plan.triggeredAt ?: now, updatedAt = now)
        dao.updateReentryPlan(updated)
        val res = placeInitialLimitLocked(context, plan.symbol, plan.direction, plan.margin, plan.leverage, plan.triggerPrice, sourceType = "REENTRY", sourceCycleId = plan.sourceCycleId)
        if (res.ok) {
            dao.updateReentryPlan(updated.copy(status = "ORDER_PLACED", newCycleId = res.cycleId, entryOrderId = res.orderId, updatedAt = now))
            dao.insertEvent(PaperEventEntity(stage = stage.id, cycleId = res.cycleId, symbol = plan.symbol, type = "REENTRY_PLACED",
                message = "Re-entry LIMIT placed as NEW cycle #${res.cycleId} @ ${fmtPx(plan.triggerPrice)} (source cycle #${plan.sourceCycleId}).",
                price = plan.triggerPrice, dedupeKey = "re-place-${plan.id}"))
        } else {
            dao.updateReentryPlan(updated.copy(status = "WAITING", lastError = res.message, updatedAt = now))
        }
    }

    private suspend fun finalizeIfEmptyLocked(cycle: PaperCycleEntity, mark: Double, now: Long, reason: String) {
        val pending = dao.getEntries(cycle.id).any { it.status == "PENDING" }
        if (pending) return
        if (cycle.status == "CLOSED") return
        dao.requestCancelAllSlForCycle(cycle.id, "CANCELLING", now)
        dao.verifyCancelAllSlForCycle(cycle.id, now)
        dao.getActiveOrdersForCycle(cycle.id).forEach { dao.updateOrder(it.copy(status = "CANCEL_VERIFIED", cancelVerifiedAt = now, updatedAt = now)) }
        val realized = cycle.realizedPnl ?: 0.0
        dao.updateCycle(cycle.copy(status = "CLOSED", closeReason = reason, closePrice = mark, closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0, realizedPnl = realized))
        if (reason == "TAKE_PROFIT" && cycle.reentryEnabled) createReentryPlanLocked(cycle, mark, now)
    }

    

    private suspend fun ensureWallet(): PaperWalletEntity = dao.getWallet(stage.id) ?: PaperWalletEntity(stage = stage.id).also { dao.upsertWallet(it) }

    private fun assertSimulatedExecutionAllowed() {
        check(stage == TradingStage.STAGE_1 || stage == TradingStage.STAGE_2) {
            "Order submission blocked: ${stage.title}. Stage 3 real execution is disabled by default and not implemented in this build."
        }
    }

    companion object {
        fun calcTp(direction: String, avg: Double, pct: Double): Double = if (direction == "LONG") avg * (1 + pct / 100.0) else avg * (1 - pct / 100.0)
        fun calcSl(direction: String, avg: Double, pct: Double): Double = if (direction == "LONG") avg * (1 - pct / 100.0) else avg * (1 + pct / 100.0)
        fun realizedPnl(direction: String, entry: Double, exit: Double, qty: Double): Double = if (direction == "LONG") (exit - entry) * qty else (entry - exit) * qty
        fun weightedAvg(entries: List<Pair<Double, Double>>): Double {
            val qty = entries.sumOf { it.second }
            if (qty <= 1e-12) return entries.lastOrNull()?.first ?: 0.0
            return entries.sumOf { it.first * it.second } / qty
        }
        fun liquidationPrice(direction: String, entry: Double, leverage: Int): Double {
            // Conservative isolated-margin simulation approximation (includes fee/buffer). Not exchange-identical.
            val lev = LeverageConfig.requireSupported(leverage)
            val move = (1.0 / lev.toDouble()) * 0.93
            return if (direction == "LONG") entry * (1.0 - move) else entry * (1.0 + move)
        }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
    private fun fmtPx(v: Double) = String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    private fun fmtQty(v: Double) = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
}
