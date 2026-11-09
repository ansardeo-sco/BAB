package com.ansardeo.binancebot.paper

import android.content.Context
import androidx.room.withTransaction
import com.ansardeo.binancebot.data.LocalCoinSettings
import com.ansardeo.binancebot.data.LocalCoinSettingsStore
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * BAB trading engine (stage-isolated).
 * - Stage 1: live public market prices + virtual 100 USDT. SIMULATED ONLY. No API keys, no real orders.
 * - Stage 2: demo/simulated trading. No real funds. SIMULATED ONLY.
 * - Stage 3: live trading scaffold. DISABLED by default. Real order submission is NOT implemented in this build;
 *   Stage 3 execution paths are blocked to prevent accidental real orders. Full signed Binance integration requires
 *   explicit secure setup and must never be enabled implicitly.
 * All stages are isolated by [stage] in the database.
 */
class PaperEngine(private val db: PaperDatabase, val stage: TradingStage = TradingStage.STAGE_1) {
    private val dao: PaperDao = db.dao()
    private val mutex = Mutex()
    private val feeRate = 0.0004 // 0.04% taker assumption (simulated; Stage 3 would use exchange fills)
    private val stageId = stage.id

    private fun assertSimulatedExecutionAllowed() {
        // SAFETY: Stage 1 and Stage 2 are always simulated. Stage 3 must never submit real orders from this engine.
        if (stage == TradingStage.STAGE_3) {
            throw IllegalStateException("Stage 3 Live Trading is disabled in this build. No real Binance orders are submitted. Configure secure live integration and explicit authorization before enabling.")
        }
    }

    private fun newWallet() = PaperWalletEntity(
        stage = stageId,
        startingBalance = if (stage == TradingStage.STAGE_3) 0.0 else 100.0,
        availableBalance = if (stage == TradingStage.STAGE_3) 0.0 else 100.0,
        reservedMargin = 0.0,
        realizedPnl = 0.0,
        updatedAt = now()
    )

    suspend fun ensureWallet(): PaperWalletEntity = mutex.withLock {
        val w = ensureWalletUnlocked()
        synchronizeStateUnlocked()
        w
    }

    suspend fun getWallet(): PaperWalletEntity = mutex.withLock { ensureWalletUnlocked() }

