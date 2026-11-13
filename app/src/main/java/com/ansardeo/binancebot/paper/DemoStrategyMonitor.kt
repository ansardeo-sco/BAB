package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.DemoCredentialsStore
import com.ansardeo.binancebot.data.DemoStageRepository
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.DemoOrderDao
import com.ansardeo.binancebot.paper.db.DemoOrderEntity
import com.ansardeo.binancebot.paper.PaperEngine
import com.ansardeo.binancebot.paper.db.PaperDatabase
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 2 (Binance USDⓈ-M Futures Demo) strategy monitor.
 *
 * Runs the same strategy RULES as Stage 1 (per-entry SL, trailing stop, averaging at -60% ROI,
 * re-entry after trailing stop, opposite-side, liquidation) but executes through Binance Demo:
 *  - polls DemoOrder records for fill/cancel/reject status
 *  - on confirmed fill: creates the entry record and places a per-entry Stop Loss (algo STOP_MARKET)
 *  - monitors trailing stop activation/hit and closes the entry via a reduce-only market order
 *  - triggers averaging at -60% ROI (places two LIMIT averaging orders)
 *  - plans re-entry after trailing-stop close
 *
 * This monitor never submits to live Binance. Demo credentials are loaded from encrypted storage.
 * Stage 1 (PaperMonitor) and Stage 3 remain untouched.
 */
object DemoStrategyMonitor {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        job = scope.launch {
            val repo = MarketRepository()
            val demoRepo = DemoStageRepository()
            val db = PaperDatabase.get(appCtx)
            val dao = db.demoOrderDao()
            while (isActive) {
                try {
                    if (TradingStage.STAGE_2.isDemoTrading && DemoCredentialsStore.hasCredentials(appCtx)) {
                        val engine = DemoStrategyEngine(db)
                        runCatching { engine.detectAndRegisterPositions(appCtx) }
                        runCatching { processOpenOrders(appCtx, demoRepo, dao, repo, engine) }
                        // Fetch prices and run strategy (trailing/averaging/re-entry).
                        runCatching {
                            val ov = repo.getOverview()
                            val prices = mutableMapOf<String, Double>()
                            ov.symbols.forEach { (k, v) -> val px = v.markPrice ?: v.lastPrice; if (px != null && px.isFinite() && px > 0) prices[k] = px }
                            if (prices.isNotEmpty()) engine.processAll(appCtx, prices)
                        }
                    }
                    delay(10_000L)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Exception) {
                    delay(15_000L)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        started.set(false)
    }

    private suspend fun processOpenOrders(
        context: Context,
        demoRepo: DemoStageRepository,
        dao: DemoOrderDao,
        marketRepo: MarketRepository,
        engine: DemoStrategyEngine
    ) {
        val db = PaperDatabase.get(context)
        val open = dao.getOpen("STAGE_2")
        if (open.isEmpty()) return
        for (rec in open) {
            try {
                val res = demoRepo.getOrder(context, rec.symbol, clientOrderId = rec.clientOrderId)
                if (!res.ok) continue
                val o = res.data ?: continue
                val newStatus = o.optString("status", rec.status)
                val filledQty = o.optString("executedQty").toDoubleOrNull() ?: rec.filledQuantity
                val avgPrice = o.optString("avgPrice").toDoubleOrNull() ?: rec.filledPrice
                val exchangeId = o.optString("orderId").ifBlank { rec.exchangeOrderId }
                val now = System.currentTimeMillis()
                dao.update(rec.copy(
                    status = newStatus,
                    exchangeOrderId = exchangeId,
                    filledQuantity = filledQty,
                    filledPrice = avgPrice,
                    filledAt = if (newStatus == "FILLED" && rec.filledAt == null) now else rec.filledAt,
                    updatedAt = now
                ))
                if (newStatus == "FILLED" && rec.orderType == "CLOSE" && rec.filledAt == null) {
                    // Confirm exit fill: update entry/cycle/history only after Binance confirms.
                    runCatching {
                        val entry = db.dao().getEntry(rec.entryId ?: -1L)
                        val cycle = if (entry != null) db.dao().getCycle(entry.cycleId) else null
                        if (entry != null && cycle != null) {
                            val filledQty = if (filledQty > 0) filledQty else rec.quantity
                            val exitPrice = avgPrice ?: entry.trailingStopPrice ?: rec.triggerPrice
                            val gross = PaperEngine.realizedPnl(entry.direction, entry.fillPrice ?: exitPrice, exitPrice, filledQty)
                            val marginPart = entry.margin * (filledQty / max(entry.filledQuantity, 1e-12))
                            val pnl = gross
                            val pnlPct = if (marginPart > 1e-12) (pnl / marginPart) * 100.0 else 0.0
                            val idem = "stage2close|${cycle.id}|${entry.id}|${System.currentTimeMillis()}"
                            db.dao().insertHistoryClose(PaperHistoryCloseEntity(
                                stage = TradingStage.STAGE_2.id, cycleId = cycle.id, entryId = entry.id,
                                symbol = cycle.symbol, direction = entry.direction,
                                entryFillPrice = entry.fillPrice ?: 0.0, exitFillPrice = exitPrice,
                                entryMargin = marginPart, leverage = entry.leverage, closedQuantity = filledQty,
                                realizedPnlUsdt = pnl, realizedPnlPercent = pnlPct,
                                closingReason = entry.closeReason ?: "TRAILING_STOP",
                                entryTimestamp = entry.filledAt ?: entry.createdAt, exitTimestamp = now,
                                idempotencyKey = idem
                            ))
                            db.dao().updateEntry(entry.copy(status = "CLOSED", remainingQuantity = 0.0, closedAt = now, updatedAt = now))
                            val remaining = db.dao().getOpenFilledEntries(cycle.id).sumOf { it.remainingQuantity }
                            db.dao().updateCycle(cycle.copy(status = if (remaining <= 1e-12) "CLOSED" else cycle.status, totalRemainingQuantity = remaining, updatedAt = now, closeReason = if (remaining <= 1e-12) (entry.closeReason ?: "TRAILING_STOP") else cycle.closeReason, closedAt = if (remaining <= 1e-12) now else cycle.closedAt))
                        }
                    }
                }
            } catch (_: Exception) {
                // continue with next order
            }
        }
    }
}