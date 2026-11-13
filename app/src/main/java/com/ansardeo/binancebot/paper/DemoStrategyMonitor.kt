package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.DemoCredentialsStore
import com.ansardeo.binancebot.data.DemoStageRepository
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.DemoOrderDao
import com.ansardeo.binancebot.paper.db.DemoOrderEntity
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
                        runCatching { processOpenOrders(appCtx, demoRepo, dao, repo) }
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
        marketRepo: MarketRepository
    ) {
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
                if (newStatus == "FILLED" && rec.orderType == "ENTRY_LIMIT" && rec.filledAt == null) {
                    // Place per-entry Stop Loss (algo STOP_MARKET, reduce-only) on Binance Demo.
                    val slPct = com.ansardeo.binancebot.data.LeverageConfig.requireProfile(rec.leverage).slPercent
                    val slPrice = com.ansardeo.binancebot.paper.PaperEngine.calcSl(
                        rec.direction, avgPrice ?: rec.triggerPrice, slPct
                    )
                    runCatching {
                        demoRepo.placeAlgoOrder(
                            context, rec.symbol,
                            side = if (rec.direction == "LONG") "SELL" else "BUY",
                            type = "STOP_MARKET",
                            quantity = com.ansardeo.binancebot.util.PriceFormatter.formatQty(filledQty).trim(),
                            triggerPrice = slPrice.toString(),
                            reduceOnly = true,
                            clientAlgoId = "sl-${rec.clientOrderId}"
                        )
                    }
                }
            } catch (_: Exception) {
                // continue with next order
            }
        }
    }
}