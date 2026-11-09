package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.TradingStage
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level price monitor for BAB simulated stages (Stage 1 & Stage 2).
 * Fetches live public Binance prices and evaluates demo engine conditions.
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
            while (isActive) {
                try {
                    val engines = listOf(
                        PaperEngineProvider.get(appCtx, TradingStage.STAGE_1),
                        PaperEngineProvider.get(appCtx, TradingStage.STAGE_2)
                    )
                    val hasWork = engines.any { runCatching { it.hasPendingWork() }.getOrDefault(false) }
                    if (hasWork) {
                        val overview = repo.getOverview()
                        val prices = overview.symbols.mapNotNull { (k, v) ->
                            val px = v.markPrice ?: v.lastPrice
                            if (px != null && px.isFinite() && px > 0.0) k to px else null
                        }.toMap()
                        if (prices.isNotEmpty()) {
                            engines.forEach { e -> runCatching { e.processAll(appCtx, prices) } }
                        }
                    }
                    delay(if (hasWork) 12_000L else 30_000L)
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
