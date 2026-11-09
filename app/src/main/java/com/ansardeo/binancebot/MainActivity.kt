package com.ansardeo.binancebot

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import com.ansardeo.binancebot.paper.db.PaperDatabase
import com.ansardeo.binancebot.ui.CoinDetailActivity
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
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
    private lateinit var topStatus: TextView
    private lateinit var navItems: List<TextView>
    private var currentPage = "Home"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var refreshGen: Long = 0L
    private var lastOverview: MarketOverview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.contentFrame)
        topStatus = findViewById(R.id.txtTopStatus)
        navItems = listOf(
            findViewById(R.id.navDashboard),
            findViewById(R.id.navManual),
            findViewById(R.id.navPositions),
            findViewById(R.id.navSettings),
            findViewById(R.id.navMore)
        )
        navItems[0].setOnClickListener { showPage("Home") }
        navItems[1].setOnClickListener { showPage("Manual") }
        navItems[2].setOnClickListener { showPage("Positions") }
        navItems[3].setOnClickListener { showPage("Settings") }
        navItems[4].setOnClickListener { showPage("More") }
        lifecycleScope.launch {
            // Ensure wallets exist for all stages
            TradingStage.entries.forEach { runCatching { PaperEngineProvider.get(this@MainActivity, it).ensureWallet() } }
        }
        updateTopStatus()
        showPage("Home")
    }

    private fun updateTopStatus() {
        val s = stage()
        val label = when (s) {
            TradingStage.STAGE_1 -> "STAGE 1 • VIRTUAL"
            TradingStage.STAGE_2 -> "STAGE 2 • DEMO"
            TradingStage.STAGE_3 -> "STAGE 3 • LIVE"
        }
        topStatus.text = label
        topStatus.setTextColor(if (s == TradingStage.STAGE_3) red else green)
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
        val line = row()
        line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        val v = if (isPrice) priceTv(value, 13f, valueColor) else text(value, 13f, valueColor, true)
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
        val outer = column().apply { setPadding(dp(16), dp(14), dp(16), dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(outer) }
        content.removeAllViews(); content.addView(scroll); return outer
    }

    private fun openDetail(symbol: String) {
        startActivity(Intent(this, CoinDetailActivity::class.java).putExtra(CoinDetailActivity.EXTRA_SYMBOL, symbol))
    }

    private fun showPage(page: String) {
        currentPage = page
        if (page != "Home") stopRefresh()
        navItems.forEachIndexed { i, item ->
            val sel = (page == "Home" && i == 0) || (page == "Manual" && i == 1) || (page == "Positions" && i == 2) || (page == "Settings" && i == 3) || (page == "More" && i == 4)
            item.setTextColor(if (sel) yellow else muted)
            item.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        when (page) {
            "Home" -> dashboardPage()
            "Manual" -> manualPage()
            "Positions" -> positionsPage()
            "Settings" -> settingsPage()
            "More" -> morePage()
        }
    }

    private fun stopRefresh() { refreshGen++; refreshRunnable?.let { mainHandler.removeCallbacks(it) }; refreshRunnable = null }

    // ---------------- HOME ----------------
    private fun dashboardPage() {
        stopRefresh()
        val root = pageContainer()
        root.addView(text("Home", 23f, white, true))
        root.addView(text("BAB – Binance Android Bot • ${stage().title}", 12f, yellow, true))
        root.addView(text("Live USDⓈ-M Futures market (public, read-only). Simulated stages never submit real orders.", 11f, muted))
        addSpace(root, 8)

        val s = stage()
        val phaseCard = addCard(root, "TRADING STAGES", "Data is isolated per stage. Stage 1/2 never submit real Binance orders.")
        addLabel(phaseCard, "Stage 1", "Live Market Prices + Virtual Balance (100 USDT)${if (s==TradingStage.STAGE_1) " • ACTIVE" else ""}", if (s==TradingStage.STAGE_1) green else muted)
        addLabel(phaseCard, "Stage 2", "Demo Trading (simulated, no real funds)${if (s==TradingStage.STAGE_2) " • ACTIVE" else ""}", if (s==TradingStage.STAGE_2) green else muted)
        addLabel(phaseCard, "Stage 3", "Live Trading (disabled by default; requires explicit authorization)${if (s==TradingStage.STAGE_3) " • SELECTED" else ""}", if (s==TradingStage.STAGE_3) red else lockedColor)
        addSpace(phaseCard, 6)
        addActionButton(phaseCard, "SWITCH STAGE (tap to cycle)") {
            val next = when (stage()) { TradingStage.STAGE_1 -> TradingStage.STAGE_2; TradingStage.STAGE_2 -> TradingStage.STAGE_3; TradingStage.STAGE_3 -> TradingStage.STAGE_1 }
            StageStore.setCurrent(this, next); updateTopStatus(); toast("Switched to ${next.shortLabel}"); showPage("Home")
        }

        val balCard = addCard(root, "WALLET • ${stage().shortLabel}")
        val balText = priceTv("-- USDT", 20f, green); balCard.addView(balText)
        val balSub = text("Loading ${stage().shortLabel} wallet...", 11f, muted); addSpace(balCard, 4); balCard.addView(balSub)
        if (stage() == TradingStage.STAGE_3) balCard.addView(text("Stage 3 live balance integration is scaffolded and disabled by default. No real orders.", 11f, red))

        val marketCard = addCard(root, "LIVE CRYPTOCURRENCY PRICES (READ-ONLY)", "Tap any coin for details/favorite. Prices use dynamic decimals, right-aligned.")
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
        val secFav = section("MY FAVORITES (tap for details / favorite toggle)")

        fun renderWallet() {
            lifecycleScope.launch {
                try {
                    val eng = engine()
                    val w = eng.getWallet()
                    val active = paperDb.dao().getActiveCyclesWithEntries(stageId())
                    val prices = lastOverview?.symbols ?: emptyMap()
                    var unreal = 0.0
                    for (cwe in active) {
                        val px = prices[cwe.cycle.symbol]?.markPrice ?: prices[cwe.cycle.symbol]?.lastPrice ?: continue
                        val avg = cwe.cycle.averageEntryPrice ?: continue
                        unreal += if (cwe.cycle.direction == "LONG") (px - avg) * cwe.cycle.totalQuantity else (avg - px) * cwe.cycle.totalQuantity
                    }
                    val equity = w.availableBalance + w.reservedMargin + unreal
                    balText.text = PriceFormatter.formatUsdt(equity)
                    balSub.text = "Available ${PriceFormatter.formatUsdt(w.availableBalance)} | Reserved ${PriceFormatter.formatUsdt(w.reservedMargin)} | Unrealized ${PriceFormatter.formatPnl(unreal)} | Realized ${PriceFormatter.formatPnl(w.realizedPnl)}"
                } catch (_: Exception) {
                    balSub.text = "Unable to load virtual wallet."
                }
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
                    mStatus.text = "$label - Binance USD-M Futures (public, read-only)"
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
        addActionButton(addCard(root, "QUICK ACCESS"), "Open Manual (Favorites)") { showPage("Manual") }
    }

    private fun renderTickerList(target: LinearLayout, items: List<Ticker>) {
        target.removeAllViews()
        if (items.isEmpty()) { target.addView(text("No live data available.", 12f, muted)); return }
        items.take(10).forEach { t ->
            val line = row().apply {
                isClickable = true; isFocusable = true
                setPadding(dp(2), dp(6), dp(2), dp(6))
                setOnClickListener { openDetail(t.symbol) }
            }
            line.addView(text(t.symbol, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.1f))
            line.addView(priceTv(PriceFormatter.formatPrice(t.lastPrice ?: t.markPrice), 12f, white), LinearLayout.LayoutParams(0, -2, 1.2f))
            val pct = t.priceChangePercent ?: 0.0
            val pctColor = when { pct > 0 -> green; pct < 0 -> red; else -> muted }
            line.addView(text(PriceFormatter.formatPercent(t.priceChangePercent), 12f, pctColor, true).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 0.8f))
            target.addView(line)
            target.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderFavorites(target: LinearLayout, ov: MarketOverview?) {
        target.removeAllViews()
        val favs = FavoritesStore.load(this)
        if (favs.isEmpty()) {
            target.addView(text("No favorites yet. Open a coin from Home/Detail and tap Favorite to add.", 12f, muted)); return
        }
        for (sym in favs) {
            val t = ov?.symbols?.get(sym)
            val line = row().apply {
                isClickable = true; isFocusable = true
                setPadding(dp(2), dp(6), dp(2), dp(6))
                setOnClickListener { openDetail(sym) }
            }
            line.addView(text(sym, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.1f))
            line.addView(priceTv(PriceFormatter.formatPrice(t?.lastPrice ?: t?.markPrice), 12f, white), LinearLayout.LayoutParams(0, -2, 1.2f))
            val pct = t?.priceChangePercent ?: 0.0
            val pctColor = when { t == null -> muted; pct > 0 -> green; pct < 0 -> red; else -> muted }
            line.addView(text(PriceFormatter.formatPercent(t?.priceChangePercent), 12f, pctColor, true).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 0.8f))
            target.addView(line)
            target.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    // ---------------- MANUAL (Favorites-only, live price, Phase 1 paper submit) ----------------
    private fun manualPage() {
        stopRefresh()
        content.removeAllViews()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(card, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val stageLabel = text("${stage().title}", 13f, if (stage()==TradingStage.STAGE_3) red else green, true)
        stageBar.addView(stageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val stageBtn = Button(this).apply { text = "Change Stage"; textSize = 11f; isAllCaps = false; setTextColor(0xFF000000.toInt()); background = rounded(yellow, 10) }
        stageBtn.setOnClickListener {
            val opts = TradingStage.entries.map { it.title }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Select Trading Stage").setItems(opts) { _, i ->
                StageStore.setCurrent(this, TradingStage.entries[i]); updateTopStatus(); showPage("Manual")
            }.show()
        }
        stageBar.addView(stageBtn)
        outer.addView(stageBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
        val view = LayoutInflater.from(this).inflate(R.layout.activity_manual_entry, outer, false)
        outer.addView(view)
        content.addView(ScrollView(this).apply { addView(outer) })

        val symbolSpinner = view.findViewById<Spinner>(R.id.spEntrySymbol)
        val leverageSpinner = view.findViewById<Spinner>(R.id.spEntryLeverage)
        val directionGroup = view.findViewById<RadioGroup>(R.id.rgDirection)
        val marginInput = view.findViewById<EditText>(R.id.etEntryMargin)
        val summary = view.findViewById<TextView>(R.id.txtEntrySummary)
        val livePriceTv = view.findViewById<TextView>(R.id.txtLivePrice)
        val liveStatus = view.findViewById<TextView>(R.id.txtLivePriceStatus)
        val submit = view.findViewById<Button>(R.id.btnSubmitEntry)
        val result = view.findViewById<TextView>(R.id.txtManualResult)
        val leverages = (1..125).map { "${it}x" }
        val favs = FavoritesStore.load(this)
        var currentLivePrice: Double? = null

        fun selectedSym(): String? = symbolSpinner.selectedItem?.toString()

        if (favs.isEmpty()) {
            symbolSpinner.adapter = createDarkSpinnerAdapter(listOf("No favorites - add from Home/Detail"))
            submit.isEnabled = false
            summary.text = "No favorite coins available. Add coins to Favorites from Home or the Coin Detail screen, then return here."
            livePriceTv.text = "--"
            liveStatus.text = "Favorites-only filter active."
            return
        }

        symbolSpinner.adapter = createDarkSpinnerAdapter(favs)
        leverageSpinner.adapter = createDarkSpinnerAdapter(leverages)
        leverageSpinner.setSelection(5) // 6x default index
        symbolSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        leverageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))

        fun updateSummary() {
            val sym = selectedSym() ?: "--"
            val dir = if (directionGroup.checkedRadioButtonId == R.id.rbShort) "SHORT" else "LONG"
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = (leverageSpinner.selectedItemPosition + 1).coerceIn(1, 125)
            val px = currentLivePrice
            val qty = if (px != null && px > 0 && margin > 0) (margin * lev) / px else null
            val notional = if (qty != null && px != null) qty * px else null
            val s = if (sym != "--") LocalCoinSettingsStore.load(this, sym) else null
            val tp = if (px != null && s != null) if (dir == "LONG") px * (1 + s.tpPercent/100.0) else px * (1 - s.tpPercent/100.0) else null
            val sl = if (px != null && s != null) if (dir == "LONG") px * (1 - s.slPercent/100.0) else px * (1 + s.slPercent/100.0) else null
            val estFee = if (notional != null) notional * 0.0004 else null
            summary.text = buildString {
                append("Stage: ${stage().title}\n")
                append("Pair: $sym (favorites)\nDirection: $dir\n")
                append("Current Market Price: ${PriceFormatter.formatPrice(px)} USDT\n")
                append("Margin: ${String.format(Locale.US, "%.4f", margin)} USDT | Leverage: ${lev}x\n")
                if (qty != null) append("Estimated Qty: ${PriceFormatter.formatQty(qty)}\n")
                if (notional != null) append("Estimated Notional: ${PriceFormatter.formatUsdt(notional)}\n")
                append("Applicable TP: ${PriceFormatter.formatPrice(tp)} | SL: ${PriceFormatter.formatPrice(sl)}\n")
                if (estFee != null) append("Estimated Entry Fee: ${PriceFormatter.formatUsdt(estFee)} (sim assumption)\n")
                append("\nPreview only. First entry is manual and requires explicit confirmation. Stage 1/2 = simulated.")
            }
        }

        val myGen = ++refreshGen
        fun startPriceRefresh() {
            fun tick() {
                if (myGen != refreshGen || currentPage != "Manual") return
                val sym = selectedSym() ?: return
                lifecycleScope.launch {
                    val px = marketRepo.getMarkPrice(sym)
                    if (myGen != refreshGen || currentPage != "Manual" || selectedSym() != sym) return@launch
                    currentLivePrice = px
                    livePriceTv.text = PriceFormatter.formatPrice(px)
                    liveStatus.text = if (px != null) "Live public mark/price (auto-refresh)" else "Price unavailable - retrying"
                    liveStatus.setTextColor(if (px != null) muted else red)
                    updateSummary()
                }
                val r = Runnable { tick() }; refreshRunnable = r; mainHandler.postDelayed(r, 8000)
            }
            tick()
        }

        val selListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { startPriceRefresh(); updateSummary() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        symbolSpinner.onItemSelectedListener = selListener
        leverageSpinner.onItemSelectedListener = selListener
        directionGroup.setOnCheckedChangeListener { _, _ -> updateSummary() }
        marginInput.addTextChangedListener(SimpleWatcher { updateSummary() })

        submit.setOnClickListener {
            val sym = selectedSym()
            if (sym.isNullOrBlank()) { toast("Select a favorite coin first"); return@setOnClickListener }
            val px = currentLivePrice
            if (px == null || px <= 0) { toast("Live price unavailable. Wait for price refresh."); return@setOnClickListener }
            val dir = if (directionGroup.checkedRadioButtonId == R.id.rbShort) "SHORT" else "LONG"
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = (leverageSpinner.selectedItemPosition + 1).coerceIn(1, 125)
            val s = stage()
            val warn = if (s == TradingStage.STAGE_3)
                "STAGE 3 LIVE TRADING: Real Binance order submission is disabled in this build. No real order will be placed."
            else "${s.shortLabel} is simulated. No real Binance order will be submitted."
            AlertDialog.Builder(this)
                .setTitle("Confirm Entry")
                .setMessage("Stage: ${s.title}\n$warn\n\n$sym $dir @ ${PriceFormatter.formatPrice(px)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${lev}x\n\nExecute this entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    if (s == TradingStage.STAGE_3) {
                        result.setTextColor(red)
                        result.text = "[X] Stage 3 Live Trading is disabled by default and real order submission is not enabled in this build. No real Binance order was submitted."
                        return@setPositiveButton
                    }
                    submit.isEnabled = false
                    result.setTextColor(muted); result.text = "Submitting simulated entry..."
                    lifecycleScope.launch {
                        val res = engine().openInitial(this@MainActivity, sym, dir, margin, lev, px)
                        submit.isEnabled = true
                        res.onSuccess { c ->
                            result.setTextColor(green)
                            result.text = "[OK] ${stage().shortLabel} simulated cycle #${c.id} opened: $sym $dir @ ${PriceFormatter.formatPrice(px)}."
                            toast("Simulated entry submitted (${stage().shortLabel})")
                            try { engine().processSymbolPrice(this@MainActivity, sym, px) } catch (_: Exception) {}
                        }.onFailure { e ->
                            result.setTextColor(red)
                            result.text = "[X] ${e.message ?: "Unable to submit entry"}"
                        }
                    }
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
        startPriceRefresh(); updateSummary()
    }

    private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    // ---------------- POSITIONS (stage-isolated: Positions / Open Orders / Pipeline) ----------------
    private fun positionsPage() {
        stopRefresh()
        updateTopStatus()
        val root = pageContainer()
        root.addView(text("Positions", 23f, white, true))
        root.addView(text("${stage().title} • Separate Positions, Open Orders, and Pipeline. Stage 1/2 simulated.", 12f, muted))
        addSpace(root, 8)

        val tabBar = row().apply { background = rounded(card, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar)
        addSpace(root, 10)
        val body = column()
        root.addView(body)

        var selectedTab = 0
        lateinit var tabViews: List<TextView>
        fun styleTabs(posCount: Int, openCount: Int, pipeCount: Int) {
            val labels = listOf("Positions ($posCount)", "Open Orders ($openCount)", "Pipeline ($pipeCount)")
            tabViews.forEachIndexed { i, tv ->
                tv.text = labels[i]
                val sel = i == selectedTab
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 10) else null
            }
        }

        fun render() {
            lifecycleScope.launch {
                try {
                    val dao = paperDb.dao()
                    val sid = stageId()
                    val prices = (lastOverview ?: marketRepo.getOverview().also { lastOverview = it }).symbols
                    val active = dao.getActiveCyclesWithEntries(sid)
                    val openOrders = dao.getActiveOrders(sid).filter { it.status == "ACTIVE" }
                    val pipeline = dao.getPipelinePlans(sid).filter { it.status !in listOf("ACTIVATED", "EXECUTED", "CANCELLED") }
                    val closed = dao.getRecentCycles(sid, 80).filter { it.status == "CLOSED" }
                    styleTabs(active.size, openOrders.size, pipeline.size)
                    body.removeAllViews()
                    when (selectedTab) {
                        0 -> renderPositionsTab(body, active, prices, closed)
                        1 -> renderOpenOrdersTab(body, openOrders)
                        2 -> renderPipelineTab(body, pipeline)
                    }
                } catch (e: Exception) {
                    body.removeAllViews()
                    body.addView(text("Unable to load positions: ${e.message ?: "unknown error"}", 12f, red))
                }
            }
        }

        tabViews = (0..2).map { i ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                textSize = 12f
                setOnClickListener { if (selectedTab != i) { selectedTab = i; render() } }
            }.also { tv -> tabBar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        styleTabs(0, 0, 0)
        render()
    }

    private fun renderPositionsTab(
        body: LinearLayout,
        active: List<com.ansardeo.binancebot.paper.db.PaperCycleWithEntries>,
        prices: Map<String, com.ansardeo.binancebot.data.Ticker>,
        closed: List<PaperCycleEntity>
    ) {
        val box = addCard(body, "POSITIONS", "Executed demo positions that are still open")
        if (active.isEmpty()) {
            addSpace(box, 6); box.addView(text("No active demo positions.", 13f, muted))
        }
        for (cwe in active) {
            val c = cwe.cycle
            val px = prices[c.symbol]?.markPrice ?: prices[c.symbol]?.lastPrice
            val avg = c.averageEntryPrice ?: 0.0
            val unreal = if (px != null && avg > 0) (if (c.direction == "LONG") (px - avg) * c.totalQuantity else (avg - px) * c.totalQuantity) else null
            val margin = cwe.entries.filter { it.status == "FILLED" || it.remainingQuantity > 0.0 }.sumOf { it.margin }
                .takeIf { it > 0.0 } ?: (c.initialMargin + if (c.averagingDone) c.averagingMargin else 0.0)
            val roi = if (unreal != null && margin > 0.0) (unreal / margin) * 100.0 else null
            addSpace(box, 10)
            val header = row().apply { isClickable = true; setOnClickListener { openDetail(c.symbol) } }
            header.addView(text("${c.symbol}  ${c.direction}", 15f, if (c.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Cycle #${c.id}", 11f, muted, true))
            box.addView(header)
            addLabel(box, "Entry Price", PriceFormatter.formatPrice(cwe.entries.firstOrNull { it.entryType == "INITIAL" }?.price ?: c.averageEntryPrice), white, true)
            addLabel(box, "Avg Entry Price", PriceFormatter.formatPrice(c.averageEntryPrice), white, true)
            addLabel(box, "Current Market Price", PriceFormatter.formatPrice(px), white, true)
            addLabel(box, "Position Qty / Size", PriceFormatter.formatQty(c.totalQuantity))
            addLabel(box, "Margin", PriceFormatter.formatUsdt(margin))
            addLabel(box, "Leverage", "${c.leverage}x")
            addLabel(box, "Unrealized PNL", PriceFormatter.formatPnl(unreal), when { (unreal ?: 0.0) > 0 -> green; (unreal ?: 0.0) < 0 -> red; else -> muted })
            addLabel(box, "ROI", if (roi == null) "--" else PriceFormatter.formatPercent(roi), when { (roi ?: 0.0) > 0 -> green; (roi ?: 0.0) < 0 -> red; else -> muted })
            addLabel(box, "TP Price", PriceFormatter.formatPrice(c.takeProfitPrice), green, true)
            addLabel(box, "SL Price", PriceFormatter.formatPrice(c.stopLossPrice), red, true)
            addButton(box, "DETAILS", inputBg).setOnClickListener { showCycleDetails(c) }
        }

        addSpace(body, 6)
        val histBox = addCard(body, "HISTORY (CLOSED)", "Closed demo trades — preserved in Positions view")
        if (closed.isEmpty()) {
            addSpace(histBox, 6); histBox.addView(text("No closed demo cycles yet.", 13f, muted))
        } else {
            for (c in closed.take(40)) {
                addSpace(histBox, 8)
                histBox.addView(text("${c.symbol} ${c.direction} — ${c.closeReason ?: "CLOSED"} (Cycle #${c.id})", 13f, white, true))
                addLabel(histBox, "Close Price", PriceFormatter.formatPrice(c.closePrice), white, true)
                addLabel(histBox, "Realized PNL", PriceFormatter.formatPnl(c.realizedPnl), if ((c.realizedPnl ?: 0.0) >= 0) green else red)
            }
        }
    }

    private fun renderOpenOrdersTab(body: LinearLayout, orders: List<com.ansardeo.binancebot.paper.db.PaperOrderEntity>) {
        val box = addCard(body, "OPEN ORDERS", "Active simulated orders (actually created in demo engine)")
        if (orders.isEmpty()) {
            addSpace(box, 6); box.addView(text("No active open orders.", 13f, muted)); return
        }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (o in orders.sortedByDescending { it.createdAt }) {
            addSpace(box, 10)
            val header = row()
            header.addView(text("${o.symbol}  ${o.direction}", 14f, if (o.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(o.orderType.replace('_', ' '), 11f, yellow, true))
            box.addView(header)
            addLabel(box, "Order Type", o.orderType)
            addLabel(box, "Trigger Price", PriceFormatter.formatPrice(o.triggerPrice), white, true)
            addLabel(box, "Limit Price", if (o.limitPrice != null) PriceFormatter.formatPrice(o.limitPrice) else "—", muted, o.limitPrice != null)
            addLabel(box, "Quantity", PriceFormatter.formatQty(o.quantity))
            addLabel(box, "Related Position / Cycle", "#${o.cycleId}")
            addLabel(box, "Status", o.status, green)
            addLabel(box, "Created", tf.format(java.util.Date(o.createdAt)))
        }
    }

    private fun renderPipelineTab(body: LinearLayout, plans: List<com.ansardeo.binancebot.paper.db.PaperPlanEntity>) {
        val box = addCard(body, "PIPELINE", "Planned demo conditions (not active exchange orders)")
        if (plans.isEmpty()) {
            addSpace(box, 6); box.addView(text("No pipeline conditions.", 13f, muted)); return
        }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (p in plans.sortedByDescending { it.updatedAt }) {
            addSpace(box, 10)
            val title = when (p.planType) {
                "AVERAGING" -> "Averaging Order (planned)"
                "OPPOSITE_SIDE" -> "Opposite Side Order (planned)"
                "REENTRY" -> "Re-entry Order (planned)"
                else -> p.planType
            }
            val actionDir = if (p.planType == "OPPOSITE_SIDE") (if (p.direction == "LONG") "SHORT" else "LONG") else p.direction
            val header = row()
            header.addView(text("${p.symbol}  $actionDir", 14f, if (actionDir == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(p.status, 11f, statusColor(p.status), true))
            box.addView(header)
            box.addView(text(title, 12f, yellow, true))
            addSpace(box, 4)
            when (p.planType) {
                "AVERAGING" -> {
                    addLabel(box, "Trigger % (adverse)", if (p.triggerPercent != null) "${String.format(Locale.US, "%.2f", p.triggerPercent)}%" else "--")
                    addLabel(box, "Warning threshold", if (p.warningPercent != null) "${String.format(Locale.US, "%.2f", p.warningPercent)}%" else "--")
                    addLabel(box, "Trigger condition", PriceFormatter.formatPrice(p.triggerPrice), white, true)
                    addLabel(box, "Rule", "Averaging allowed only once per cycle")
                }
                "OPPOSITE_SIDE" -> {
                    addLabel(box, "Trigger %", if (p.triggerPercent != null) "${String.format(Locale.US, "%.2f", p.triggerPercent)}%" else "--")
                    addLabel(box, "Warning threshold", if (p.warningPercent != null) "${String.format(Locale.US, "%.2f", p.warningPercent)}%" else "--")
                    addLabel(box, "Trigger price/condition", PriceFormatter.formatPrice(p.triggerPrice), white, true)
                    addLabel(box, "Margin (sim)", if (p.margin != null) PriceFormatter.formatUsdt(p.margin) else "--")
                }
                "REENTRY" -> {
                    addLabel(box, "Reference close", PriceFormatter.formatPrice(p.referencePrice), white, true)
                    addLabel(box, "Trigger price/condition", PriceFormatter.formatPrice(p.triggerPrice), white, true)
                    if (p.triggerPercent != null) addLabel(box, "Pullback %", "${String.format(Locale.US, "%.2f", p.triggerPercent)}%")
                    addLabel(box, "Note", "Created only after successful TP; activates to Open Order when met")
                }
            }
            addLabel(box, "Related Cycle", if (p.cycleId != null) "#${p.cycleId}" else if (p.sourceCycleId != null) "source #${p.sourceCycleId}" else "--")
            addLabel(box, "Updated", tf.format(java.util.Date(p.updatedAt)))
        }
    }

    private fun statusColor(status: String): Int = when (status.uppercase(Locale.US)) {
        "WARNING", "TRIGGER_REACHED", "READY" -> yellow
        "ACTIVATED", "EXECUTED" -> green
        "CANCELLED", "REJECTED" -> red
        else -> muted // WAITING etc.
    }

    private fun showCycleDetails(c: PaperCycleEntity) {
        lifecycleScope.launch {
            val entries = paperDb.dao().getEntries(c.id)
            val msg = buildString {
                append("Cycle #${c.id} - ${c.symbol} ${c.direction}\nStatus: ${c.status}\n")
                append("Avg Entry: ${PriceFormatter.formatPrice(c.averageEntryPrice)}\n")
                append("TP Target: ${PriceFormatter.formatPrice(c.takeProfitPrice)}\n")
                append("SL Target: ${PriceFormatter.formatPrice(c.stopLossPrice)}\n")
                append("Qty: ${PriceFormatter.formatQty(c.totalQuantity)} | Lev: ${c.leverage}x\n")
                append("Averaging: ${if (c.averagingDone) "USED" else "ARMED (${c.averagingPercent}%, warn ${c.warningPercent}%)"} | Opposite: ${if (c.oppositeDone) "USED" else "${c.oppositePercent}%"}\n")
                append("Re-entry (after TP only): ${c.reentryPercent}% ${if (c.reentryEnabled) "enabled" else "disabled"}\n")
                if (c.closeReason != null) append("Close: ${c.closeReason} @ ${PriceFormatter.formatPrice(c.closePrice)}\n")
                if (c.realizedPnl != null) append("Realized PnL: ${PriceFormatter.formatPnl(c.realizedPnl)}\n")
                append("\nENTRIES (simulated):\n")
                entries.forEach { e -> append("- ${e.entryType} ${e.status} @ ${PriceFormatter.formatPrice(e.price)} qty ${PriceFormatter.formatQty(e.quantity)}\n") }
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Paper Cycle Details").setMessage(msg).setPositiveButton("CLOSE", null).show()
        }
    }

    // ---------------- SETTINGS ----------------
    private fun settingsPage() {
        val root = pageContainer()
        root.addView(text("Settings", 23f, white, true))
        root.addView(text("Per-coin settings (saved locally). Applied to simulated stages; Stage 3 integration is scaffolded/disabled by default.", 12f, muted))
        val st = addCard(root, "ACTIVE STAGE", "Current: ${stage().title}")
        addActionButton(st, "CHANGE STAGE") {
            val opts = TradingStage.entries.map { it.title }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Select Trading Stage").setItems(opts) { _, i ->
                StageStore.setCurrent(this, TradingStage.entries[i]); updateTopStatus(); showPage("Settings")
            }.show()
        }
        val symbols = arrayOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT")
        var selected = symbols[0]
        val selector = addCard(root, "SELECT TRADING PAIR")
        val spinner = Spinner(this).apply { adapter = createDarkSpinnerAdapter(symbols.toList()); setPopupBackgroundDrawable(rounded(inputBg, 10)) }
        selector.addView(spinner, LinearLayout.LayoutParams(-1, dp(50)))
        val box = column(); root.addView(box)
        fun render(sym: String) {
            box.removeAllViews()
            val s = LocalCoinSettingsStore.load(this, sym)
            val sCard = addCard(box, "$sym CONFIGURATION", "Local only • per-coin isolated • used by BAB simulation engine")
            fun et(label: String, value: Double): EditText {
                val e = EditText(this).apply {
                    setText(String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.'))
                    setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg)
                    setPadding(dp(10), dp(8), dp(10), dp(8)); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                sCard.addView(text(label, 12f, muted, true)); sCard.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }); return e
            }
            fun sw(label: String, checked: Boolean) = Switch(this).apply { text = label; isChecked = checked; setTextColor(white) }.also { sCard.addView(it); addSpace(sCard, 4) }
            val swL = sw("LONG enabled", s.enabledLong); val swS = sw("SHORT enabled", s.enabledShort)
            val swAvg = sw("Averaging enabled (one-time at trigger %)", s.averagingEnabled)
            val swRe = sw("Re-entry enabled", s.reentryEnabled)
            val m = et("Initial Margin (USDT)", s.marginAmount)
            val lev = et("Leverage", s.leverage.toDouble())
            val tp = et("Take Profit %", s.tpPercent)
            val sl = et("Stop Loss %", s.slPercent)
            val avgP = et("Averaging Trigger % (default 9)", s.averagingPercent)
            val avgM = et("Averaging Margin (USDT)", s.averagingMargin)
            val reP = et("Re-entry Pullback %", s.reentryPercent)
            val msg = text("", 12f, muted); sCard.addView(msg)
            addButton(sCard, "SAVE LOCAL SETTINGS", yellow).setOnClickListener {
                try {
                    if (!swL.isChecked && !swS.isChecked) { msg.setTextColor(red); msg.text = "Enable at least one direction."; return@setOnClickListener }
                    val j = org.json.JSONObject().apply {
                        put("enabledLong", swL.isChecked); put("enabledShort", swS.isChecked)
                        put("marginAmount", m.text.toString().toDoubleOrNull() ?: s.marginAmount)
                        put("leverage", (lev.text.toString().toDoubleOrNull() ?: s.leverage.toDouble()).toInt().coerceIn(1, 125))
                        put("tpPercent", tp.text.toString().toDoubleOrNull() ?: s.tpPercent)
                        put("slPercent", sl.text.toString().toDoubleOrNull() ?: s.slPercent)
                        put("warningPercent", s.warningPercent)
                        put("oppositeEnabled", s.oppositeEnabled); put("oppositePercent", s.oppositePercent); put("oppositeMargin", s.oppositeMargin)
                        put("averagingEnabled", swAvg.isChecked)
                        put("averagingPercent", avgP.text.toString().toDoubleOrNull() ?: s.averagingPercent)
                        put("averagingMargin", avgM.text.toString().toDoubleOrNull() ?: s.averagingMargin)
                        put("reentryEnabled", swRe.isChecked)
                        put("reentryPercent", reP.text.toString().toDoubleOrNull() ?: s.reentryPercent)
                        put("savedAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date()))
                    }
                    getSharedPreferences("app_settings_local", Context.MODE_PRIVATE).edit().putString("settings_$sym", j.toString()).apply()
                    msg.setTextColor(green); msg.text = "Saved locally for $sym."; toast("Settings saved for $sym")
                } catch (e: Exception) { msg.setTextColor(red); msg.text = e.message ?: "Save failed" }
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selected = symbols[pos]; render(selected) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        render(selected)
    }

    // ---------------- MORE ----------------
    private fun morePage() {
        val root = pageContainer()
        root.addView(text("More", 23f, white, true))
        root.addView(text("BAB system status, wallet, history/events summary, and safety information", 12f, muted))
        val phases = addCard(root, "TRADING STAGES (INDEPENDENT)", "Stage data is isolated. Stage 1/2 never submit real Binance orders.")
        TradingStage.entries.forEach { stg ->
            val active = stage() == stg
            val desc = when (stg) {
                TradingStage.STAGE_1 -> "Live USDⓈ-M prices + virtual 100 USDT. Simulated execution only."
                TradingStage.STAGE_2 -> "Demo trading with simulated wallet/orders/positions/history. No real funds."
                TradingStage.STAGE_3 -> "Live Binance USDⓈ-M (scaffold). Disabled by default. Explicit authorization + secure API required. This build does not submit real orders."
            }
            addSpace(phases, 6)
            phases.addView(text("${stg.title}${if (active) " • ACTIVE" else ""}", 13f, if (active && stg!=TradingStage.STAGE_3) green else if (active) red else muted, true))
            phases.addView(text(desc, 11f, muted))
        }

        val info = addCard(root, "SYSTEM INFORMATION")
        addLabel(info, "Application", "BAB – Binance Android Bot")
        addLabel(info, "Version", BuildConfig.VERSION_NAME)
        addLabel(info, "Stage 1/2 Orders", "SIMULATED ONLY (no real Binance orders)", green)
        addLabel(info, "Stage 3 Live", "DISABLED BY DEFAULT", red)
        addLabel(info, "Market Data", "Binance public USDⓈ-M endpoints (read-only)", muted)

        val s = stage()
        if (s != TradingStage.STAGE_3) {
            addButton(addCard(root, "WALLET (${s.shortLabel})"), "RESET ${s.shortLabel} VIRTUAL WALLET TO 100 USDT", 0xFF3A2E16.toInt()).setOnClickListener {
                AlertDialog.Builder(this).setTitle("Reset ${s.shortLabel} wallet?")
                    .setMessage("Resets ${s.shortLabel} simulated balance to 100.0000 USDT. History remains in local storage.")
                    .setPositiveButton("RESET") { _, _ -> lifecycleScope.launch { engine().resetWallet(); toast("${s.shortLabel} wallet reset"); showPage("More") } }
                    .setNegativeButton("CANCEL", null).show()
            }
        } else {
            addCard(root, "STAGE 3 LIVE TRADING").addView(text("Live Trading is disabled by default. Real Binance order submission is not enabled in this build. No credentials are stored in code.", 12f, red))
        }
    }

    // (legacy helper removed; BAB uses TradingStage)

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
