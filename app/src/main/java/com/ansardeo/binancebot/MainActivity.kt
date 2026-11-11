package com.ansardeo.binancebot

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.binancebot.BuildConfig
import com.ansardeo.binancebot.data.*
import com.ansardeo.binancebot.paper.PaperEngine
import com.ansardeo.binancebot.paper.PaperEngineProvider
import com.ansardeo.binancebot.paper.db.PaperCycleEntity
import com.ansardeo.binancebot.paper.db.PaperCycleWithEntries
import com.ansardeo.binancebot.paper.db.PaperDatabase
import com.ansardeo.binancebot.paper.db.PaperHistoryCloseEntity
import com.ansardeo.binancebot.paper.db.PaperOrderEntity
import com.ansardeo.binancebot.paper.db.PaperReentryPlanEntity
import com.ansardeo.binancebot.ui.CoinDetailActivity
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val marketRepo = MarketRepository()
    private val paperDb by lazy { PaperDatabase.get(this) }
    private fun stage() = StageStore.current(this)
    private fun engine(): PaperEngine = PaperEngineProvider.get(this, stage())
    private fun stageId() = stage().id

    private val bg get() = ContextCompat.getColor(this, R.color.bg_primary)
    private val card get() = ContextCompat.getColor(this, R.color.bg_card)
    private val yellow get() = ContextCompat.getColor(this, R.color.accent_yellow)
    private val white get() = ContextCompat.getColor(this, R.color.text_primary)
    private val muted get() = ContextCompat.getColor(this, R.color.text_muted)
    private val green get() = ContextCompat.getColor(this, R.color.green)
    private val red get() = ContextCompat.getColor(this, R.color.red)
    private val inputBg get() = ContextCompat.getColor(this, R.color.bg_input)
    private val lockedColor get() = ContextCompat.getColor(this, R.color.locked)

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>
    private var currentPage = "Home"
    private var pendingTradeSymbol: String? = null
    private var pendingTradePrice: Double? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var refreshGen: Long = 0L
    private var lastOverview: MarketOverview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTradeIntent(intent)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.contentFrame)
        navItems = listOf(
            findViewById(R.id.navDashboard),
            findViewById(R.id.navManual),
            findViewById(R.id.navPositions),
            findViewById(R.id.navSettings),
            findViewById(R.id.navMore)
        )
        navIcons = listOf(
            findViewById(R.id.navDashboardIcon),
            findViewById(R.id.navManualIcon),
            findViewById(R.id.navPositionsIcon),
            findViewById(R.id.navSettingsIcon),
            findViewById(R.id.navMoreIcon)
        )
        navLabels = listOf(
            findViewById(R.id.navDashboardLabel),
            findViewById(R.id.navManualLabel),
            findViewById(R.id.navPositionsLabel),
            findViewById(R.id.navSettingsLabel),
            findViewById(R.id.navMoreLabel)
        )
        navItems[0].setOnClickListener { showPage("Home") }
        navItems[1].setOnClickListener { showPage("Trade") }
        navItems[2].setOnClickListener { showPage("Future") }
        navItems[3].setOnClickListener { showPage("Settings") }
        navItems[4].setOnClickListener { showPage("More") }
        lifecycleScope.launch {
            TradingStage.entries.forEach { runCatching { PaperEngineProvider.get(this@MainActivity, it).getWallet() } }
        }
        updateTopStatus()
        val nav = intent.getStringExtra("navigate_to")
        if (nav == "Trade" || pendingTradeSymbol != null) showPage("Trade") else if (nav == "Home") showPage("Home") else showPage("Home")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        handleTradeIntent(intent)
        val nav = intent?.getStringExtra("navigate_to")
        if (nav == "Trade" || pendingTradeSymbol != null) showPage("Trade")
        else if (nav == "Home") showPage("Home")
    }

    private fun handleTradeIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("trade_symbol")?.let { pendingTradeSymbol = it.uppercase() }
        if (intent.hasExtra("trade_price")) pendingTradePrice = intent.getDoubleExtra("trade_price", Double.NaN).takeIf { !it.isNaN() }
    }

    private fun updateTopStatus() {
        // Stage badge removed from the header per UI requirements; stage is managed in Settings.
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 16): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun cardView(): LinearLayout = column().apply { background = rounded(card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
    private fun addSpace(parent: LinearLayout, h: Int = 10) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }

    private fun priceTv(value: String, size: Float = 13f, color: Int = white, bold: Boolean = true): TextView =
        text(value, size, color, bold).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            typeface = Typeface.MONOSPACE
        }

    private fun addLabel(parent: LinearLayout, label: String, value: String, valueColor: Int = white, isPrice: Boolean = false) {
        val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val labelTv = text(label, 12f, muted)
        line.addView(labelTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(48) })
        val v = if (isPrice) priceTv(value, 13f, valueColor) else text(value, 13f, valueColor, true).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        line.addView(v)
        parent.addView(line)
        addSpace(parent, 8)
    }
    private fun addCard(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val box = cardView()
        box.addView(text(title, 14f, yellow, true))
        if (subtitle != null) { addSpace(box, 4); box.addView(text(subtitle, 12f, muted)) }
        addSpace(parent, 12); parent.addView(box); return box
    }

    private fun pageContainer(): LinearLayout {
        val outer = column().apply { setPadding(dp(12), dp(12), dp(12), dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(outer) }
        content.removeAllViews(); content.addView(scroll); return outer
    }

    private fun openDetail(symbol: String) {
        startActivity(Intent(this, CoinDetailActivity::class.java).putExtra(CoinDetailActivity.EXTRA_SYMBOL, symbol))
    }

    private fun showPage(page: String) {
        currentPage = page
        if (page != "Home") stopRefresh()
                navItems.forEachIndexed { i, _ ->
            val sel = (page == "Home" && i == 0) || (page == "Trade" && i == 1) || (page == "Future" && i == 2) || (page == "Settings" && i == 3) || (page == "More" && i == 4)
            val c = if (sel) yellow else muted
            navIcons[i].setColorFilter(c)
            navLabels[i].setTextColor(c)
            navLabels[i].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        when (page) {
            "Home" -> dashboardPage()
            "Trade" -> manualPage()
            "Future" -> positionsPage()
            "Settings" -> settingsPage()
            "More" -> morePage()
        }
    }

    private fun stopRefresh() { refreshGen++; refreshRunnable?.let { mainHandler.removeCallbacks(it) }; refreshRunnable = null }

    // ---------------- HOME ----------------
    private fun dashboardPage() {
        stopRefresh()
        val root = pageContainer()
        addSpace(root, 2)

        val balCard = addCard(root, "WALLET BALANCE - ${stage().shortLabel}")
        val balText = priceTv("-- USDT", 20f, green); balCard.addView(balText)
        val balSub = text("Loading ${stage().shortLabel} wallet...", 11f, muted); addSpace(balCard, 4); balCard.addView(balSub)
        if (stage() == TradingStage.STAGE_3) balCard.addView(text("Stage 3 live balance integration is disabled by default. No real orders.", 11f, red))

        val marketCard = addCard(root, "LIVE CRYPTOCURRENCY PRICES (READ-ONLY)", "Tap any coin for details. Prices use dynamic decimals, right-aligned.")
        val mStatus = text("Loading market data...", 12f, yellow, true); marketCard.addView(mStatus)
        val mUpdated = text("Last update: --", 11f, muted); marketCard.addView(mUpdated); addSpace(marketCard, 8)

        fun section(title: String): LinearLayout {
            marketCard.addView(text(title, 11f, muted, true)); addSpace(marketCard, 4)
            val box = column(); marketCard.addView(box); addSpace(marketCard, 8)
            box.addView(text("Loading...", 12f, muted)); return box
        }
        val secVol = section("TOP 10 BY 24H QUOTE VOLUME")
        val secGain = section("TOP 10 GAINERS (24h %)")
        val secLoss = section("TOP 10 LOSERS (24h %)")
        val secFav = section("MY FAVORITES")

        fun renderWallet() {
            lifecycleScope.launch {
                try {
                    val w = engine().getWallet()
                    val active = paperDb.dao().getActiveCyclesWithEntries(stageId())
                    val prices = lastOverview?.symbols ?: emptyMap()
                    var unreal = 0.0
                    for (cwe in active) {
                        val px = prices[cwe.cycle.symbol]?.markPrice ?: prices[cwe.cycle.symbol]?.lastPrice ?: continue
                        val avg = cwe.cycle.averageEntryPrice ?: continue
                        val qty = cwe.cycle.totalRemainingQuantity.takeIf { it > 0 } ?: cwe.cycle.totalQuantity
                        unreal += if (cwe.cycle.direction == "LONG") (px - avg) * qty else (avg - px) * qty
                    }
                    val equity = w.availableBalance + w.reservedMargin + unreal
                    balText.text = PriceFormatter.formatUsdt(equity)
                    balSub.text = "Available ${PriceFormatter.formatUsdt(w.availableBalance)} | Reserved ${PriceFormatter.formatUsdt(w.reservedMargin)} | Unrealized ${PriceFormatter.formatPnl(unreal)} | Realized ${PriceFormatter.formatPnl(w.realizedPnl)}"
                } catch (_: Exception) { balSub.text = "Unable to load virtual wallet." }
            }
        }

        val gen = refreshGen
        fun refreshMarket() {
            if (gen != refreshGen || currentPage != "Home") return
            lifecycleScope.launch {
                val ov = marketRepo.getOverview()
                if (gen != refreshGen || currentPage != "Home") return@launch
                lastOverview = ov
                if (!ov.dataAvailable) {
                    mStatus.text = "Unavailable - ${ov.error ?: "unable to load live prices"} (retrying)"
                    mStatus.setTextColor(red)
                    mUpdated.text = "Last update: --"
                    listOf(secVol, secGain, secLoss).forEach { it.removeAllViews(); it.addView(text("Unable to load live prices. Retrying...", 12f, red)) }
                    renderFavorites(secFav, null)
                } else {
                    val label = if (ov.stale) "Stale" else if (ov.live) "Live" else ov.status
                    mStatus.text = "$label - Binance USDT-M Futures (public, read-only)"
                    mStatus.setTextColor(if (ov.stale) yellow else green)
                    mUpdated.text = "Last update: ${ov.lastUpdate ?: "--"} (auto-refresh)"
                    renderTickerList(secVol, ov.volumeLeaders)
                    renderTickerList(secGain, ov.gainers)
                    renderTickerList(secLoss, ov.losers)
                    renderFavorites(secFav, ov)
                    renderWallet()
                }
            }
            val r = Runnable { refreshMarket() }; refreshRunnable = r; mainHandler.postDelayed(r, 15000)
        }
        renderWallet(); refreshMarket()
    }

    private fun percentBadge(pct: Double?): TextView {
        val v = pct ?: 0.0
        val bg = when {
            pct == null -> inputBg
            v > 0.0 -> green
            v < 0.0 -> red
            else -> inputBg
        }
        val label = if (pct == null) "--" else PriceFormatter.formatPercent(pct)
        return text(label, 11.5f, white, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            // Fixed-size, sharp 90-degree corners so every % change badge lines up exactly.
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 0f }
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(28))
            setPadding(dp(2), 0, dp(2), 0)
        }
    }

    private fun renderTickerList(target: LinearLayout, items: List<Ticker>) {
        target.removeAllViews()
        if (items.isEmpty()) { target.addView(text("No live data available.", 12f, muted)); return }
        items.take(10).forEach { t ->
            val line = row().apply {
                isClickable = true; isFocusable = true
                setPadding(dp(10), dp(13), dp(10), dp(13))
                setOnClickListener { openDetail(t.symbol) }
            }
            // 1. Symbol (neutral)
            line.addView(text(t.symbol, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(10) })
            // 2. Price (neutral)
            line.addView(priceTv(PriceFormatter.formatPrice(t.lastPrice ?: t.markPrice), 12f, white).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 1.0f).apply { marginEnd = dp(10) })
            // 3. Change % box - fixed-width slot keeps every badge aligned
            val badgeWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            badgeWrap.addView(percentBadge(t.priceChangePercent), LinearLayout.LayoutParams(dp(70), dp(28)))
            line.addView(badgeWrap, LinearLayout.LayoutParams(dp(78), -2))
            target.addView(line)
            target.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderFavorites(target: LinearLayout, ov: MarketOverview?) {
        target.removeAllViews()
        val favs = FavoritesStore.load(this)
        if (favs.isEmpty()) { target.addView(text("No favorites yet. Open a coin and tap Favorite.", 12f, muted)); return }
        for (sym in favs) {
            val t = ov?.symbols?.get(sym)
            val line = row().apply { isClickable = true; setPadding(dp(10), dp(13), dp(10), dp(13)); setOnClickListener { openDetail(sym) } }
            line.addView(text(sym, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(10) })
            line.addView(priceTv(PriceFormatter.formatPrice(t?.lastPrice ?: t?.markPrice), 12f, white).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 1.0f).apply { marginEnd = dp(10) })
            val badgeWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            badgeWrap.addView(percentBadge(t?.priceChangePercent), LinearLayout.LayoutParams(dp(70), dp(28)))
            line.addView(badgeWrap, LinearLayout.LayoutParams(dp(78), -2))
            target.addView(line)
            target.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    // ---------------- TRADE (Manual tab renamed) ----------------
    private fun manualPage() /* Trade tab */ {
        stopRefresh()
        content.removeAllViews()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stageBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(card, 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        stageBar.addView(text("${stage().title}", 13f, if (stage()==TradingStage.STAGE_3) red else green, true), LinearLayout.LayoutParams(0, -2, 1f))
        stageBar.addView(Button(this).apply { text = "Change Stage"; textSize = 11f; isAllCaps = false; setTextColor(0xFF000000.toInt()); background = rounded(yellow, 10); setOnClickListener {
            val opts = TradingStage.entries.map { it.title }.toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle("Select Trading Stage").setItems(opts) { _, i -> StageStore.setCurrent(this@MainActivity, TradingStage.entries[i]); updateTopStatus(); showPage("Trade") }.show()
        } })
        outer.addView(stageBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
        val view = LayoutInflater.from(this).inflate(R.layout.activity_manual_entry, outer, false)
        outer.addView(view)
        content.addView(ScrollView(this).apply { addView(outer) })

        // Market Statistics are shown in Home coin details only
        view.findViewById<View?>(R.id.boxStats)?.visibility = View.GONE

        val symbolInput = view.findViewById<AutoCompleteTextView>(R.id.actvSymbol)
        val leverageSpinner = view.findViewById<Spinner>(R.id.spEntryLeverage)
        val marginInput = view.findViewById<EditText>(R.id.etEntryMargin)
        val entryPriceInput = view.findViewById<EditText>(R.id.etEntryPrice)
        val summaryBox = view.findViewById<LinearLayout>(R.id.boxEntrySummary)
        val livePriceTv = view.findViewById<TextView>(R.id.txtLivePrice)
        val liveStatus = view.findViewById<TextView>(R.id.txtLivePriceStatus)
        val btnOpenLong = view.findViewById<Button>(R.id.btnOpenLong)
        val btnOpenShort = view.findViewById<Button>(R.id.btnOpenShort)
        val result = view.findViewById<TextView>(R.id.txtManualResult)

        var currentLivePrice: Double? = null
        var userEditedEntryPrice = false
        val prefillSymbol = pendingTradeSymbol?.uppercase()
        val prefillPrice = pendingTradePrice
        pendingTradeSymbol = null
        pendingTradePrice = null

        fun selectedSym(): String = symbolInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        fun selectedLev(): Int = LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(leverageSpinner.selectedItemPosition) { 6 }

        leverageSpinner.adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" })
        leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(6).coerceAtLeast(0))
        leverageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        symbolInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>()))

        fun addSummaryRow(label: String, value: String, valueColor: Int = white, bold: Boolean = true) {
            val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
            line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
            line.addView(text(value, 12f, valueColor, bold).apply {
                gravity = Gravity.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            })
            summaryBox.addView(line)
            summaryBox.addView(View(this), LinearLayout.LayoutParams(-1, dp(6)))
        }

        fun updateSummary() {
            val sym = selectedSym().ifBlank { "--" }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (_: Exception) { 6 }
            val profile = LeverageConfig.requireProfile(lev)
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            val qty = if (intended != null && intended > 0 && margin > 0) (margin * lev) / intended else null
            val tsLong = if (intended != null && intended > 0) intended * (1 + profile.trailingActivationPercent/100.0) else null
            val slLong = if (intended != null && intended > 0) intended * (1 - profile.slPercent/100.0) else null
            val tsShort = if (intended != null && intended > 0) intended * (1 - profile.trailingActivationPercent/100.0) else null
            val slShort = if (intended != null && intended > 0) intended * (1 + profile.slPercent/100.0) else null
            summaryBox.removeAllViews()
            addSummaryRow("Stage", stage().title, if (stage()==TradingStage.STAGE_3) red else green)
            addSummaryRow("Symbol", sym)
            addSummaryRow("Live Price", "${PriceFormatter.formatPrice(currentLivePrice)} USDT")
            addSummaryRow("Intended LIMIT Entry", "${PriceFormatter.formatPrice(intended)} USDT")
            addSummaryRow("Margin", "${PriceFormatter.formatUsdt(margin)} / ${lev}x")
            addSummaryRow("Fixed % (TS act / cb)", "${profile.trailingActivationPercent}% / ${profile.callbackRatePercent}%")
            addSummaryRow("Fixed % (SL / New Entry Dist)", "${profile.slPercent}% / ${profile.newEntryDistancePercent}%")
            addSummaryRow("Estimated Qty", if (qty != null) PriceFormatter.formatQty(qty) else "--")
            addSummaryRow("Long TS act / SL", "${PriceFormatter.formatPrice(tsLong)} / ${PriceFormatter.formatPrice(slLong)}", green)
            addSummaryRow("Short TS act / SL", "${PriceFormatter.formatPrice(tsShort)} / ${PriceFormatter.formatPrice(slShort)}", red)
            val note = text("All entries are LIMIT orders. Stage 1/2 simulated only. No MARKET orders.", 11f, muted)
            summaryBox.addView(note)
        }

        fun loadSymbolLive(sym: String) {
            if (sym.isBlank() || !sym.endsWith("USDT")) return
            val myGen = ++refreshGen
            liveStatus.setTextColor(muted); liveStatus.text = "Loading live price for $sym..."
            fun tick() {
                if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return
                lifecycleScope.launch {
                    val px = marketRepo.getMarkPrice(sym)
                    if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return@launch
                    currentLivePrice = px
                    livePriceTv.text = "Live price: ${PriceFormatter.formatPrice(currentLivePrice)} USDT"
                    liveStatus.text = if (currentLivePrice != null) "Live public mark/price (auto-refresh)" else "Price unavailable - retrying"
                    liveStatus.setTextColor(if (currentLivePrice != null) muted else red)
                    if (!userEditedEntryPrice && currentLivePrice != null && entryPriceInput.text.isNullOrBlank()) {
                        entryPriceInput.setText(PriceFormatter.formatPrice(currentLivePrice))
                    }
                    updateSummary()
                }
                val r = Runnable { tick() }; refreshRunnable = r; mainHandler.postDelayed(r, 12000)
            }
            tick()
        }

        fun onSymbolChanged() { userEditedEntryPrice = false; loadSymbolLive(selectedSym()) }

        lifecycleScope.launch {
            val symbols = marketRepo.getUsdmSymbols().getOrElse {
                val fb = (lastOverview?.symbols?.keys?.toList() ?: emptyList()) + FavoritesStore.load(this@MainActivity)
                fb.distinct().sorted().ifEmpty { listOf("BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT") }
            }
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, symbols)
            symbolInput.setAdapter(adapter)
            if (!prefillSymbol.isNullOrBlank()) {
                symbolInput.setText(prefillSymbol, false)
                if (prefillPrice != null && prefillPrice > 0) {
                    userEditedEntryPrice = true
                    entryPriceInput.setText(PriceFormatter.formatPrice(prefillPrice))
                }
            } else {
                val fav = FavoritesStore.load(this@MainActivity).firstOrNull()
                if (!fav.isNullOrBlank() && symbolInput.text.isNullOrBlank()) symbolInput.setText(fav, false)
            }
            val s = selectedSym(); if (s.isNotBlank()) loadSymbolLive(s)
        }

        fun setButtonsEnabled(enabled: Boolean) { btnOpenLong.isEnabled = enabled; btnOpenShort.isEnabled = enabled }

        fun submitEntry(dir: String) {
            val sym = selectedSym()
            if (sym.isBlank() || !sym.endsWith("USDT")) { toast("Select a valid USDT-M symbol"); return }
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            if (intended == null || intended <= 0.0) { toast("Enter a valid intended LIMIT entry price"); return }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            if (margin <= 0.0) { toast("Enter a valid margin"); return }
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (e: Exception) { toast(e.message ?: "Invalid leverage"); return }
            val s = stage()
            val warn = if (s == TradingStage.STAGE_3) "STAGE 3 LIVE TRADING is disabled in this build. No real order will be placed." else "${s.shortLabel} is simulated. No real Binance order will be submitted."
            AlertDialog.Builder(this)
                .setTitle("Confirm $dir LIMIT Entry")
                .setMessage("Stage: ${s.title}\n$warn\n\n$sym $dir\nIntended LIMIT price: ${PriceFormatter.formatPrice(intended)}\nLive price: ${PriceFormatter.formatPrice(currentLivePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${lev}x\n\nPlace this $dir LIMIT entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    if (s == TradingStage.STAGE_3) { result.setTextColor(red); result.text = "[X] Stage 3 is disabled by default and real order submission is not enabled in this build."; return@setPositiveButton }
                    setButtonsEnabled(false); result.setTextColor(muted); result.text = "Placing simulated $dir LIMIT entry..."
                    lifecycleScope.launch {
                        val res = engine().openInitialLimit(this@MainActivity, sym, dir, margin, lev, intended)
                        setButtonsEnabled(true)
                        if (res.ok) {
                            result.setTextColor(green); result.text = "[OK] ${res.message} (Cycle #${res.cycleId})"; toast("$dir LIMIT entry placed")
                            runCatching { currentLivePrice?.let { engine().processAll(this@MainActivity, mapOf(sym to it)) } }
                        } else {
                            result.setTextColor(red); result.text = "[X] ${res.message}"
                        }
                    }
                }.setNegativeButton("CANCEL", null).show()
        }

        symbolInput.setOnItemClickListener { _, _, _, _ -> onSymbolChanged() }
        symbolInput.setOnEditorActionListener { _, _, _ -> onSymbolChanged(); false }
        symbolInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus && selectedSym().isNotBlank()) loadSymbolLive(selectedSym()) }
        entryPriceInput.addTextChangedListener(SimpleWatcher { if (entryPriceInput.hasFocus()) userEditedEntryPrice = true; updateSummary() })
        marginInput.addTextChangedListener(SimpleWatcher { updateSummary() })
        leverageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateSummary() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnOpenLong.setOnClickListener { submitEntry("LONG") }
        btnOpenShort.setOnClickListener { submitEntry("SHORT") }

        updateSummary()
    }


    private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    private fun fmtTime(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

    // ---------------- FUTURE ----------------
    private fun positionsPage() /* Future tab */ {
        stopRefresh(); updateTopStatus()
        val root = pageContainer()
        val tabBar = row().apply { background = rounded(card, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar); addSpace(root, 8)
        val subBar = row().apply { background = rounded(card, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(subBar); addSpace(root, 8)
        val body = column(); root.addView(body)

        var primaryTab = 0 // 0 Position, 1 Open Order, 2 History
        var positionSub = 0 // 0 Open, 1 Closed
        var orderSub = 0 // 0 Basic, 1 Conditional
        lateinit var primaryViews: List<TextView>
        lateinit var subViews: List<TextView>

        fun stylePrimary(posCount: Int, openCount: Int, histCount: Int) {
            val labels = listOf("Position ($posCount)", "Open Order ($openCount)", "History ($histCount)")
            primaryViews.forEachIndexed { i, tv ->
                tv.text = labels[i]; val sel = i == primaryTab
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 10) else null
            }
        }
        fun styleSub(a: String, b: String) {
            val labels = listOf(a, b)
            subViews.forEachIndexed { i, tv ->
                val sel = if (primaryTab == 0) i == positionSub else i == orderSub
                tv.text = labels[i]
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 8) else null
            }
        }
        fun render() {
            lifecycleScope.launch {
                try {
                    val dao = paperDb.dao(); val sid = stageId()
                    val ov = (lastOverview ?: runCatching { marketRepo.getOverview().also { lastOverview = it } }.getOrNull())
                    val prices = ov?.symbols ?: emptyMap()
                    val priceState = when {
                        ov == null -> "Loading"
                        !ov.dataAvailable -> "Error"
                        ov.stale -> "Stale"
                        else -> "Live"
                    }
                    val active = dao.getActiveCyclesWithEntries(sid).filter { cwe ->
                        cwe.cycle.status in listOf("PENDING_ENTRY","ACTIVE","CLOSING") &&
                            (cwe.cycle.totalRemainingQuantity > 1e-12 || cwe.entries.any { e -> e.status == "PENDING" || e.remainingQuantity > 1e-12 })
                    }
                    // Position -> Open: only genuinely executed and currently open positions
                    val openPositions = active.filter { cwe ->
                        cwe.cycle.status != "PENDING_ENTRY" &&
                            cwe.entries.any { e -> e.status == "FILLED" && e.remainingQuantity > 1e-12 }
                    }
                    val allOrders = dao.getActiveOrders(sid).filter { it.status == "ACTIVE" }
                    val cyclesAll = dao.getRecentCycles(sid, 300)
                    val cycleById = cyclesAll.associateBy { it.id }
                    val openEntryOrders = allOrders.filter { it.orderType == "ENTRY_LIMIT" }
                    val protectiveOrders = allOrders.filter { it.orderType == "CYCLE_TP" || it.orderType == "ENTRY_SL" }
                    val reentries = dao.getPendingReentryPlans(sid).filter { it.status !in listOf("EXECUTED","CANCELLED","FAILED") }
                    val history = dao.getHistoryCloses(sid, 500)
                    // Conditional opposite triggers (awaiting averaging execution) - pending trigger, shown in Conditional
                    val conditionalOpposites = cyclesAll.filter { c ->
                        c.oppositeEnabled && !c.oppositeDone && c.status in listOf("PENDING_ENTRY","ACTIVE","CLOSING") &&
                            dao.getEntries(c.id).any { it.entryType == "AVERAGING" && it.status == "PENDING" } &&
                            dao.countOpenCycles(sid, c.symbol, if (c.direction == "LONG") "SHORT" else "LONG") == 0
                    }
                    val basicCount = openEntryOrders.size + reentries.size
                    val conditionalCount = protectiveOrders.groupBy { it.cycleId }.size + conditionalOpposites.size
                    stylePrimary(openPositions.size, basicCount, history.size)
                    subBar.visibility = if (primaryTab == 2) View.GONE else View.VISIBLE
                    if (primaryTab == 0) styleSub("Open (${openPositions.size})", "Closed (${history.size})")
                    else if (primaryTab == 1) styleSub("Basic ($basicCount)", "Conditional ($conditionalCount)")
                    body.removeAllViews()
                    when (primaryTab) {
                        0 -> if (positionSub == 0) renderPositionsOpen(body, openPositions, prices, priceState) else renderPositionsClosed(body, history)
                        1 -> if (orderSub == 0) renderOrdersBasic(body, openEntryOrders, reentries, prices, priceState) else renderOrdersConditional(body, protectiveOrders, conditionalOpposites, cycleById, prices, priceState)
                        2 -> renderHistoryPrimary(body, history)
                    }
                } catch (e: Exception) {
                    body.removeAllViews(); body.addView(text("Unable to load", 12f, red))
                }
            }
        }
        primaryViews = listOf("Position","Open Order","History").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); textSize = 12f; setOnClickListener { if (primaryTab != i) { primaryTab = i; render() } } }
                .also { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        subViews = listOf("A","B").mapIndexed { i, _ ->
            TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f; setOnClickListener {
                if (primaryTab == 0) { if (positionSub != i) { positionSub = i; render() } } else if (primaryTab == 1) { if (orderSub != i) { orderSub = i; render() } }
            } }.also { subBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        stylePrimary(0,0,0); styleSub("Open","Closed"); render()
    }

    private fun makeItemCard(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return box
    }

    private fun currentPriceText(prices: Map<String, Ticker>, symbol: String, state: String): String {
        val px = prices[symbol]?.markPrice ?: prices[symbol]?.lastPrice
        return when {
            px != null -> PriceFormatter.formatPrice(px)
            state == "Loading" -> "Loading..."
            state == "Stale" -> "Stale"
            state == "Error" -> "Unavailable"
            else -> "--"
        }
    }

    private fun renderPositionsOpen(body: LinearLayout, active: List<PaperCycleWithEntries>, prices: Map<String, Ticker>, priceState: String) {
        if (active.isEmpty()) { body.addView(text("No open positions.", 13f, muted)); return }
        for (cwe in active) {
            val c = cwe.cycle
            val openEntries = cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            if (openEntries.isEmpty()) continue
            val px = prices[c.symbol]?.markPrice ?: prices[c.symbol]?.lastPrice
            for (e in openEntries) {
                val entryPrice = e.fillPrice
                val entryQty = e.remainingQuantity
                val baseQty = kotlin.math.max(e.filledQuantity, 1e-12)
                val entryMargin = e.margin * (entryQty / baseQty)
                val unreal = if (px != null && entryPrice != null && entryQty > 1e-12) {
                    if (c.direction == "LONG") (px - entryPrice) * entryQty else (entryPrice - px) * entryQty
                } else null
                val roi = if (unreal != null && entryMargin > 1e-12) (unreal / entryMargin) * 100.0 else null
                val box = makeItemCard(body)
                val header = row().apply { isClickable = true; setOnClickListener { openDetail(c.symbol) } }
                header.addView(text("${c.symbol}  ${c.direction}", 15f, if (c.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(text("${c.leverage}x", 12f, muted, true)); box.addView(header)
                addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
                addLabel(box, "Entry Price", PriceFormatter.formatPrice(entryPrice), white, true)
                addLabel(box, "Margin", PriceFormatter.formatUsdt(entryMargin), white, true)
                addLabel(box, "Trailing Stop", if (c.averageEntryPrice != null) "avg ${PriceFormatter.formatPrice(c.averageEntryPrice)} (act ${c.trailingActivationPercent}%, cb ${c.callbackRatePercent}%)" else "--", white, true)
                addLabel(box, "Entry SL", PriceFormatter.formatPrice(e.slPrice), white, true)
                addLabel(box, "ROI", PriceFormatter.formatPercent(roi), pnlColor(roi), true)
                addLabel(box, "Unrealized PNL", PriceFormatter.formatPnl(unreal), pnlColor(unreal), true)
            }
        }
    }
    private fun renderPositionsClosed(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No closed positions.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TP" -> green; "SL","LIQUIDATION" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Entry", PriceFormatter.formatPrice(h.entryFillPrice), white, true)
            addLabel(box, "Exit", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Qty", PriceFormatter.formatQty(h.closedQuantity))
            addLabel(box, "Margin / Lev", "${PriceFormatter.formatUsdt(h.entryMargin)} / ${h.leverage}x")
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} (${PriceFormatter.formatPercent(h.realizedPnlPercent)})", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "Closed", tf.format(java.util.Date(h.exitTimestamp)))
        }
    }

    private fun renderOrdersBasic(body: LinearLayout, entryOrders: List<PaperOrderEntity>, reentries: List<PaperReentryPlanEntity>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        for (o in entryOrders.sortedByDescending { it.createdAt }) {
            any = true
            val box = makeItemCard(body)
            val tag = when {
                o.clientOrderId.contains("-avg-") || o.clientOrderId.startsWith("avg-") -> "Averaging"
                o.clientOrderId.contains("-initial-") || o.clientOrderId.startsWith("init-") -> "Initial"
                o.clientOrderId.contains("opp") -> "Opposite"
                else -> "Entry"
            }
            val header = row()
            header.addView(text("${o.symbol} ${o.direction}", 15f, if (o.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(tag, 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(o.triggerPrice), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, o.symbol, priceState), white, true)
        }
        for (r in reentries.sortedByDescending { it.createdAt }) {
            any = true
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${r.symbol} ${r.direction}", 15f, if (r.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Re-entry", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(r.triggerPrice), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, r.symbol, priceState), white, true)
        }
        if (!any) body.addView(text("No open orders.", 13f, muted))
    }

    private fun renderOrdersConditional(body: LinearLayout, protectiveOrders: List<PaperOrderEntity>, conditionalOpposites: List<PaperCycleEntity>, cycleById: Map<Long, PaperCycleEntity>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        val byCycle = protectiveOrders.groupBy { it.cycleId }
        for ((cycleId, orders) in byCycle.toSortedMap()) {
            any = true
            val cycle = cycleById[cycleId]
            val sym = cycle?.symbol ?: orders.firstOrNull()?.symbol ?: "--"
            val tp = orders.firstOrNull { it.orderType == "CYCLE_TP" } // none in trailing-stop model
            // show cycle-level TP and a representative SL price per card context; margin is cycle initial+avg context - use cycle margins
            val sls = orders.filter { it.orderType == "ENTRY_SL" }
            val marginAmt = if (cycle != null) {
                // total margin for open executed entries approximated by initial+averaging when applicable
                cycle.initialMargin + if (cycle.averagingDone) cycle.averagingMargin else 0.0
            } else 0.0
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("$sym ${cycle?.direction ?: ""}".trim(), 15f, if (cycle?.direction == "SHORT") red else green, true), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(header)
            addLabel(box, "Margin Amount", PriceFormatter.formatUsdt(marginAmt), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, sym, priceState), white, true)
            addLabel(box, "Trailing Stop", if (cycle?.averageEntryPrice != null) "avg ${PriceFormatter.formatPrice(cycle.averageEntryPrice)} (act ${cycle.trailingActivationPercent}%, cb ${cycle.callbackRatePercent}%)" else "--", green, true)
            // If multiple independent SLs exist, show each as its own separate card per-entry context while keeping only 4 fields
            if (sls.isEmpty()) {
                addLabel(box, "SL Order Price", "--", red, true)
            } else if (sls.size == 1) {
                addLabel(box, "SL Order Price", PriceFormatter.formatPrice(sls.first().triggerPrice), red, true)
            } else {
                // first in this card, rest as separate cards (each must be separate clean card per requirement intent)
                addLabel(box, "SL Order Price", PriceFormatter.formatPrice(sls.first().triggerPrice), red, true)
                for (sl in sls.drop(1)) {
                    val c2 = makeItemCard(body)
                    val h2 = row(); h2.addView(text("$sym ${cycle?.direction ?: ""}".trim(), 15f, if (cycle?.direction == "SHORT") red else green, true), LinearLayout.LayoutParams(0,-2,1f)); c2.addView(h2)
                    val entryMargin = cycle?.initialMargin ?: marginAmt
                    addLabel(c2, "Margin Amount", PriceFormatter.formatUsdt(entryMargin), white, true)
                    addLabel(c2, "Current Price", currentPriceText(prices, sym, priceState), white, true)
                    addLabel(c2, "Trailing Stop", if (cycle?.averageEntryPrice != null) "avg ${PriceFormatter.formatPrice(cycle.averageEntryPrice)} (act ${cycle.trailingActivationPercent}%, cb ${cycle.callbackRatePercent}%)" else "--", green, true)
                    addLabel(c2, "SL Order Price", PriceFormatter.formatPrice(sl.triggerPrice), red, true)
                }
            }
        }
        // Conditional opposite-side triggers (awaiting averaging execution)
        for (c in conditionalOpposites) {
            any = true
            val box = makeItemCard(body)
            val oppositeDir = if (c.direction == "LONG") "SHORT" else "LONG"
            val header = row()
            header.addView(text("${c.symbol} $oppositeDir", 15f, if (oppositeDir == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Conditional", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Margin Amount", PriceFormatter.formatUsdt(c.averagingMargin), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
            // pending trigger; TP/SL prices not yet applicable
            addLabel(box, "TP Trigger Price", "--", green, true)
            addLabel(box, "SL Order Price", "--", red, true)
        }
        if (!any) body.addView(text("No conditional orders.", 13f, muted))
    }

    private fun renderHistoryPrimary(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No history.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TP" -> green; "SL","LIQUIDATION" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Entry", PriceFormatter.formatPrice(h.entryFillPrice), white, true)
            addLabel(box, "Exit", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Qty", PriceFormatter.formatQty(h.closedQuantity))
            addLabel(box, "Margin / Lev", "${PriceFormatter.formatUsdt(h.entryMargin)} / ${h.leverage}x")
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} (${PriceFormatter.formatPercent(h.realizedPnlPercent)})", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "Entry", tf.format(java.util.Date(h.entryTimestamp)))
            addLabel(box, "Exit", tf.format(java.util.Date(h.exitTimestamp)))
        }
    }

    private fun pnlColor(v: Double?): Int = when { v == null -> muted; v > 0.0 -> green; v < 0.0 -> red; else -> muted }


    // ---------------- SETTINGS ----------------
    private fun settingsPage() {
        val root = pageContainer()
        root.addView(text("Settings", 23f, white, true))
        root.addView(text("Default settings (applied to trading). TP/SL/Averaging/Re-entry percentages are auto-selected from the fixed leverage table (3x-20x) and are not editable.", 12f, muted))
        val st = addCard(root, "ACTIVE STAGE", "Current: ${stage().title}")
        addActionButton(st, "CHANGE STAGE") {
            val opts = TradingStage.entries.map { it.title }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Select Trading Stage").setItems(opts) { _, i -> StageStore.setCurrent(this, TradingStage.entries[i]); updateTopStatus(); showPage("Settings") }.show()
        }
        val box = column(); root.addView(box)
        box.removeAllViews()
        val s = LocalCoinSettingsStore.loadGlobal(this)
        val sc = addCard(box, "DEFAULT CONFIGURATION", "Saved locally. Trading % come from the fixed leverage table.")
        fun sw(label: String, checked: Boolean) = Switch(this).apply { text = label; isChecked = checked; setTextColor(white) }.also { sc.addView(it); addSpace(sc, 4) }
        val swL = sw("LONG enabled", s.enabledLong)
        val swS = sw("SHORT enabled", s.enabledShort)
        val swAvg = sw("Averaging enabled (max 1 per cycle)", s.averagingEnabled)
        val swRe = sw("Re-entry enabled (new cycle after TP pullback)", s.reentryEnabled)
        val swOpp = sw("Opposite-side on averaging fill (independent)", s.oppositeEnabled)
        fun et(label: String, value: String): EditText {
            val e = EditText(this).apply { setText(value); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg); setPadding(dp(10), dp(8), dp(10), dp(8)); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
            sc.addView(text(label, 12f, muted, true)); sc.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }); return e
        }
        val marginEt = et("Initial Margin (USDT)", String.format(Locale.US, "%.4f", s.marginAmount).trimEnd('0').trimEnd('.'))
        val avgMarginEt = et("Averaging Margin (USDT)", String.format(Locale.US, "%.4f", s.averagingMargin).trimEnd('0').trimEnd('.'))
        val oppMarginEt = et("Opposite-side Margin (USDT)", String.format(Locale.US, "%.4f", s.oppositeMargin).trimEnd('0').trimEnd('.'))
        sc.addView(text("Leverage (3x-20x)", 12f, muted, true))
        val levSpin = Spinner(this).apply { adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" }); setPopupBackgroundDrawable(rounded(inputBg, 10)); setBackgroundColor(inputBg); setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(s.leverage).coerceAtLeast(0)) }
        sc.addView(levSpin, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val info = text("", 12f, yellow); sc.addView(info)
        fun updateInfo() {
            val lev = LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { 6 }
            val p = LeverageConfig.requireProfile(lev)
            info.text = "Auto (read-only): TS act ${p.trailingActivationPercent}% | cb ${p.callbackRatePercent}% | SL ${p.slPercent}% | New entry dist ${p.newEntryDistancePercent}%"
        }
        levSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateInfo() }; override fun onNothingSelected(p0: AdapterView<*>?) {} }
        updateInfo()
        val msg = text("", 12f, muted); sc.addView(msg)
        addButton(sc, "SAVE SETTINGS", yellow).setOnClickListener {
            try {
                if (!swL.isChecked && !swS.isChecked) { msg.setTextColor(red); msg.text = "Enable at least one direction."; return@setOnClickListener }
                val lev = LeverageConfig.requireSupported(LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(levSpin.selectedItemPosition) { 6 })
                val ns = LocalCoinSettings.sanitized("__GLOBAL__", swL.isChecked, swS.isChecked, marginEt.text.toString().toDoubleOrNull() ?: s.marginAmount, lev, swOpp.isChecked, oppMarginEt.text.toString().toDoubleOrNull() ?: s.oppositeMargin, swAvg.isChecked, avgMarginEt.text.toString().toDoubleOrNull() ?: s.averagingMargin, swRe.isChecked)
                LocalCoinSettingsStore.saveGlobal(this, ns)
                msg.setTextColor(green); msg.text = "Saved. Percentages are fixed by leverage ${lev}x."; toast("Settings saved")
            } catch (e: Exception) { msg.setTextColor(red); msg.text = e.message ?: "Save failed" }
        }
    }


    // ---------------- MORE ----------------
    private fun morePage() {
        val root = pageContainer()
        root.addView(text("More", 23f, white, true))
        root.addView(text("BAB system status, wallet, and safety information", 12f, muted))
        val phases = addCard(root, "TRADING STAGES (INDEPENDENT)", "Stage data is isolated. Stage 1/2 never submit real Binance orders.")
        TradingStage.entries.forEach { stg ->
            val active = stage() == stg
            val desc = when (stg) {
                TradingStage.STAGE_1 -> "Live USDT-M prices + virtual 100 USDT. Simulated execution only."
                TradingStage.STAGE_2 -> "Demo trading with simulated wallet/orders/positions/history. No real funds."
                TradingStage.STAGE_3 -> "Live Binance USDT-M (scaffold). Disabled by default. This build does not submit real orders."
            }
            addSpace(phases, 6)
            phases.addView(text("${stg.title}${if (active) " - ACTIVE" else ""}", 13f, if (active && stg!=TradingStage.STAGE_3) green else if (active) red else muted, true))
            phases.addView(text(desc, 11f, muted))
        }
        val info = addCard(root, "SYSTEM INFORMATION")
        addLabel(info, "Application", "BAB - Binance Android Bot")
        addLabel(info, "Version", BuildConfig.VERSION_NAME)
        addLabel(info, "Stage 1/2 Orders", "SIMULATED ONLY (no real Binance orders)", green)
        addLabel(info, "Stage 3 Live", "DISABLED BY DEFAULT", red)
        addLabel(info, "Market Data", "Binance public USDT-M endpoints (read-only)", muted)

        val s = stage()
        if (s != TradingStage.STAGE_3) {
            addButton(addCard(root, "WALLET (${s.shortLabel})"), "RESET ${s.shortLabel} VIRTUAL WALLET TO 100 USDT", 0xFF3A2E16.toInt()).setOnClickListener {
                AlertDialog.Builder(this).setTitle("Reset ${s.shortLabel} wallet?")
                    .setMessage("Resets ${s.shortLabel} simulated balance to 100.0000 USDT.")
                    .setPositiveButton("RESET") { _, _ -> lifecycleScope.launch { engine().resetDemo(); toast("${s.shortLabel} wallet reset"); showPage("More") } }
                    .setNegativeButton("CANCEL", null).show()
            }
        } else {
            addCard(root, "STAGE 3 LIVE TRADING").addView(text("Live Trading is disabled by default. Real Binance order submission is not enabled in this build. No credentials are stored in code.", 12f, red))
        }
    }

    // ---------------- helpers ----------------
    private fun createDarkSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.setPadding(dp(8), dp(10), dp(8), dp(10)); return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.setBackgroundColor(inputBg)
                v.setPadding(dp(12), dp(12), dp(12), dp(12)); return v
            }
        }

    private fun addButton(parent: LinearLayout, label: String, color: Int): Button {
        val b = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            setTextColor(if (color == yellow) 0xFF000000.toInt() else white)
            background = rounded(color, 12)
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }); return b
    }

    private fun addActionButton(parent: LinearLayout, label: String, color: Int = inputBg, action: () -> Unit) {
        addButton(parent, label, color).setOnClickListener { action() }
    }

    private fun addActionButton(parent: LinearLayout, label: String, action: () -> Unit) = addActionButton(parent, label, inputBg, action)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { stopRefresh(); super.onDestroy() }
}