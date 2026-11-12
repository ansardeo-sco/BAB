package com.ansardeo.binancebot.data

import com.ansardeo.binancebot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MarketRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val backendBase = BuildConfig.BACKEND_BASE_URL
    private val exchangeRulesCache = ConcurrentHashMap<String, Pair<SymbolFilterRules, Long>>()
    private val rulesTtlMs = 10 * 60 * 1000L

    suspend fun getOverview(): MarketOverview = withContext(Dispatchers.IO) {
        try {
            val backend = getJson("$backendBase/market/overview")
            if (backend != null && backend.optBoolean("data_available", false)) {
                return@withContext parseOverview(backend)
            }
        } catch (_: Exception) {
        }
        fetchDirectOverview()
    }

    suspend fun getTicker(symbol: String): Ticker? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        try {
            val t24 = getJson("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$sym")
            val pm = getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$sym")
            if (t24 == null) return@withContext null
            val mark = pm?.optString("markPrice")?.toDoubleOrNull()
            return@withContext Ticker(
                symbol = sym,
                lastPrice = t24.optString("lastPrice").toDoubleOrNull(),
                priceChangePercent = t24.optString("priceChangePercent").toDoubleOrNull(),
                quoteVolume = t24.optString("quoteVolume").toDoubleOrNull(),
                markPrice = mark ?: t24.optString("lastPrice").toDoubleOrNull(),
                highPrice = t24.optString("highPrice").toDoubleOrNull(),
                lowPrice = t24.optString("lowPrice").toDoubleOrNull(),
                openPrice = t24.optString("openPrice").toDoubleOrNull(),
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getMarkPrice(symbol: String): Double? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        try {
            val pm = getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$sym")
            pm?.optString("markPrice")?.toDoubleOrNull()
                ?: getJson("https://fapi.binance.com/fapi/v1/ticker/price?symbol=$sym")
                    ?.optString("price")?.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** USDT-M USDT perpetual symbols (public). */
    suspend fun getUsdmSymbols(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val ex = getJson("https://fapi.binance.com/fapi/v1/exchangeInfo") ?: return@withContext Result.failure(IOException("No exchange info"))
            val arr = ex.optJSONArray("symbols") ?: JSONArray()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sym = s.optString("symbol", "")
                val status = s.optString("status", "")
                val quote = s.optString("quoteAsset", "")
                val contract = s.optString("contractType", "")
                if (status == "TRADING" && quote == "USDT" && contract == "PERPETUAL" && sym.endsWith("USDT")) out.add(sym)
            }
            Result.success(out.distinct().sorted())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetch and cache Binance USDⓈ-M symbol filters for Stage 1 rule simulation/validation. */
    suspend fun getSymbolRules(symbol: String): SymbolRulesResult = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank()) return@withContext SymbolRulesResult(null, error = "Invalid symbol")
        val now = System.currentTimeMillis()
        exchangeRulesCache[sym]?.let { (r, ts) -> if (now - ts <= rulesTtlMs) return@withContext SymbolRulesResult(r) }
        try {
            val ex = getJson("https://fapi.binance.com/fapi/v1/exchangeInfo?symbol=$sym")
                ?: return@withContext SymbolRulesResult(exchangeRulesCache[sym]?.first, stale = exchangeRulesCache[sym] != null, error = "Unable to load exchange filters")
            val arr = ex.optJSONArray("symbols") ?: JSONArray()
            val s = arr.optJSONObject(0) ?: return@withContext SymbolRulesResult(exchangeRulesCache[sym]?.first, stale = exchangeRulesCache[sym] != null, error = "Symbol not found in exchangeInfo")
            val rules = parseSymbolRules(sym, s)
            exchangeRulesCache[sym] = rules to now
            SymbolRulesResult(rules)
        } catch (e: Exception) {
            val cached = exchangeRulesCache[sym]?.first
            SymbolRulesResult(cached, stale = cached != null, error = e.message ?: "Failed to load exchange filters")
        }
    }

    fun getCachedSymbolRules(symbol: String): SymbolFilterRules? = exchangeRulesCache[symbol.uppercase().trim()]?.first

    private fun parseSymbolRules(symbol: String, s: JSONObject): SymbolFilterRules {
        var tickSize: BigDecimal? = null
        var stepSize: BigDecimal? = null
        var minQty: BigDecimal? = null
        var maxQty: BigDecimal? = null
        var minNotional: BigDecimal? = null
        val filters = s.optJSONArray("filters") ?: JSONArray()
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: continue
            when (f.optString("filterType")) {
                "PRICE_FILTER" -> tickSize = f.optString("tickSize").toBigDecimalOrNullSafe()
                "LOT_SIZE" -> {
                    stepSize = f.optString("stepSize").toBigDecimalOrNullSafe()
                    minQty = f.optString("minQty").toBigDecimalOrNullSafe()
                    maxQty = f.optString("maxQty").toBigDecimalOrNullSafe()
                }
                "MIN_NOTIONAL", "NOTIONAL" -> {
                    val n = f.optString("notional").toBigDecimalOrNullSafe() ?: f.optString("minNotional").toBigDecimalOrNullSafe()
                    if (n != null) minNotional = n
                }
            }
        }
        return SymbolFilterRules(
            symbol = symbol,
            tickSize = tickSize,
            stepSize = stepSize,
            minQty = minQty,
            maxQty = maxQty,
            minNotional = minNotional,
            pricePrecision = s.optInt("pricePrecision").takeIf { s.has("pricePrecision") },
            quantityPrecision = s.optInt("quantityPrecision").takeIf { s.has("quantityPrecision") }
        )
    }

    private fun String.toBigDecimalOrNullSafe(): BigDecimal? = try { if (isBlank()) null else BigDecimal(this) } catch (_: Exception) { null }

    /** Actual 24h + 7d market statistics from Binance public data. RSI clearly identified by interval/method. */
    suspend fun getMarketStats(symbol: String): MarketStats = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank()) return@withContext MarketStats(symbol = sym, livePrice = null, change24hPercent = null, volume24h = null, high24h = null, low24h = null, rsi24h = null, high7d = null, low7d = null, rsi7d = null, error = "No symbol selected")
        try {
            val t24 = getJson("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$sym")
            val pm = getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$sym")
            val live = pm?.optString("markPrice")?.toDoubleOrNull() ?: t24?.optString("lastPrice")?.toDoubleOrNull()
            val change = t24?.optString("priceChangePercent")?.toDoubleOrNull()
            val vol = t24?.optString("quoteVolume")?.toDoubleOrNull()
            val high24 = t24?.optString("highPrice")?.toDoubleOrNull()
            val low24 = t24?.optString("lowPrice")?.toDoubleOrNull()
            val klines1h = getKlines(sym, "1h", 40)
            val klines4h = getKlines(sym, "4h", 60)
            val closes1h = klines1h.mapNotNull { it.close }
            val closes4h = klines4h.mapNotNull { it.close }
            val rsi24 = calculateRsiWilder(closes1h, 14)
            val rsi7d = calculateRsiWilder(closes4h, 14)
            val high7d = klines4h.mapNotNull { it.high }.maxOrNull()
            val low7d = klines4h.mapNotNull { it.low }.minOrNull()
            val stale = live == null && t24 == null
            MarketStats(
                symbol = sym, livePrice = live, change24hPercent = change, volume24h = vol,
                high24h = high24, low24h = low24, rsi24h = rsi24,
                high7d = high7d, low7d = low7d, rsi7d = rsi7d,
                isStale = stale, error = if (stale) "Market data unavailable" else null
            )
        } catch (e: Exception) {
            MarketStats(symbol = sym, livePrice = null, change24hPercent = null, volume24h = null, high24h = null, low24h = null, rsi24h = null, high7d = null, low7d = null, rsi7d = null, error = e.message ?: "Failed to load market statistics")
        }
    }

    private data class Kline(val high: Double?, val low: Double?, val close: Double?)

    private fun getKlines(symbol: String, interval: String, limit: Int): List<Kline> {
        val text = getText("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=$limit") ?: return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<Kline>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            fun d(idx: Int) = k.optString(idx, "").toDoubleOrNull()
            out.add(Kline(high = d(2), low = d(3), close = d(4)))
        }
        return out
    }

    internal fun calculateRsiWilder(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0) gain += delta else loss += abs(delta)
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            val g = if (delta > 0) delta else 0.0
            val l = if (delta < 0) abs(delta) else 0.0
            avgGain = ((avgGain * (period - 1)) + g) / period
            avgLoss = ((avgLoss * (period - 1)) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        val rsi = 100.0 - (100.0 / (1.0 + rs))
        return if (rsi.isFinite()) rsi.coerceIn(0.0, 100.0) else null
    }

    private fun fetchDirectOverview(): MarketOverview {
        return try {
            val body = getText("https://fapi.binance.com/fapi/v1/ticker/24hr")
                ?: return unavailable("Unable to load live prices")
            val arr = JSONArray(body)
            parseBinanceArray(arr)
        } catch (e: Exception) {
            unavailable(e.message)
        }
    }

    private fun parseOverview(json: JSONObject): MarketOverview {
        fun arr(key: String): List<Ticker> {
            val a = json.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { tickerFromJson(a.optJSONObject(it)) }
        }
        val symbols = mutableMapOf<String, Ticker>()
        val sm = json.optJSONObject("symbols")
        if (sm != null) {
            val keys = sm.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tickerFromJson(sm.optJSONObject(k))?.let { symbols[it.symbol] = it }
            }
        }
        (arr("volume_leaders") + arr("gainers") + arr("losers")).forEach { symbols.putIfAbsent(it.symbol, it) }
        val status = json.optString("status", "Live")
        return MarketOverview(
            status = status,
            live = json.optBoolean("live", status == "Live"),
            stale = json.optBoolean("stale", false),
            dataAvailable = json.optBoolean("data_available", symbols.isNotEmpty()),
            lastUpdate = json.optString("last_update", json.optString("timestamp", null)),
            error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null,
            volumeLeaders = arr("volume_leaders"),
            gainers = arr("gainers"),
            losers = arr("losers"),
            symbols = symbols,
        )
    }

    private fun parseBinanceArray(arr: JSONArray): MarketOverview {
        val list = mutableListOf<Ticker>()
        val map = mutableMapOf<String, Ticker>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol", "").uppercase()
            if (sym.isBlank() || !sym.endsWith("USDT") || sym.contains("_")) continue
            val last = o.optString("lastPrice").toDoubleOrNull() ?: continue
            val t = Ticker(
                symbol = sym,
                lastPrice = last,
                priceChangePercent = o.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                quoteVolume = o.optString("quoteVolume").toDoubleOrNull() ?: 0.0,
                markPrice = last,
            )
            list.add(t)
            map[sym] = t
        }
        val vol = list.sortedByDescending { it.quoteVolume ?: 0.0 }.take(10)
        val gain = list.filter { (it.priceChangePercent ?: 0.0) > 0 }
            .sortedByDescending { it.priceChangePercent ?: 0.0 }.take(10)
        val loss = list.filter { (it.priceChangePercent ?: 0.0) < 0 }
            .sortedBy { it.priceChangePercent ?: 0.0 }.take(10)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        return MarketOverview("Live", live = true, stale = false, dataAvailable = list.isNotEmpty(),
            lastUpdate = now, error = null, volumeLeaders = vol, gainers = gain, losers = loss, symbols = map)
    }

    private fun tickerFromJson(o: JSONObject?): Ticker? {
        o ?: return null
        val sym = o.optString("symbol", "").uppercase()
        if (sym.isBlank()) return null
        val last = if (o.has("lastPrice") && !o.isNull("lastPrice")) o.optDouble("lastPrice").takeIf { !it.isNaN() } else null
        return Ticker(
            symbol = sym,
            lastPrice = last,
            priceChangePercent = if (o.has("priceChangePercent")) o.optDouble("priceChangePercent").takeIf { !it.isNaN() } else null,
            quoteVolume = if (o.has("quoteVolume")) o.optDouble("quoteVolume").takeIf { !it.isNaN() } else null,
            markPrice = if (o.has("markPrice")) o.optDouble("markPrice").takeIf { !it.isNaN() } else last,
        )
    }

    private fun unavailable(msg: String?): MarketOverview = MarketOverview(
        status = "Unavailable", live = false, stale = false, dataAvailable = false,
        lastUpdate = null, error = msg, volumeLeaders = emptyList(), gainers = emptyList(),
        losers = emptyList(), symbols = emptyMap()
    )

    private fun getJson(url: String): JSONObject? {
        val text = getText(url) ?: return null
        return try { JSONObject(text) } catch (_: Exception) { null }
    }

    private fun getText(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string()
        }
    }
}
