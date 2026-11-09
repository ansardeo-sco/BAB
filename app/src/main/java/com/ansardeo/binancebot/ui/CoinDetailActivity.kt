package com.ansardeo.binancebot.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.binancebot.R
import com.ansardeo.binancebot.data.FavoritesStore
import com.ansardeo.binancebot.data.MarketRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coin_detail)
        symbol = (intent.getStringExtra(EXTRA_SYMBOL) ?: "BTCUSDT").uppercase()
        findViewById<TextView>(R.id.txtDetailSymbol).text = symbol
        findViewById<Button>(R.id.btnCloseDetail).setOnClickListener { finish() }
        val favBtn = findViewById<TextView>(R.id.btnFavoriteToggle)
        favBtn.setOnClickListener {
            val res = FavoritesStore.toggle(this, symbol)
            renderFavorite(res.isFavorite)
            Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
        }
        renderFavorite(FavoritesStore.isFavorite(this, symbol))
        load()
    }

    private fun renderFavorite(isFav: Boolean) {
        val btn = findViewById<TextView>(R.id.btnFavoriteToggle)
        btn.text = if (isFav) "★ Remove Favorite" else "☆ Add to Favorites"
    }

    private fun load() {
        lifecycleScope.launch {
            findViewById<TextView>(R.id.txtDetailStatus).text = "● Loading live market data..."
            val t = market.getTicker(symbol)
            val status = findViewById<TextView>(R.id.txtDetailStatus)
            if (t == null) {
                status.text = "● Unavailable — unable to load live data"
                status.setTextColor(ContextCompat.getColor(this@CoinDetailActivity, R.color.red))
            } else {
                status.text = "● Live — Binance USDⓈ-M Futures (public, read-only)"
                status.setTextColor(ContextCompat.getColor(this@CoinDetailActivity, R.color.green))
                val price = t.markPrice ?: t.lastPrice
                findViewById<TextView>(R.id.txtMarkPrice).text = PriceFormatter.formatPrice(price)
                val ch = findViewById<TextView>(R.id.txtChange)
                ch.text = PriceFormatter.formatPercent(t.priceChangePercent)
                val pct = t.priceChangePercent ?: 0.0
                ch.setTextColor(ContextCompat.getColor(this@CoinDetailActivity,
                    when { pct > 0 -> R.color.green; pct < 0 -> R.color.red; else -> R.color.text_muted }))
                findViewById<TextView>(R.id.txtHigh).text = PriceFormatter.formatPrice(t.highPrice)
                findViewById<TextView>(R.id.txtLow).text = PriceFormatter.formatPrice(t.lowPrice)
                findViewById<TextView>(R.id.txtVolume).text = formatVolume(t.quoteVolume)
                val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                findViewById<TextView>(R.id.txtUpdated).text = utc.format(Date())
            }
        }
        refresh?.let { handler.removeCallbacks(it) }
        val r = Runnable { load() }
        refresh = r
        handler.postDelayed(r, 15000)
    }

    private fun formatVolume(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        return when {
            v >= 1_000_000_000 -> String.format(Locale.US, "%,.2fB", v / 1_000_000_000)
            v >= 1_000_000 -> String.format(Locale.US, "%,.2fM", v / 1_000_000)
            v >= 1_000 -> String.format(Locale.US, "%,.2fK", v / 1_000)
            else -> String.format(Locale.US, "%,.2f", v)
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
