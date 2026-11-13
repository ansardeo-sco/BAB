package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.DemoStageRepository
import com.ansardeo.binancebot.data.LeverageConfig
import com.ansardeo.binancebot.data.LocalCoinSettings
import com.ansardeo.binancebot.data.LocalCoinSettingsStore
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.*
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.max

/**
 * Stage 2 (Binance USDⓈ-M Futures Demo) strategy engine.
 *
 * Uses the SAME strategy rules as Stage 1 (PaperEngine) but:
 *  - Balance/execution come from Binance Demo (not local virtual wallet).
 *  - Initial entry is NOT placed by BAB; the user places it manually in the Binance app.
 *    BAB detects the new position from Binance positions/trades and registers a cycle+entry.
 *  - NO Stop Loss orders are placed in Stage 2 (per user instruction; Stage 1 SL preserved).
 *  - All automated averaging/re-entry/opposite entries use LIMIT orders on Binance Demo.
 *  - Trailing stop is monitored app-side (same math as Stage 1) and closes via reduce-only order.
 *
 * Stage 3 remains disabled. Stage 1 untouched.
 */
class DemoStrategyEngine(
    private val db: PaperDatabase,
    private val demoRepo: DemoStageRepository = DemoStageRepository()
) {
    private val dao: PaperDao = db.dao()
    private val demoDao: DemoOrderDao = db.demoOrderDao()
    private val mutex = Mutex()
    private val feeRate = 0.0004
    private val averagingRoiTriggerPercent = 60.0

    suspend fun hasPendingWork(): Boolean {
        return dao.getActiveCycles(TradingStage.STAGE_2.id).isNotEmpty() ||
            dao.getPendingReentryPlans(TradingStage.STAGE_2.id).isNotEmpty() ||
            dao.getActiveOrders(TradingStage.STAGE_2.id).isNotEmpty() ||
            demoDao.getOpen(TradingStage.STAGE_2.id).isNotEmpty()
    }

    /** Detect new Binance Demo positions and register local cycles/entries. */
    suspend fun detectAndRegisterPositions(context: Context) {
        mutex.withLock {
            db.withTransaction {
                val creds = com.ansardeo.binancebot.data.DemoCredentialsStore.load(context)
                if (!creds.hasBoth) return@withTransaction
                val snap = runCatching { demoRepo.fetchSnapshot(context) }.getOrNull() ?: return@withTransaction
                if (!snap.ok) return@withTransaction
                val positions = snap.positions
                for (i in 0 until positions.length()) {
                    val p = positions.optJSONObject(i) ?: continue
                    val sym = p.optString("symbol").uppercase()
                    if (sym.isBlank()) continue
                    val amt = p.optString("positionAmt").toDoubleOrNull() ?: 0.0
                    if (kotlin.math.abs(amt) <= 1e-12) continue
                    val side = if (amt > 0) "LONG" else "SHORT"
                    val entryPrice = p.optString("entryPrice").toDoubleOrNull() ?: continue
                    if (entryPrice <= 0.0) continue
                    val qty = kotlin.math.abs(amt)
                    val lev = p.optString("leverage").toIntOrNull() ?: 6
                    // Skip if an open cycle already exists for this symbol/direction.
                    val open = dao.countOpenCycles(TradingStage.STAGE_2.id, sym, side)
                    if (open > 0) continue
                    registerInitialEntryLocked(context, sym, side, entryPrice, qty, lev, p)
                }
            }
        }
    }

    private suspend fun registerInitialEntryLocked(context: Context, sym: String, dir: String, entryPrice: Double, qty: Double, lev: Int, posJson: org.json.JSONObject) {
        val now = System.currentTimeMillis()
        val settings = runCatching { com.ansardeo.binancebot.data.LocalCoinSettingsStore.load(context, sym) }.getOrNull()
            ?: runCatching { com.ansardeo.binancebot.data.LocalCoinSettingsStore.loadGlobal(context) }.getOrNull()
        val profile = LeverageConfig.requireProfile(LeverageConfig.requireSupported(lev))
        val cycle = PaperCycleEntity(
            stage = TradingStage.STAGE_2.id, symbol = sym, direction = dir, status = "ACTIVE", leverage = lev,
            initialMargin = 0.0, averagingMargin = settings?.averagingMargin?.takeIf { it > 0 } ?: 2.0,
            oppositeMargin = settings?.oppositeMargin?.takeIf { it > 0 } ?: 2.0,
            trailingActivationPercent = profile.trailingActivationPercent, callbackRatePercent = profile.callbackRatePercent,
            newEntryDistancePercent = profile.newEntryDistancePercent, slPercent = profile.slPercent,
            averagingEnabled = settings?.averagingEnabled ?: true, reentryEnabled = settings?.reentryEnabled ?: true,
            oppositeEnabled = settings?.oppositeEnabled ?: true, averageEntryPrice = entryPrice,
            totalQuantity = qty, totalRemainingQuantity = qty, createdAt = now, updatedAt = now, sourceType = "BINANCE_DETECTED"
        )
        val cycleId = dao.insertCycle(cycle)
        val entry = PaperEntryEntity(
            stage = TradingStage.STAGE_2.id, cycleId = cycleId, symbol = sym, direction = dir, entryType = "INITIAL",
            status = "FILLED", margin = 0.0, leverage = lev, intendedPrice = entryPrice, fillPrice = entryPrice,
            quantity = qty, filledQuantity = qty, remainingQuantity = qty, clientOrderId = "detected-${cycleId}-$now",
            trailingBasePrice = entryPrice, trailingActivationPercent = profile.trailingActivationPercent,
            trailingCallbackPercent = profile.callbackRatePercent, trailingActivated = false,
            filledAt = now, createdAt = now, updatedAt = now
        )
        val entryId = dao.insertEntry(entry)
        dao.updateCycle(cycle.copy(id = cycleId, averageEntryPrice = entryPrice, totalQuantity = qty, totalRemainingQuantity = qty))
        dao.insertEvent(PaperEventEntity(stage = TradingStage.STAGE_2.id, cycleId = cycleId, symbol = sym, type = "ENTRY_DETECTED",
            message = "Detected Binance Demo $dir position for $sym @ ${String.format(java.util.Locale.US, "%.8f", entryPrice).trimEnd('0').trimEnd('.')} qty ${String.format(java.util.Locale.US, "%.6f", qty).trimEnd('0').trimEnd('.')}.", price = entryPrice))
    }

    /** Process active Stage 2 cycles: trailing stop, averaging trigger, re-entry, opposite-side. */
    suspend fun processAll(context: Context, prices: Map<String, Double>) {
        if (prices.isEmpty()) return
        mutex.withLock {
            db.withTransaction {
                val now = System.currentTimeMillis()
                for (c in dao.getActiveCycles(TradingStage.STAGE_2.id)) {
                    val mark = prices[c.symbol.uppercase()] ?: continue
                    processActiveCycleLocked(context, c, mark, now)
                }
                for (plan in dao.getPendingReentryPlans(TradingStage.STAGE_2.id)) {
                    processReentryPlanLocked(context, plan, now)
                }
            }
        }
    }

    private suspend fun processActiveCycleLocked(context: Context, cycle: PaperCycleEntity, mark: Double, now: Long) {
        if (!mark.isFinite() || mark <= 0.0) return
        if (cycle.status !in setOf("ACTIVE", "CLOSING")) return

        // Trailing stop (per entry). Closure triggers re-entry logic. NO SL in Stage 2.
        for (entry in dao.getOpenFilledEntries(cycle.id)) {
            val eNow = dao.getEntry(entry.id) ?: continue
            val progressed = PaperEngine.progressTrailing(eNow, mark)
            if (progressed.first) dao.updateEntry(progressed.second)
            val e2 = progressed.second
            if (PaperEngine.isTrailingStopHit(e2, mark)) {
                val exit = e2.trailingStopPrice ?: mark
                closeEntryLocked(context, dao.getCycle(cycle.id) ?: cycle, dao.getEntry(entry.id) ?: e2, exit, "TRAILING_STOP", now, triggerReentry = true)
            }
        }

        var current = dao.getCycle(cycle.id) ?: return
        val remaining = dao.getOpenFilledEntries(current.id).sumOf { it.remainingQuantity }
        val pending = dao.getEntries(current.id).any { it.status == "PENDING" }
        dao.updateCycle(current.copy(totalRemainingQuantity = remaining, averageEntryPrice = weightedAverage(current.id), updatedAt = now))
        if (remaining <= 1e-12 && !pending) finalizeLocked(dao.getCycle(current.id) ?: current, mark, now, "TRAILING_STOP")
    }

    private suspend fun closeEntryLocked(context: Context, cycle: PaperCycleEntity, entry: PaperEntryEntity, exitPrice: Double, reason: String, now: Long, triggerReentry: Boolean) {
        if (entry.status != "FILLED" || entry.remainingQuantity <= 1e-12) return
        val closeQty = entry.remainingQuantity
        val gross = PaperEngine.realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, closeQty)
        val marginPart = entry.margin * (closeQty / max(entry.filledQuantity, 1e-12))
        val pnl = gross
        val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
        val idem = "stage2|${cycle.id}|${entry.id}|$reason|${System.currentTimeMillis()}"
        dao.insertHistoryClose(PaperHistoryCloseEntity(
            stage = TradingStage.STAGE_2.id, cycleId = cycle.id, entryId = entry.id, symbol = cycle.symbol,
            direction = entry.direction, entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice,
            entryMargin = marginPart, leverage = entry.leverage, closedQuantity = closeQty,
            realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct, closingReason = reason,
            entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now, idempotencyKey = idem
        ))
        // Close on Binance Demo via reduce-only market order.
        runCatching {
            val side = if (entry.direction == "LONG") "SELL" else "BUY"
            val qtyStr = com.ansardeo.binancebot.util.PriceFormatter.formatQty(closeQty).trim()
            demoRepo.placeLimitOrder(context, cycle.symbol, side, qtyStr, (exitPrice).toString(), reduceOnly = true)
        }
        dao.updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closeReason = reason, closedAt = now, updatedAt = now))
        val remaining = dao.getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
        dao.updateCycle(cycle.copy(totalRemainingQuantity = remaining, updatedAt = now, closeReason = if (remaining <= 1e-12) reason else cycle.closeReason))
        dao.insertEvent(PaperEventEntity(stage = TradingStage.STAGE_2.id, cycleId = cycle.id, symbol = cycle.symbol, type = "ENTRY_CLOSED",
            message = "Stage 2 entry #${entry.id} ${entry.entryType} ${entry.direction} closed by $reason. (No SL in Stage 2 per policy.)", price = exitPrice))
        if (triggerReentry && reason == "TRAILING_STOP") {
            createReentryPlansLocked(cycle, exitPrice, now)
        }
    }

    private suspend fun createReentryPlansLocked(cycle: PaperCycleEntity, closePrice: Double, now: Long) {
        if (!cycle.reentryEnabled) return
        val dist = cycle.newEntryDistancePercent / 100.0
        val longTrigger = closePrice * (1.0 - dist)
        val shortTrigger = closePrice * (1.0 + dist)
        val longOpen = dao.countOpenCyclesExcluding(TradingStage.STAGE_2.id, cycle.symbol, "LONG", cycle.id) > 0
        val shortOpen = dao.countOpenCyclesExcluding(TradingStage.STAGE_2.id, cycle.symbol, "SHORT", cycle.id) > 0
        if (!longOpen) insertReentryPlanLocked(cycle, "LONG", longTrigger, closePrice, now)
        if (!shortOpen) insertReentryPlanLocked(cycle, "SHORT", shortTrigger, closePrice, now)
    }

    private suspend fun insertReentryPlanLocked(cycle: PaperCycleEntity, direction: String, trigger: Double, closePrice: Double, now: Long) {
        val existing = dao.getPendingReentryPlans(TradingStage.STAGE_2.id).firstOrNull {
            it.sourceCycleId == cycle.id && it.direction == direction && it.symbol == cycle.symbol
        }
        if (existing != null) return
        dao.insertReentryPlan(PaperReentryPlanEntity(
            stage = TradingStage.STAGE_2.id, sourceCycleId = cycle.id, symbol = cycle.symbol, direction = direction,
            leverage = cycle.leverage, margin = cycle.initialMargin.takeIf { it > 0 } ?: 2.0,
            referenceClosePrice = closePrice, triggerPrice = trigger, status = "WAITING",
            clientPlanId = "re2-${cycle.id}-$direction-$now-${UUID.randomUUID()}", createdAt = now, updatedAt = now
        ))
    }

    private suspend fun processReentryPlanLocked(context: Context, plan: PaperReentryPlanEntity, now: Long) {
        if (plan.status !in setOf("WAITING", "TRIGGERED", "ORDER_PLACED")) return
        val sideOpen = dao.countOpenCycles(TradingStage.STAGE_2.id, plan.symbol, plan.direction) > 0
        if (sideOpen) {
            dao.updateReentryPlan(plan.copy(status = "CANCELLED", lastError = "Side already open", updatedAt = now))
            return
        }
        if (plan.status == "ORDER_PLACED") return
        // Place LIMIT re-entry order on Binance Demo.
        val pxStr = com.ansardeo.binancebot.util.PriceFormatter.formatPriceInput(plan.triggerPrice)
        val lev = plan.leverage
        val margin = plan.margin
        val qty = if (plan.triggerPrice > 0) (margin * lev) / plan.triggerPrice else 0.0
        val qtyStr = com.ansardeo.binancebot.util.PriceFormatter.formatQty(qty).trim()
        val side = if (plan.direction == "LONG") "BUY" else "SELL"
        val clientId = "reentry2-${plan.id}-${System.currentTimeMillis()}"
        val res = demoRepo.placeLimitOrder(context, plan.symbol, side, qtyStr, pxStr, reduceOnly = false, clientOrderId = clientId)
        if (res.ok) {
            val oid = res.data?.optString("orderId")
            demoDao.insert(DemoOrderEntity(stage = TradingStage.STAGE_2.id, symbol = plan.symbol, direction = plan.direction,
                side = side, orderType = "ENTRY_LIMIT", triggerPrice = plan.triggerPrice, limitPrice = plan.triggerPrice,
                quantity = qty, leverage = lev, status = res.data?.optString("status") ?: "NEW",
                clientOrderId = clientId, exchangeOrderId = oid, reduceOnly = false))
            dao.updateReentryPlan(plan.copy(status = "ORDER_PLACED", entryOrderId = null, updatedAt = now, lastError = null))
        } else {
            dao.updateReentryPlan(plan.copy(status = "WAITING", lastError = res.error, updatedAt = now))
        }
    }

    private suspend fun weightedAverage(cycleId: Long): Double? {
        val open = dao.getOpenFilledEntries(cycleId)
        val qty = open.sumOf { it.remainingQuantity }
        if (qty <= 1e-12) return null
        return open.sumOf { (it.fillPrice ?: 0.0) * it.remainingQuantity } / qty
    }

    private suspend fun finalizeLocked(cycle: PaperCycleEntity, mark: Double, now: Long, reason: String) {
        if (cycle.status == "CLOSED") return
        dao.updateCycle(cycle.copy(status = "CLOSED", closeReason = reason, closePrice = mark, closedAt = now, updatedAt = now, totalRemainingQuantity = 0.0))
        dao.insertEvent(PaperEventEntity(stage = TradingStage.STAGE_2.id, cycleId = cycle.id, symbol = cycle.symbol, type = "CYCLE_CLOSED",
            message = "Stage 2 cycle #${cycle.id} closed ($reason).", price = mark))
    }

    companion object {
        fun calcSl(direction: String, entry: Double, pct: Double): Double = PaperEngine.calcSl(direction, entry, pct)
    }
}