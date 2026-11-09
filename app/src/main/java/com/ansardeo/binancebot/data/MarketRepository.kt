package com.ansardeo.binancebot.data

import com.ansardeo.binancebot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MarketRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val backendBase = BuildConfig.BACKEND_BASE_URL

    suspend fun getOverview(): MarketOverview = withContext(Dispatchers.IO) {
        // Prefer backend overview if available; fallback to direct public Binance data.
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