    suspend fun resetWallet() = mutex.withLock {
        assertSimulatedExecutionAllowed()
        db.withTransaction {
            val t = now()
            dao.upsertWallet(newWallet().copy(updatedAt = t))
            for (o in dao.getActiveOrders(stageId)) dao.updateOrder(o.copy(status = "CANCELLED", updatedAt = t))
            for (p in dao.getPendingPlans(stageId)) dao.updatePlan(p.copy(status = "CANCELLED", updatedAt = t))
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = null, symbol = null, type = "WALLET_RESET", message = "${stage.shortLabel} wallet reset to ${fmt(newWallet().availableBalance)} USDT (active orders/pipeline cancelled)", createdAt = t))
        }
    }

    suspend fun hasPendingWork(): Boolean = mutex.withLock {
        synchronizeStateUnlocked()
        dao.getActiveCycles(stageId).isNotEmpty() || dao.getActiveOrders(stageId).isNotEmpty() || dao.countPendingPlans(stageId) > 0
    }

    suspend fun openInitial(
        context: Context,
        symbol: String,
        direction: String,
        marginInput: Double,
        leverageInput: Int,
        price: Double,
        sourceCycleId: Long? = null,
        sourceType: String? = "INITIAL",
    ): Result<PaperCycleEntity> = mutex.withLock {
        openInitialUnlocked(context, symbol, direction, marginInput, leverageInput, price, sourceCycleId, sourceType)
    }

    private suspend fun openInitialUnlocked(
        context: Context,
        symbol: String,
        direction: String,
        marginInput: Double,
        leverageInput: Int,
        price: Double,
        sourceCycleId: Long?,
        sourceType: String?,
    ): Result<PaperCycleEntity> {
        return try {
            assertSimulatedExecutionAllowed() // blocks Stage 3 real-order risk in this build
            val sym = symbol.uppercase()
            val dir = direction.uppercase()
            require(dir == "LONG" || dir == "SHORT") { "Direction must be LONG or SHORT" }
            require(price > 0 && price.isFinite()) { "Invalid live price for $sym" }
            val s = LocalCoinSettingsStore.load(context, sym)
            if (dir == "LONG" && !s.enabledLong) return Result.failure(IllegalStateException("LONG is disabled for $sym"))
            if (dir == "SHORT" && !s.enabledShort) return Result.failure(IllegalStateException("SHORT is disabled for $sym"))
            val margin = if (marginInput > 0) marginInput else s.marginAmount
            require(margin in 0.5..100000.0) { "Margin must be valid (0.5+ USDT)" }
            val leverage = if (leverageInput > 0) leverageInput.coerceIn(1, 125) else s.leverage
            val qty = (margin * leverage) / price
            require(qty > 0 && qty.isFinite()) { "Calculated quantity is invalid" }
            val notional = qty * price
            val fee = notional * feeRate
            val tpPrice = tpPrice(dir, price, s.tpPercent)
            val slPrice = slPrice(dir, price, s.slPercent)
            val t = now()

            val cycle = db.withTransaction {
                val wallet = ensureWalletUnlocked()
                require(wallet.availableBalance + 1e-9 >= margin) {
                    "Insufficient ${stage.shortLabel} balance. Available ${fmt(wallet.availableBalance)} USDT, required ${fmt(margin)} USDT"
                }
                val existing = dao.getActiveCycles(stageId).any { it.symbol == sym && it.direction == dir }
                require(!existing) { "An active $dir cycle already exists for $sym in ${stage.shortLabel}" }

                val cycleId = dao.insertCycle(
                    PaperCycleEntity(
                        stage = stageId, symbol = sym, direction = dir, status = "ACTIVE",
                        leverage = leverage, initialMargin = margin, averagingMargin = s.averagingMargin,
                        tpPercent = s.tpPercent, slPercent = s.slPercent,
                        averagingPercent = s.averagingPercent, reentryPercent = s.reentryPercent,
                        averagingEnabled = s.averagingEnabled, reentryEnabled = s.reentryEnabled,
                        averageEntryPrice = price, totalQuantity = qty,
                        takeProfitPrice = tpPrice, stopLossPrice = slPrice,
                        updatedAt = t, createdAt = t,
                        warningPercent = s.warningPercent,
                        oppositeEnabled = s.oppositeEnabled, oppositePercent = s.oppositePercent, oppositeMargin = s.oppositeMargin,
                        sourceCycleId = sourceCycleId, sourceType = sourceType,
                    )
                )
                dao.insertEntry(PaperEntryEntity(stage = stageId, cycleId = cycleId, symbol = sym, direction = dir, entryType = "INITIAL", status = "FILLED", margin = margin, leverage = leverage, price = price, quantity = qty, remainingQuantity = qty, fee = fee, createdAt = t))
                dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - margin - fee, reservedMargin = wallet.reservedMargin + margin, realizedPnl = wallet.realizedPnl - fee, updatedAt = t))
                createProtectiveOrders(cycleId, sym, dir, tpPrice, slPrice, qty, t)
                createPipelinePlans(cycleId, sym, dir, price, s, t)
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = cycleId, symbol = sym, type = "INITIAL_ENTRY", message = "${stage.shortLabel} simulated $dir opened @ ${p(price)} | margin ${fmt(margin)} | lev ${leverage}x | qty ${q(qty)}", price = price, createdAt = t))
                dao.getCycle(cycleId)!!
            }
            Result.success(cycle)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createProtectiveOrders(cycleId: Long, sym: String, dir: String, tp: Double, sl: Double, qty: Double, t: Long) {
        dao.insertOrder(PaperOrderEntity(stage = stageId, cycleId = cycleId, symbol = sym, direction = dir, orderType = "TAKE_PROFIT", triggerPrice = tp, quantity = qty, status = "ACTIVE", createdAt = t, updatedAt = t))
        dao.insertOrder(PaperOrderEntity(stage = stageId, cycleId = cycleId, symbol = sym, direction = dir, orderType = "STOP_LOSS", triggerPrice = sl, quantity = qty, status = "ACTIVE", createdAt = t, updatedAt = t))
    }

    private suspend fun createPipelinePlans(cycleId: Long, sym: String, dir: String, entryPrice: Double, s: LocalCoinSettings, t: Long) {
        if (s.averagingEnabled && !hasOpenPlan(cycleId, "AVERAGING")) {
            val trig = adverseTriggerPrice(dir, entryPrice, s.averagingPercent)
            dao.insertPlan(PaperPlanEntity(stage = stageId, cycleId = cycleId, symbol = sym, direction = dir, planType = "AVERAGING", status = "WAITING", triggerPercent = s.averagingPercent, warningPercent = s.warningPercent, referencePrice = entryPrice, triggerPrice = trig, margin = s.averagingMargin, leverage = s.leverage, createdAt = t, updatedAt = t))
        }
        if (s.oppositeEnabled && !hasOpenPlan(cycleId, "OPPOSITE_SIDE")) {
            val trig = adverseTriggerPrice(dir, entryPrice, s.oppositePercent)
            dao.insertPlan(PaperPlanEntity(stage = stageId, cycleId = cycleId, symbol = sym, direction = dir, planType = "OPPOSITE_SIDE", status = "WAITING", triggerPercent = s.oppositePercent, warningPercent = s.warningPercent, referencePrice = entryPrice, triggerPrice = trig, margin = s.oppositeMargin, leverage = s.leverage, createdAt = t, updatedAt = t))
        }
    }

    private suspend fun hasOpenPlan(cycleId: Long, type: String): Boolean {
        val p = dao.getPlanForCycle(cycleId, type) ?: return false
        return p.status !in listOf("ACTIVATED", "EXECUTED", "CANCELLED")
    }

    suspend fun processSymbolPrice(context: Context, symbol: String, price: Double) = mutex.withLock {
        processSymbolPriceUnlocked(context, symbol, price)
    }

    suspend fun processAll(context: Context, prices: Map<String, Double>) = mutex.withLock {
        synchronizeStateUnlocked()
        for ((sym, px) in prices) {
            if (!px.isFinite() || px <= 0) continue
            processSymbolPriceUnlocked(context, sym, px)
        }
    }

    private suspend fun processSymbolPriceUnlocked(context: Context, symbol: String, price: Double) {
        if (price <= 0 || !price.isFinite()) return
        val sym = symbol.uppercase()
        processPlansUnlocked(context, sym, price)
        val active = dao.getActiveCycles(stageId).filter { it.symbol == sym }
        for (c in active) processCycleUnlocked(context, c, price)
        processActiveOrdersUnlocked(context, sym, price)
    }

    private suspend fun processCycleUnlocked(context: Context, cycle: PaperCycleEntity, price: Double) {
        val fresh = dao.getCycle(cycle.id) ?: return
        if (fresh.status != "ACTIVE") return
        val avg = fresh.averageEntryPrice ?: return
        if (avg <= 0 || fresh.totalQuantity <= 0) return
        val s = LocalCoinSettingsStore.load(context, fresh.symbol)
        val favorable = favorablePct(fresh.direction, avg, price)
        val adverse = adversePct(fresh.direction, avg, price)
        val slPct = if (fresh.slPercent > 0) fresh.slPercent else s.slPercent
        val tpPct = if (fresh.tpPercent > 0) fresh.tpPercent else s.tpPercent
        if (adverse >= slPct && slPct > 0) { closeCycleUnlocked(fresh, price, "STOP_LOSS", s); return }
        if (favorable >= tpPct && tpPct > 0) { closeCycleUnlocked(fresh, price, "TAKE_PROFIT", s); return }
    }

    private suspend fun processPlansUnlocked(context: Context, sym: String, price: Double) {
        val plans = dao.getPendingPlans(stageId).filter { it.symbol == sym }
        for (plan in plans) {
            val p = dao.getPlan(plan.id) ?: continue
            if (p.status in listOf("ACTIVATED", "EXECUTED", "CANCELLED")) continue
            when (p.planType) {
                "AVERAGING" -> processAveragingPlan(context, p, price)
                "OPPOSITE_SIDE" -> processOppositePlan(context, p, price)
                "REENTRY" -> processReentryPlan(context, p, price)
            }
        }
    }

    private suspend fun processAveragingPlan(context: Context, plan: PaperPlanEntity, price: Double) {
        val cycle = plan.cycleId?.let { dao.getCycle(it) } ?: run { dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        if (cycle.status != "ACTIVE" || cycle.averagingDone) { dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        val avg = cycle.averageEntryPrice ?: return
        val adverse = adversePct(cycle.direction, avg, price)
        val warnAt = plan.warningPercent ?: cycle.warningPercent
        val trigAt = plan.triggerPercent ?: cycle.averagingPercent
        var cur = plan
        if (adverse >= warnAt && cur.status == "WAITING") {
            cur = cur.copy(status = "WARNING", updatedAt = now(), triggerPrice = plan.triggerPrice ?: adverseTriggerPrice(cycle.direction, avg, trigAt))
            dao.updatePlan(cur)
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = cycle.id, symbol = cycle.symbol, type = "AVERAGING_WARNING", message = "Averaging warning: adverse ${fmt(adverse)}% reached ${fmt(warnAt)}% (trigger ${fmt(trigAt)}%)", price = price, createdAt = now()))
        }
        if (adverse >= trigAt && trigAt > 0) executeAveraging(context, cycle, cur, price)
    }

    private suspend fun executeAveraging(context: Context, cycle: PaperCycleEntity, plan: PaperPlanEntity, price: Double) {
        val fresh = dao.getCycle(cycle.id) ?: return
        if (fresh.status != "ACTIVE" || fresh.averagingDone) { dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        val s = LocalCoinSettingsStore.load(context, fresh.symbol)
        val margin = (plan.margin ?: fresh.averagingMargin).takeIf { it > 0 } ?: s.averagingMargin
        val t = now()
        db.withTransaction {
            val wallet = ensureWalletUnlocked()
            if (margin <= 0 || wallet.availableBalance + 1e-9 < margin) {
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = fresh.id, symbol = fresh.symbol, type = "AVERAGING_SKIPPED", message = "Averaging trigger reached but skipped (margin ${fmt(margin)} unavailable)", price = price, createdAt = t))
                dao.updateCycle(fresh.copy(averagingDone = true, updatedAt = t))
                dao.updatePlan((dao.getPlan(plan.id) ?: plan).copy(status = "CANCELLED", updatedAt = t))
                return@withTransaction
            }
            val c = dao.getCycle(fresh.id) ?: return@withTransaction
            if (c.status != "ACTIVE" || c.averagingDone) return@withTransaction
            val avg = c.averageEntryPrice ?: price
            val qty = (margin * c.leverage) / price
            if (qty <= 0 || !qty.isFinite()) return@withTransaction
            val fee = qty * price * feeRate
            val newQty = c.totalQuantity + qty
            val newAvg = ((avg * c.totalQuantity) + (price * qty)) / newQty
            val tp = tpPrice(c.direction, newAvg, c.tpPercent)
            val sl = slPrice(c.direction, newAvg, c.slPercent)
            dao.insertEntry(PaperEntryEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, direction = c.direction, entryType = "AVERAGING", status = "FILLED", margin = margin, leverage = c.leverage, price = price, quantity = qty, remainingQuantity = qty, fee = fee, createdAt = t))
            dao.updateCycle(c.copy(averagingDone = true, averageEntryPrice = newAvg, totalQuantity = newQty, takeProfitPrice = tp, stopLossPrice = sl, updatedAt = t))
            dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance - margin - fee, reservedMargin = wallet.reservedMargin + margin, realizedPnl = wallet.realizedPnl - fee, updatedAt = t))
            dao.cancelActiveOrdersForCycle(stageId, c.id, "CANCELLED", t)
            createProtectiveOrders(c.id, c.symbol, c.direction, tp, sl, newQty, t)
            dao.updatePlan((dao.getPlan(plan.id) ?: plan).copy(status = "EXECUTED", referencePrice = avg, triggerPrice = price, executedAt = t, updatedAt = t))
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, type = "AVERAGING_EXECUTED", message = "Averaging executed @ ${p(price)} | new avg ${p(newAvg)} | TP ${p(tp)} SL ${p(sl)}", price = price, createdAt = t))
        }
    }

    private suspend fun processOppositePlan(context: Context, plan: PaperPlanEntity, price: Double) {
        val cycle = plan.cycleId?.let { dao.getCycle(it) } ?: run { dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        if (cycle.status != "ACTIVE" || cycle.oppositeDone || !cycle.oppositeEnabled) { if (cycle.status != "ACTIVE" || cycle.oppositeDone) dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        val avg = cycle.averageEntryPrice ?: return
        val adverse = adversePct(cycle.direction, avg, price)
        val warnAt = plan.warningPercent ?: cycle.warningPercent
        val trigAt = plan.triggerPercent ?: cycle.oppositePercent
        var cur = dao.getPlan(plan.id) ?: plan
        if (cur.status in listOf("ACTIVATED", "EXECUTED", "CANCELLED")) return
        if (adverse >= warnAt && cur.status == "WAITING") {
            cur = cur.copy(status = "WARNING", updatedAt = now()); dao.updatePlan(cur)
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = cycle.id, symbol = cycle.symbol, type = "OPPOSITE_WARNING", message = "Opposite-side warning: adverse ${fmt(adverse)}% (trigger ${fmt(trigAt)}%)", price = price, createdAt = now()))
        }
        if (adverse >= trigAt && trigAt > 0) executeOpposite(context, cycle, cur, price)
    }

    private suspend fun executeOpposite(context: Context, cycle: PaperCycleEntity, plan: PaperPlanEntity, price: Double) {
        val fresh = dao.getCycle(cycle.id) ?: return
        if (fresh.status != "ACTIVE" || fresh.oppositeDone) { dao.updatePlan(plan.copy(status = "CANCELLED", updatedAt = now())); return }
        val s = LocalCoinSettingsStore.load(context, fresh.symbol)
        val oppDir = if (fresh.direction == "LONG") "SHORT" else "LONG"
        val margin = listOf(plan.margin, fresh.oppositeMargin, s.oppositeMargin, fresh.initialMargin).firstNotNullOfOrNull { v -> v?.takeIf { it > 0 } } ?: 2.0
        val lev = (plan.leverage ?: fresh.leverage).takeIf { it > 0 } ?: s.leverage
        val t = now()
        db.withTransaction {
            val c = dao.getCycle(fresh.id) ?: return@withTransaction
            if (c.status != "ACTIVE" || c.oppositeDone) return@withTransaction
            dao.updateCycle(c.copy(oppositeDone = true, updatedAt = t))
            dao.updatePlan((dao.getPlan(plan.id) ?: plan).copy(status = "TRIGGER_REACHED", updatedAt = t, triggerPrice = price, margin = margin, leverage = lev))
        }
        val res = openInitialUnlocked(context, fresh.symbol, oppDir, margin, lev, price, sourceCycleId = fresh.id, sourceType = "OPPOSITE")
        db.withTransaction {
            val pl = dao.getPlan(plan.id)
            if (res.isSuccess) {
                val newId = res.getOrNull()?.id
                pl?.let { dao.updatePlan(it.copy(status = "EXECUTED", resultCycleId = newId, executedAt = now(), updatedAt = now())) }
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = fresh.id, symbol = fresh.symbol, type = "OPPOSITE_EXECUTED", message = "Opposite-side $oppDir executed @ ${p(price)} (cycle #$newId)", price = price, createdAt = now()))
            } else {
                pl?.let { dao.updatePlan(it.copy(status = "CANCELLED", updatedAt = now())) }
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = fresh.id, symbol = fresh.symbol, type = "OPPOSITE_FAILED", message = "Opposite-side execution failed: ${res.exceptionOrNull()?.message}", price = price, createdAt = now()))
            }
        }
    }

    private suspend fun processReentryPlan(context: Context, plan: PaperPlanEntity, price: Double) {
        if (plan.status !in listOf("WAITING", "WARNING", "TRIGGER_REACHED", "READY")) return
        val trigger = plan.triggerPrice ?: return
        val hit = if (plan.direction == "LONG") price <= trigger else price >= trigger
        if (!hit) return
        db.withTransaction {
            val cur = dao.getPlan(plan.id) ?: return@withTransaction
            if (cur.status in listOf("ACTIVATED", "EXECUTED", "CANCELLED")) return@withTransaction
            if (dao.getActiveOrders(stageId).any { it.sourcePlanId == cur.id && it.status == "ACTIVE" }) return@withTransaction
            val s = LocalCoinSettingsStore.load(context, cur.symbol)
            val margin = cur.margin ?: s.marginAmount
            val lev = cur.leverage ?: s.leverage
            val qty = if (price > 0) (margin * lev) / price else 0.0
            val tempCycleId = cur.cycleId ?: (cur.sourceCycleId ?: 0L)
            val orderId = dao.insertOrder(PaperOrderEntity(stage = stageId, cycleId = tempCycleId, symbol = cur.symbol, direction = cur.direction, orderType = "REENTRY", triggerPrice = trigger, quantity = qty, status = "ACTIVE", sourcePlanId = cur.id, createdAt = now(), updatedAt = now()))
            dao.updatePlan(cur.copy(status = "ACTIVATED", activeOrderId = orderId, activatedAt = now(), updatedAt = now()))
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = cur.sourceCycleId, symbol = cur.symbol, type = "REENTRY_ACTIVATED", message = "Re-entry condition met @ ${p(trigger)}; simulated order activated (Open Orders)", price = price, createdAt = now()))
        }
        val activated = dao.getPlan(plan.id) ?: return
        val orderId = activated.activeOrderId ?: return
        val order = dao.getOrder(orderId) ?: return
        executeReentryOrder(context, order, activated, price)
    }

    private suspend fun executeReentryOrder(context: Context, order: PaperOrderEntity, plan: PaperPlanEntity, price: Double) {
        if (order.status != "ACTIVE" || order.orderType != "REENTRY") return
        val hasActive = dao.getActiveCycles(stageId).any { it.symbol == plan.symbol && it.direction == plan.direction }
        if (hasActive) return
        val s = LocalCoinSettingsStore.load(context, plan.symbol)
        val margin = plan.margin ?: s.marginAmount
        val lev = plan.leverage ?: s.leverage
        val res = openInitialUnlocked(context, plan.symbol, plan.direction, margin, lev, price, sourceCycleId = plan.sourceCycleId, sourceType = "REENTRY")
        db.withTransaction {
            val curOrder = dao.getOrder(order.id) ?: order
            val curPlan = dao.getPlan(plan.id) ?: plan
            if (res.isSuccess) {
                val newId = res.getOrNull()?.id
                if (curOrder.status == "ACTIVE") dao.updateOrder(curOrder.copy(status = "EXECUTED", executedAt = now(), updatedAt = now(), cycleId = newId ?: curOrder.cycleId))
                dao.updatePlan(curPlan.copy(status = "EXECUTED", resultCycleId = newId, executedAt = now(), updatedAt = now()))
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = newId ?: curPlan.sourceCycleId, symbol = plan.symbol, type = "REENTRY_EXECUTED", message = "Re-entry executed @ ${p(price)} -> cycle #$newId", price = price, createdAt = now()))
            } else {
                if (curOrder.status == "ACTIVE") dao.updateOrder(curOrder.copy(status = "REJECTED", updatedAt = now()))
                dao.updatePlan(curPlan.copy(status = "CANCELLED", updatedAt = now()))
                dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = curPlan.sourceCycleId, symbol = plan.symbol, type = "REENTRY_FAILED", message = "Re-entry execution failed: ${res.exceptionOrNull()?.message}", price = price, createdAt = now()))
            }
        }
    }

    private suspend fun processActiveOrdersUnlocked(context: Context, sym: String, price: Double) {
        val orders = dao.getActiveOrders(stageId).filter { it.symbol == sym }
        for (o in orders) {
            if (o.status != "ACTIVE") continue
            if (o.orderType == "REENTRY") {
                val plan = o.sourcePlanId?.let { dao.getPlan(it) } ?: continue
                executeReentryOrder(context, dao.getOrder(o.id) ?: o, plan, price)
            }
        }
    }

    private suspend fun closeCycleUnlocked(cycle: PaperCycleEntity, price: Double, reason: String, settings: LocalCoinSettings) {
        val t = now()
        db.withTransaction {
            val fresh = dao.getCycle(cycle.id) ?: return@withTransaction
            if (fresh.status == "CLOSED") return@withTransaction
            val entries = dao.getEntries(fresh.id).filter { it.remainingQuantity > 1e-12 }
            val avg = fresh.averageEntryPrice ?: price
            var grossPnl = 0.0
            var totalMargin = 0.0
            var closeFee = 0.0
            for (e in entries) {
                val q = e.remainingQuantity
                grossPnl += if (fresh.direction == "LONG") (price - e.price) * q else (e.price - price) * q
                totalMargin += e.margin
                closeFee += q * price * feeRate
                dao.updateEntry(e.copy(remainingQuantity = 0.0, status = "CLOSED", closedAt = t))
            }
            val netPnl = grossPnl - closeFee
            val wallet = ensureWalletUnlocked()
            val release = max(0.0, totalMargin)
            val tpOrder = dao.getActiveOrder(fresh.id, "TAKE_PROFIT")
            val slOrder = dao.getActiveOrder(fresh.id, "STOP_LOSS")
            if (reason == "TAKE_PROFIT") {
                tpOrder?.let { dao.updateOrder(it.copy(status = "EXECUTED", executedAt = t, updatedAt = t, quantity = fresh.totalQuantity)) }
                slOrder?.let { dao.updateOrder(it.copy(status = "CANCELLED", updatedAt = t)) }
            } else if (reason == "STOP_LOSS") {
                slOrder?.let { dao.updateOrder(it.copy(status = "EXECUTED", executedAt = t, updatedAt = t, quantity = fresh.totalQuantity)) }
                tpOrder?.let { dao.updateOrder(it.copy(status = "CANCELLED", updatedAt = t)) }
            } else {
                dao.cancelActiveOrdersForCycle(stageId, fresh.id, "CANCELLED", t)
            }
            for (pl in dao.getPlansForCycle(fresh.id)) {
                if (pl.planType != "REENTRY" && pl.status !in listOf("ACTIVATED", "EXECUTED", "CANCELLED")) dao.updatePlan(pl.copy(status = "CANCELLED", updatedAt = t))
            }
            dao.updateCycle(fresh.copy(status = "CLOSED", closePrice = price, realizedPnl = netPnl, closeReason = reason, updatedAt = t, closedAt = t))
            dao.upsertWallet(wallet.copy(availableBalance = wallet.availableBalance + release + netPnl, reservedMargin = max(0.0, wallet.reservedMargin - release), realizedPnl = wallet.realizedPnl + netPnl, updatedAt = t))
            dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = fresh.id, symbol = fresh.symbol, type = reason, message = "Cycle closed ($reason) @ ${p(price)} | net PnL ${fmt(netPnl)} USDT", price = price, createdAt = t))

            // Re-entry only after successful Take Profit
            if (reason == "TAKE_PROFIT" && fresh.reentryEnabled && fresh.reentryPercent > 0) {
                val existing = dao.getReentryPlanForSource(stageId, fresh.id)
                if (existing == null) {
                    val trigger = if (fresh.direction == "LONG") price * (1 - fresh.reentryPercent / 100.0) else price * (1 + fresh.reentryPercent / 100.0)
                    dao.insertPlan(PaperPlanEntity(stage = stageId, sourceCycleId = fresh.id, symbol = fresh.symbol, direction = fresh.direction, planType = "REENTRY", status = "WAITING", triggerPercent = fresh.reentryPercent, referencePrice = price, triggerPrice = trigger, margin = fresh.initialMargin, leverage = fresh.leverage, createdAt = t, updatedAt = t))
                    dao.insertEvent(PaperEventEntity(stage = stageId, cycleId = fresh.id, symbol = fresh.symbol, type = "REENTRY_PLANNED", message = "Re-entry planned after TP: trigger @ ${p(trigger)} (${fresh.reentryPercent}%)", price = trigger, createdAt = t))
                }
            }
            // STOP_LOSS: no re-entry
        }
    }

    private suspend fun synchronizeStateUnlocked() {
        try {
            val t = now()
            val legacy = try { dao.getWaitingReentries(stageId) } catch (_: Exception) { emptyList() }
            for (r in legacy) {
                if (dao.getReentryPlanForSource(stageId, r.sourceCycleId) == null) {
                    dao.insertPlan(PaperPlanEntity(stage = stageId, sourceCycleId = r.sourceCycleId, symbol = r.symbol, direction = r.direction, planType = "REENTRY", status = "WAITING", referencePrice = r.referenceClosePrice, triggerPrice = r.triggerPrice, createdAt = r.createdAt, updatedAt = t))
                    dao.updateReentry(r.copy(status = "TRIGGERED", triggeredAt = t))
                }
            }
            for (c in dao.getActiveCycles(stageId)) {
                val avg = c.averageEntryPrice ?: continue
                val tp = c.takeProfitPrice ?: tpPrice(c.direction, avg, c.tpPercent)
                val sl = c.stopLossPrice ?: slPrice(c.direction, avg, c.slPercent)
                if (c.takeProfitPrice == null || c.stopLossPrice == null) dao.updateCycle(c.copy(takeProfitPrice = tp, stopLossPrice = sl, updatedAt = t))
                if (dao.getActiveOrder(c.id, "TAKE_PROFIT") == null) dao.insertOrder(PaperOrderEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, direction = c.direction, orderType = "TAKE_PROFIT", triggerPrice = tp, quantity = c.totalQuantity, status = "ACTIVE", createdAt = c.createdAt, updatedAt = t))
                if (dao.getActiveOrder(c.id, "STOP_LOSS") == null) dao.insertOrder(PaperOrderEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, direction = c.direction, orderType = "STOP_LOSS", triggerPrice = sl, quantity = c.totalQuantity, status = "ACTIVE", createdAt = c.createdAt, updatedAt = t))
                if (c.averagingEnabled && !c.averagingDone && dao.getPlanForCycle(c.id, "AVERAGING") == null) dao.insertPlan(PaperPlanEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, direction = c.direction, planType = "AVERAGING", status = "WAITING", triggerPercent = c.averagingPercent, warningPercent = c.warningPercent, referencePrice = avg, triggerPrice = adverseTriggerPrice(c.direction, avg, c.averagingPercent), margin = c.averagingMargin, leverage = c.leverage, createdAt = t, updatedAt = t))
                if (c.oppositeEnabled && !c.oppositeDone && dao.getPlanForCycle(c.id, "OPPOSITE_SIDE") == null) dao.insertPlan(PaperPlanEntity(stage = stageId, cycleId = c.id, symbol = c.symbol, direction = c.direction, planType = "OPPOSITE_SIDE", status = "WAITING", triggerPercent = c.oppositePercent, warningPercent = c.warningPercent, referencePrice = avg, triggerPrice = adverseTriggerPrice(c.direction, avg, c.oppositePercent), margin = c.oppositeMargin, leverage = c.leverage, createdAt = t, updatedAt = t))
                if (c.averagingDone) dao.getPlanForCycle(c.id, "AVERAGING")?.let { if (it.status !in listOf("EXECUTED", "CANCELLED")) dao.updatePlan(it.copy(status = "CANCELLED", updatedAt = t)) }
                if (c.oppositeDone) dao.getPlanForCycle(c.id, "OPPOSITE_SIDE")?.let { if (it.status !in listOf("EXECUTED", "CANCELLED")) dao.updatePlan(it.copy(status = "CANCELLED", updatedAt = t)) }
            }
        } catch (_: Exception) { }
    }

    private suspend fun ensureWalletUnlocked(): PaperWalletEntity =
        dao.getWallet(stageId) ?: newWallet().also { dao.upsertWallet(it) }

    private fun tpPrice(direction: String, avg: Double, tpPercent: Double): Double =
        if (direction == "LONG") avg * (1 + tpPercent / 100.0) else avg * (1 - tpPercent / 100.0)
    private fun slPrice(direction: String, avg: Double, slPercent: Double): Double =
        if (direction == "LONG") avg * (1 - slPercent / 100.0) else avg * (1 + slPercent / 100.0)
    private fun adverseTriggerPrice(direction: String, ref: Double, pct: Double): Double =
        if (direction == "LONG") ref * (1 - pct / 100.0) else ref * (1 + pct / 100.0)
    private fun favorablePct(direction: String, avg: Double, price: Double): Double =
        if (direction == "LONG") ((price - avg) / avg) * 100.0 else ((avg - price) / avg) * 100.0
    private fun adversePct(direction: String, avg: Double, price: Double): Double =
        if (direction == "LONG") ((avg - price) / avg) * 100.0 else ((price - avg) / avg) * 100.0
    private fun now() = System.currentTimeMillis()
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
    private fun q(v: Double) = String.format(java.util.Locale.US, "%.6f", v)
    private fun p(v: Double) = com.ansardeo.binancebot.util.PriceFormatter.formatPrice(v)
}

object PaperEngineProvider {
    private val engines = java.util.concurrent.ConcurrentHashMap<String, PaperEngine>()
    fun get(context: Context, stage: TradingStage = TradingStage.STAGE_1): PaperEngine =
        engines.getOrPut(stage.id) { PaperEngine(PaperDatabase.get(context), stage) }
    fun getAll(context: Context): List<PaperEngine> = TradingStage.entries.map { get(context, it) }
}
