package com.ansardeo.binancebot.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.binancebot.MainActivity
import com.ansardeo.binancebot.R
import com.ansardeo.binancebot.data.FavoritesStore
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.MarketStats
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CoinDetailActivity : AppCompatActivity() {
    private val market = MarketRepository()
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Runnable? = null
    private lateinit var symbol: String
    private var currentPrice: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coin_detail)
        symbol = (intent.getStringExtra(EXTRA_SYMBOL) ?: "BTCUSDT").uppercase()
        findViewById<TextView>(R.id.txtDetailSymbol).text = symbol

        val favBtn = findViewById<TextView>(R.id.btnFavoriteToggle)
        favBtn.setOnClickListener {
            val res = FavoritesStore.toggle(this, symbol)
            renderFavorite(res.isFavorite)
            Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
        }
        renderFavorite(FavoritesStore.isFavorite(this, symbol))

        findViewById<Button>(R.id.btnBackHome).setOnClickListener {
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to", "Home")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
            finish()
        }
        findViewById<Button>(R.id.btnTradeNow).setOnClickListener {
            val i = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to", "Trade")
                putExtra("trade_symbol", symbol)
                currentPrice?.let { putExtra("trade_price", it) }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
            finish()
        }

        load()
    }

    private fun renderFavorite(isFav: Boolean) {
        val btn = findViewById<TextView>(R.id.btnFavoriteToggle)
        btn.text = if (isFav) "★ Remove Favorite" else "☆ Add to Favorites"
    }

    private fun load() {
        lifecycleScope.launch {
            val status = findViewById<TextView>(R.id.txtDetailStatus)
            val statsState = findViewById<TextView>(R.id.txtStatsState)
            status.text = "Loading live market data..."
            status.setTextColor(ContextCompat.getColor(this@CoinDetailActivity, R.color.text_muted))
            statsState.text = "Loading market statistics..."
            statsState.setTextColor(ContextCompat.getColor(this@CoinDetailActivity, R.color.text_muted))

            val stats = market.getMarketStats(symbol)
            renderStats(stats)
        }
        refresh?.let { handler.removeCallbacks(it) }
        val r = Runnable { load() }
        refresh = r
        handler.postDelayed(r, 15000)
    }

    private fun renderStats(stats: MarketStats) {
        val status = findViewById<TextView>(R.id.txtDetailStatus)
        val statsState = findViewById<TextView>(R.id.txtStatsState)
        val price = stats.livePrice
        currentPrice = price
        findViewById<TextView>(R.id.txtMarkPrice).text = PriceFormatter.formatPrice(price)

        val ch = stats.change24hPercent
        val changeTv = findViewById<TextView>(R.id.txtChange)
        changeTv.text = "24h Change: ${PriceFormatter.formatPercent(ch)}"
        val pct = ch ?: 0.0
        changeTv.setTextColor(ContextCompat.getColor(this, when {
            ch == null -> R.color.text_muted
            pct > 0 -> R.color.green
            pct < 0 -> R.color.red
            else -> R.color.text_muted
        }))

        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timeStr = utc.format(Date(stats.fetchedAt))
        findViewById<TextView>(R.id.txtUpdated).text = "Last update: $timeStr"

        if (stats.error != null) {
            val color = if (stats.isStale) R.color.accent_yellow else R.color.red
            status.text = if (stats.isStale) "Stale data: ${stats.error}" else "Error: ${stats.error}"
            status.setTextColor(ContextCompat.getColor(this, color))
            statsState.text = if (stats.isStale) "Statistics may be stale. ${stats.error}" else "Failed to load statistics: ${stats.error}"
            statsState.setTextColor(ContextCompat.getColor(this, color))
        } else {
            status.text = "Live — Binance USDⓈ-M Futures (public, read-only)"
            status.setTextColor(ContextCompat.getColor(this, R.color.green))
            statsState.text = if (stats.isStale) "Stale statistics (refreshing...)" else "Actual market statistics loaded."
            statsState.setTextColor(ContextCompat.getColor(this, if (stats.isStale) R.color.accent_yellow else R.color.green))
        }

        fun rsiTxt(v: Double?) = if (v == null) "--" else String.format(Locale.US, "%.0f", v)
        findViewById<TextView>(R.id.txtHigh7d).text = PriceFormatter.formatPrice(stats.high7d)
        findViewById<TextView>(R.id.txtLow7d).text = PriceFormatter.formatPrice(stats.low7d)
        findViewById<TextView>(R.id.txtRsi7d).text = rsiTxt(stats.rsi7d)
        findViewById<TextView>(R.id.txtHigh24h).text = PriceFormatter.formatPrice(stats.high24h)
        findViewById<TextView>(R.id.txtLow24h).text = PriceFormatter.formatPrice(stats.low24h)
        findViewById<TextView>(R.id.txtRsi24h).text = rsiTxt(stats.rsi24h)

        findViewById<TextView>(R.id.txtVol24h).text = "24h Trading Volume: ${formatVolume(stats.volume24h)}"

        fun dist(from: Double?, to: Double?): String {
            if (from == null || to == null || from == 0.0) return "--"
            val pct = ((to - from) / from) * 100.0
            return PriceFormatter.formatPercent(pct)
        }
        val d24hH = dist(price, stats.high24h)
        val d24hL = dist(price, stats.low24h)
        val d7dH = dist(price, stats.high7d)
        val d7dL = dist(price, stats.low7d)
        findViewById<TextView>(R.id.txtDist24h).text = "Distance from current to 24h High/Low: High $d24hH, Low $d24hL"
        findViewById<TextView>(R.id.txtDist7d).text = "Distance from current to 7d High/Low: High $d7dH, Low $d7dL"
    }

    private fun formatVolume(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        return when {
            v >= 1_000_000_000 -> String.format(Locale.US, "%,.2fB USDT", v / 1_000_000_000)
            v >= 1_000_000 -> String.format(Locale.US, "%,.2fM USDT", v / 1_000_000)
            v >= 1_000 -> String.format(Locale.US, "%,.2fK USDT", v / 1_000)
            else -> String.format(Locale.US, "%,.2f USDT", v)
        }
    }

    override fun onDestroy() {
        refresh?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
