package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.PaperDatabase
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background price monitor for BAB simulated stages (Stage 1 & 2).
 * Fetches live public Binance prices for symbols with active work and evaluates engine conditions.
 * NEVER submits real Binance orders. Stage 3 is not auto-executed here.
 */
object PaperMonitor {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        job = scope.launch {
            val repo = MarketRepository()
            val db = PaperDatabase.get(appCtx)
            val dao = db.dao()
            while (isActive) {
                try {
                    val engines = listOf(
                        PaperEngineProvider.get(appCtx, TradingStage.STAGE_1),
                        PaperEngineProvider.get(appCtx, TradingStage.STAGE_2)
                    )
                    val workSymbols = mutableSetOf<String>()
                    for (st in listOf(TradingStage.STAGE_1.id, TradingStage.STAGE_2.id)) {
                        runCatching { dao.getActiveCycles(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol }
                        runCatching { dao.getPendingEntries(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol }
                        runCatching { dao.getPendingReentryPlans(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol }
                    }
                    val hasWork = workSymbols.isNotEmpty() || engines.any { runCatching { it.hasPendingWork() }.getOrDefault(false) }
                    if (hasWork) {
                        val prices = mutableMapOf<String, Double>()
                        // Overview prices (bulk)
                        runCatching { repo.getOverview() }.getOrNull()?.symbols?.forEach { (k, v) ->
                            val px = v.markPrice ?: v.lastPrice
                            if (px != null && px.isFinite() && px > 0.0) prices[k] = px
                        }
                        // Ensure all work symbols have a price
                        for (sym in workSymbols) {
                            if (prices[sym] == null) {
                                val px = runCatching { repo.getMarkPrice(sym) }.getOrNull()
                                if (px != null && px.isFinite() && px > 0.0) prices[sym] = px
                            }
                        }
                        if (prices.isNotEmpty()) {
                            engines.forEach { e -> runCatching { e.processAll(appCtx, prices) } }
                        }
                    }
                    delay(if (hasWork) 10_000L else 30_000L)
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
}
