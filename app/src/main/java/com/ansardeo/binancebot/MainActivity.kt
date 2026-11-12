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
import com.ansardeo.binancebot.paper.db.PaperEntryEntity
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

        val marketCard = addCard(root, "LIVE CRYPTOCURRENCY PRICES (READ-ONLY)")

        val tabBar = row().apply { background = rounded(inputBg, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        marketCard.addView(tabBar); addSpace(marketCard, 8)
        val listHost = column(); marketCard.addView(listHost)
        val marketTabs = listOf("Favorites", "Volume", "Gainers", "Losers")
        var selectedMarketTab = 1 // Volume is the default selected sub-tab on every Home open
        val tabViews = mutableListOf<TextView>()
        fun styleMarketTabs() {
            tabViews.forEachIndexed { i, tv ->
                val sel = i == selectedMarketTab
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(card, 8) else null
            }
        }
        fun volumeText(t: Ticker): String {
            val q = t.quoteVolume
            if (q == null || q <= 0.0) return "--"
            return when {
                q >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", q / 1_000_000_000)
                q >= 1_000_000 -> String.format(Locale.US, "%.2fM", q / 1_000_000)
                q >= 1_000 -> String.format(Locale.US, "%.2fK", q / 1_000)
                else -> String.format(Locale.US, "%.2f", q)
            }
        }
        fun renderMarketList(ov: MarketOverview?) {
            listHost.removeAllViews()
            if (ov == null || !ov.dataAvailable) {
                val msg = when {
                    ov == null -> "Loading..."
                    else -> "Unable to load live prices. Retrying..."
                }
                listHost.addView(text(msg, 12f, if (ov == null) muted else red)); return
            }
            when (selectedMarketTab) {
                0 -> {
                    val favs = FavoritesStore.load(this)
                    if (favs.isEmpty()) { listHost.addView(text("No favorites yet. Open a coin and tap Favorite to add it.", 12f, muted)); return }
                    val items = favs.mapNotNull { ov.symbols[it] }.ifEmpty { favs.map { Ticker(it, null, null, null, null, null) } }
                    renderMarketRows(listHost, items, volumeTextProvider = { t -> volumeText(t) })
                }
                1 -> renderMarketRows(listHost, ov.volumeLeaders.take(10), volumeTextProvider = { t -> volumeText(t) })
                2 -> renderMarketRows(listHost, ov.gainers.take(10), volumeTextProvider = { t -> volumeText(t) })
                3 -> renderMarketRows(listHost, ov.losers.take(10), volumeTextProvider = { t -> volumeText(t) })
            }
        }
        marketTabs.forEachIndexed { i, name ->
            val tv = text(name, 12f, muted, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { if (selectedMarketTab != i) { selectedMarketTab = i; styleMarketTabs(); renderMarketList(lastOverview) } }
            }
            tabViews.add(tv)
            tabBar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        }
        styleMarketTabs()

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
                    listHost.removeAllViews()
                    listHost.addView(text("Unable to load live prices. Retrying...", 12f, red))
                } else {
                    renderMarketList(ov)
                    renderWallet()
                }
            }
            val r = Runnable { refreshMarket() }; refreshRunnable = r; mainHandler.postDelayed(r, 15000)
        }
        renderMarketList(null); renderWallet(); refreshMarket()
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

    private fun formatCompactVolume(v: Double?): String {
        val q = v ?: return "--"
        if (q <= 0.0) return "--"
        return when {
            q >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", q / 1_000_000_000)
            q >= 1_000_000 -> String.format(Locale.US, "%.2fM", q / 1_000_000)
            q >= 1_000 -> String.format(Locale.US, "%.2fK", q / 1_000)
            else -> String.format(Locale.US, "%.2f", q)
        }
    }

    private fun renderMarketRows(target: LinearLayout, items: List<Ticker>, volumeTextProvider: (Ticker) -> String = { formatCompactVolume(it.quoteVolume) }) {
        target.removeAllViews()
        // Header row: Symbol, Price, 24h Vol, 24h % (last/far right)
        val header = row().apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        header.addView(text("Symbol", 11f, muted, true), LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(8) })
        header.addView(text("Price", 11f, muted, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, LinearLayout.LayoutParams(0, -2, 1.0f).apply { marginEnd = dp(8) })
        header.addView(text("24h Vol", 11f, muted, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginEnd = dp(8) })
        header.addView(text("24h %", 11f, muted, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, LinearLayout.LayoutParams(dp(78), -2))
        target.addView(header)
        target.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))

        if (items.isEmpty()) { target.addView(text("No data available.", 12f, muted).apply { setPadding(dp(10), dp(12), dp(10), dp(12)) }); return }
        items.take(10).forEach { t ->
            val line = row().apply {
                isClickable = true; isFocusable = true
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { openDetail(t.symbol) }
            }
            line.addView(text(t.symbol, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1.15f).apply { marginEnd = dp(8) })
            line.addView(priceTv(PriceFormatter.formatPrice(t.lastPrice ?: t.markPrice), 12f, white).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 1.0f).apply { marginEnd = dp(8) })
            line.addView(priceTv(volumeTextProvider(t), 11.5f, muted).apply {
                gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginEnd = dp(8) })
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

    // Backward-compatible wrappers if referenced elsewhere
    private fun renderTickerList(target: LinearLayout, items: List<Ticker>) = renderMarketRows(target, items)
    private fun renderFavorites(target: LinearLayout, ov: MarketOverview?) {
        val favs = FavoritesStore.load(this)
        val items = if (ov == null) favs.map { Ticker(it, null, null, null, null, null, null, null) } else favs.mapNotNull { ov.symbols[it] }.ifEmpty { favs.map { Ticker(it, null, null, null, null, null, null, null) } }
        if (favs.isEmpty()) { target.removeAllViews(); target.addView(text("No favorites yet. Open a coin and tap Favorite.", 12f, muted)); return }
        renderMarketRows(target, items)
    }

    // ---------------- TRADE (Manual tab renamed) ----------------
    private fun manualPage() /* Trade tab */ {
        stopRefresh()
        content.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.activity_manual_entry, content, false)
        content.addView(view)

        view.findViewById<View?>(R.id.boxStats)?.visibility = View.GONE

        val stageSpinner = view.findViewById<Spinner>(R.id.spStageSelect)
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

        // Compact stage selector (preserves stage management)
        stageSpinner.adapter = createDarkSpinnerAdapter(TradingStage.entries.map { it.shortLabel })
        stageSpinner.setSelection(TradingStage.entries.indexOf(stage()).coerceAtLeast(0))
        stageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        stageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val sel = TradingStage.entries.getOrNull(pos) ?: return
                if (sel != stage()) { StageStore.setCurrent(this@MainActivity, sel); updateTopStatus(); showPage("Trade") }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        leverageSpinner.adapter = createDarkSpinnerAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" })
        leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(6).coerceAtLeast(0))
        leverageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        symbolInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>()))

        fun previewRow(parent: LinearLayout, label: String, value: String, valueColor: Int = white, prominent: Boolean = false) {
            val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
            line.addView(text(label, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
            line.addView(text(value, if (prominent) 13.5f else 12.5f, valueColor, true).apply {
                gravity = Gravity.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                typeface = Typeface.MONOSPACE
            })
            parent.addView(line)
            addSpace(parent, 6)
        }

        fun previewGrid2(parent: LinearLayout, leftLabel: String, leftValue: String, leftColor: Int, rightLabel: String, rightValue: String, rightColor: Int) {
            val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
            val l = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) } }
            l.addView(text(leftLabel, 11f, muted))
            l.addView(text(leftValue, 12.5f, leftColor, true).apply { typeface = Typeface.MONOSPACE })
            val r = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            r.addView(text(rightLabel, 11f, muted).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
            r.addView(text(rightValue, 12.5f, rightColor, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END; typeface = Typeface.MONOSPACE })
            line.addView(l); line.addView(r)
            parent.addView(line)
            addSpace(parent, 8)
        }

        fun sectionTitle(parent: LinearLayout, title: String) {
            parent.addView(text(title, 12f, yellow, true))
            addSpace(parent, 6)
        }

        fun updateSummary() {
            val sym = selectedSym().ifBlank { "--" }
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = try { LeverageConfig.requireSupported(selectedLev()) } catch (_: Exception) { 6 }
            val profile = LeverageConfig.requireProfile(lev)
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            val qty = if (intended != null && intended > 0 && margin > 0) (margin * lev) / intended else null
            val notional = if (intended != null && intended > 0 && qty != null) intended * qty else null
            val tsLongAct = if (intended != null && intended > 0) intended * (1 + profile.trailingActivationPercent/100.0) else null
            val slLong = if (intended != null && intended > 0) intended * (1 - profile.slPercent/100.0) else null
            val tsShortAct = if (intended != null && intended > 0) intended * (1 - profile.trailingActivationPercent/100.0) else null
            val slShort = if (intended != null && intended > 0) intended * (1 + profile.slPercent/100.0) else null
            summaryBox.removeAllViews()
            sectionTitle(summaryBox, "A. Order Summary")
            previewRow(summaryBox, "Stage", stage().shortLabel, if (stage()==TradingStage.STAGE_3) red else green)
            previewRow(summaryBox, "Symbol", sym)
            previewRow(summaryBox, "Live Price", if (currentLivePrice != null) "${PriceFormatter.formatPrice(currentLivePrice)} USDT" else "--")
            previewRow(summaryBox, "Intended Entry Price", if (intended != null) "${PriceFormatter.formatPrice(intended)} USDT" else "--")
            previewRow(summaryBox, "Margin", "${PriceFormatter.formatUsdt(margin)} USDT")
            previewRow(summaryBox, "Leverage", "${lev}x")
            previewRow(summaryBox, "Estimated Quantity", if (qty != null) PriceFormatter.formatQty(qty) else "--")
            previewRow(summaryBox, "Estimated Notional", if (notional != null) "${PriceFormatter.formatUsdt(notional)} USDT" else "--")
            addSpace(summaryBox, 4)
            sectionTitle(summaryBox, "B. Trailing Stop")
            previewRow(summaryBox, "Activation Percentage", "${PriceFormatter.formatPercent(profile.trailingActivationPercent)}")
            previewRow(summaryBox, "Callback Rate", "${PriceFormatter.formatPercent(profile.callbackRatePercent)}")
            previewGrid2(summaryBox,
                "LONG Activation Price", if (tsLongAct != null) PriceFormatter.formatPrice(tsLongAct) else "--", green,
                "SHORT Activation Price", if (tsShortAct != null) PriceFormatter.formatPrice(tsShortAct) else "--", red)
            sectionTitle(summaryBox, "C. Risk Management")
            previewGrid2(summaryBox,
                "LONG Stop Loss Price", if (slLong != null) PriceFormatter.formatPrice(slLong) else "--", red,
                "SHORT Stop Loss Price", if (slShort != null) PriceFormatter.formatPrice(slShort) else "--", red)
        }

        fun loadSymbolLive(sym: String) {
            if (sym.isBlank() || !sym.endsWith("USDT")) return
            val myGen = ++refreshGen
            liveStatus.setTextColor(muted); liveStatus.text = "Loading..."
            fun tick() {
                if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return
                lifecycleScope.launch {
                    val px = marketRepo.getMarkPrice(sym)
                    if (myGen != refreshGen || currentPage != "Trade" || selectedSym() != sym) return@launch
                    currentLivePrice = px
                    livePriceTv.text = if (currentLivePrice != null) "${PriceFormatter.formatPrice(currentLivePrice)} USDT" else "--"
                    liveStatus.text = if (currentLivePrice != null) "Live" else "Unavailable"
                    liveStatus.setTextColor(if (currentLivePrice != null) green else red)
                    if (!userEditedEntryPrice && currentLivePrice != null && entryPriceInput.text.isNullOrBlank()) {
                        entryPriceInput.setText(PriceFormatter.formatPriceInput(currentLivePrice))
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
                    entryPriceInput.setText(PriceFormatter.formatPriceInput(prefillPrice))
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
    private fun fmtDateTime(ts: Long?): String = ts?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) } ?: "--"

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
        var orderSub = 0 // 0 Basic, 1 TP, 2 SL
        var historySub = 1 // 0 Fills, 1 Closes (Closes default when History is entered)
        lateinit var primaryViews: List<TextView>
        var subViews: List<TextView> = emptyList()

        fun stylePrimary(posCount: Int, openCount: Int, histCount: Int) {
            val labels = listOf("Position ($posCount)", "Open Order ($openCount)", "History ($histCount)")
            primaryViews.forEachIndexed { i, tv ->
                tv.text = labels[i]; val sel = i == primaryTab
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 10) else null
            }
        }
        fun styleSub(labels: List<String>) {
            subViews.forEachIndexed { i, tv ->
                val sel = when (primaryTab) {
                    0 -> i == positionSub
                    1 -> i == orderSub
                    else -> i == historySub
                }
                tv.text = labels.getOrElse(i) { "" }
                tv.setTextColor(if (sel) yellow else muted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) rounded(inputBg, 8) else null
            }
        }
        fun rebuildSubTabs() {
            subBar.removeAllViews()
            val count = if (primaryTab == 1) 3 else 2
            subViews = (0 until count).map { i ->
                TextView(this).apply {
                    gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f
                    setOnClickListener {
                        when (primaryTab) {
                            0 -> if (positionSub != i) { positionSub = i; render() }
                            1 -> if (orderSub != i) { orderSub = i; render() }
                            2 -> if (historySub != i) { historySub = i; render() }
                        }
                    }
                }.also { subBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
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
                    // Position -> Open: only genuinely executed and currently open positions (per-entry cards)
                    val openPositions = active.filter { cwe ->
                        cwe.cycle.status != "PENDING_ENTRY" &&
                            cwe.entries.any { e -> e.status == "FILLED" && e.remainingQuantity > 1e-12 }
                    }
                    val openPositionCount = openPositions.sumOf { cwe -> cwe.entries.count { e -> e.status == "FILLED" && e.remainingQuantity > 1e-12 } }
                    val allOrders = dao.getActiveOrders(sid).filter { it.status in setOf("ACTIVE", "CANCELLING") }
                    val cyclesAll = dao.getRecentCycles(sid, 300)
                    val cycleById = cyclesAll.associateBy { it.id }
                    val openEntryOrders = allOrders.filter { it.orderType == "ENTRY_LIMIT" }
                    val protectiveSlOrders = allOrders.filter { it.orderType == "ENTRY_SL" }
                    val reentries = dao.getPendingReentryPlans(sid).filter { it.status !in listOf("EXECUTED","CANCELLED","FAILED") }
                    val history = dao.getHistoryCloses(sid, 500)
                    // Executed entry fills (actual fills) for Fills subsection
                    val fillRecords = mutableListOf<Pair<PaperCycleEntity, PaperEntryEntity>>()
                    for (c in cyclesAll) {
                        val entries = dao.getEntries(c.id)
                        for (e in entries) {
                            val filled = e.filledAt != null || e.fillPrice != null || e.filledQuantity > 1e-12 || e.status == "FILLED" || e.status == "CLOSED"
                            if (filled) fillRecords.add(c to e)
                        }
                    }
                    fillRecords.sortByDescending { (c, e) -> e.filledAt ?: e.updatedAt ?: e.createdAt ?: c.updatedAt }
                    // Conditional opposite triggers (awaiting averaging execution) - kept in Basic open orders subsection
                    val conditionalOpposites = cyclesAll.filter { c ->
                        c.oppositeEnabled && !c.oppositeDone && c.status in listOf("PENDING_ENTRY","ACTIVE","CLOSING") &&
                            dao.getEntries(c.id).any { it.entryType == "AVERAGING" && it.status == "PENDING" } &&
                            dao.countOpenCycles(sid, c.symbol, if (c.direction == "LONG") "SHORT" else "LONG") == 0
                    }
                    val tpCount = openPositionCount // trailing-stop (TP) orders, one per open executed entry
                    val slCount = protectiveSlOrders.size
                    val basicCount = openEntryOrders.size + reentries.size + conditionalOpposites.size
                    val openOrderCount = basicCount + tpCount + slCount
                    val historyCount = (fillRecords.size - history.size).coerceAtLeast(0)
                    stylePrimary(openPositionCount, openOrderCount, historyCount)
                    subBar.visibility = View.VISIBLE
                    when (primaryTab) {
                        0 -> styleSub(listOf("Open ($openPositionCount)", "Closed (${history.size})"))
                        1 -> styleSub(listOf("Basic ($basicCount)", "TP ($tpCount)", "SL ($slCount)"))
                        2 -> styleSub(listOf("Fills (${fillRecords.size})", "Closes (${history.size})"))
                    }
                    body.removeAllViews()
                    when (primaryTab) {
                        0 -> if (positionSub == 0) renderPositionsOpen(body, openPositions, prices, priceState) else renderPositionsClosed(body, history)
                        1 -> when (orderSub) {
                            0 -> renderOrdersBasic(body, openEntryOrders, reentries, conditionalOpposites, prices, priceState)
                            1 -> renderOrdersTp(body, openPositions, prices, priceState)
                            else -> renderOrdersSl(body, protectiveSlOrders, cycleById, prices, priceState)
                        }
                        2 -> if (historySub == 0) renderHistoryFills(body, fillRecords) else renderHistoryCloses(body, history)
                    }
                } catch (e: Exception) {
                    body.removeAllViews(); body.addView(text("Unable to load", 12f, red))
                }
            }
        }
        var lastPrimary = -1
        primaryViews = listOf("Position","Open Order","History").mapIndexed { i, _ ->
            TextView(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); textSize = 12f
                setOnClickListener {
                    if (primaryTab != i) {
                        primaryTab = i
                        if (i == 2) historySub = 1 // Closes selected by default every time History is entered
                        rebuildSubTabs()
                        render()
                    }
                }
            }.also { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        rebuildSubTabs()
        stylePrimary(0,0,0); styleSub(listOf("Open","Closed")); render()
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

    private fun addProminentLabel(parent: LinearLayout, label: String, value: String, valueColor: Int = white) {
        val line = row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        line.addView(text(label, 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
        line.addView(text(value, 15f, valueColor, true).apply {
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            typeface = Typeface.MONOSPACE
        })
        parent.addView(line)
        addSpace(parent, 8)
    }

    private fun trailingActivationPriceFor(e: PaperEntryEntity): Double? {
        val base = e.trailingBasePrice ?: e.fillPrice ?: return null
        val act = e.trailingActivationPercent / 100.0
        if (act <= 0.0) return null
        return if (e.direction == "LONG") base * (1.0 + act) else base * (1.0 - act)
    }

    private fun trailingStatusFor(cycle: PaperCycleEntity, e: PaperEntryEntity, px: Double?): String {
        val hitNow = if (px != null && e.trailingActivated && e.trailingStopPrice != null) {
            if (e.direction == "LONG") px <= (e.trailingStopPrice ?: Double.NEGATIVE_INFINITY) + 1e-9 else px >= (e.trailingStopPrice ?: Double.POSITIVE_INFINITY) - 1e-9
        } else false
        return when {
            e.status == "CLOSED" -> "CLOSED"
            e.status == "FILLED" && cycle.status == "CLOSING" -> "CLOSING"
            hitNow -> "HIT"
            e.trailingActivated -> "ACTIVE"
            else -> "WAITING"
        }
    }

    private fun statusBadgeColor(status: String): Int = when (status) {
        "ACTIVE" -> green
        "WAITING" -> yellow
        "HIT", "CLOSING" -> red
        "CLOSED", "FILLED" -> green
        "CANCELLED", "REJECTED" -> muted
        else -> muted
    }

    private fun renderPositionsOpen(body: LinearLayout, active: List<PaperCycleWithEntries>, prices: Map<String, Ticker>, priceState: String) {
        val openEntries = active.flatMap { cwe -> cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }.map { cwe.cycle to it } }
        if (openEntries.isEmpty()) { body.addView(text("No open positions.", 13f, muted)); return }
        for ((c, e) in openEntries) {
            val px = prices[c.symbol]?.markPrice ?: prices[c.symbol]?.lastPrice
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
            header.addView(text(c.symbol, 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("${c.direction}  ${c.leverage}x", 13f, if (c.direction == "LONG") green else red, true))
            box.addView(header)
            addSpace(box, 4)
            addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
            addLabel(box, "Entry Price", PriceFormatter.formatPrice(entryPrice), white, true)
            val avg = c.averageEntryPrice
            val showAvg = avg != null && (c.averagingDone || (entryPrice != null && kotlin.math.abs(avg - entryPrice) > 1e-9))
            if (showAvg) addLabel(box, "Average Entry Price", PriceFormatter.formatPrice(avg), white, true)
            addLabel(box, "Position Quantity", PriceFormatter.formatQty(entryQty), white, true)
            addLabel(box, "Margin", PriceFormatter.formatUsdt(entryMargin) + " USDT", white, true)
            val activationPrice = trailingActivationPriceFor(e)
            addLabel(box, "Activation Price", activationPrice?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            if (e.trailingActivated && e.trailingStopPrice != null) {
                addLabel(box, "Current Trailing Stop Price", PriceFormatter.formatPrice(e.trailingStopPrice), yellow, true)
            }
            addLabel(box, "Activation", "${PriceFormatter.formatPercent(e.trailingActivationPercent)}", white, true)
            addLabel(box, "Callback Rate", "${PriceFormatter.formatPercent(e.trailingCallbackPercent)}", white, true)
            addLabel(box, "Entry Stop Loss", PriceFormatter.formatPrice(e.slPrice), red, true)
            addLabel(box, "ROI", PriceFormatter.formatPercent(roi), pnlColor(roi), true)
            addLabel(box, "Unrealized PNL", if (unreal == null) "--" else PriceFormatter.formatPnl(unreal) + " USDT", pnlColor(unreal), true)
            addSpace(box, 2)
            box.addView(text("Trailing Stop Status", 12f, muted, true))
            addSpace(box, 4)
            val status = trailingStatusFor(c, e, px)
            box.addView(text(status, 13f, statusBadgeColor(status), true))
        }
    }

    private fun renderPositionsClosed(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No closed positions.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TP", "TRAILING_STOP" -> green; "SL","LIQUIDATION" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Entry", PriceFormatter.formatPrice(h.entryFillPrice), white, true)
            addLabel(box, "Exit", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Quantity", PriceFormatter.formatQty(h.closedQuantity))
            addLabel(box, "Margin / Leverage", "${PriceFormatter.formatUsdt(h.entryMargin)} USDT / ${h.leverage}x")
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} USDT (${PriceFormatter.formatPercent(h.realizedPnlPercent)})", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "Closed", tf.format(java.util.Date(h.exitTimestamp)))
        }
    }

    private fun renderOrdersBasic(body: LinearLayout, entryOrders: List<PaperOrderEntity>, reentries: List<PaperReentryPlanEntity>, conditionalOpposites: List<PaperCycleEntity>, prices: Map<String, Ticker>, priceState: String) {
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
            addLabel(box, "Created At", fmtDateTime(o.createdAt), muted)
        }
        for (r in reentries.sortedByDescending { it.createdAt }) {
            any = true
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${r.symbol} ${r.direction}", 15f, if (r.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("Re-entry", 12f, yellow, true)); box.addView(header)
            addLabel(box, "Order Price", PriceFormatter.formatPrice(r.triggerPrice), white, true)
            addLabel(box, "Current Price", currentPriceText(prices, r.symbol, priceState), white, true)
            addLabel(box, "Status", r.status, muted, true)
            addLabel(box, "Created At", fmtDateTime(r.createdAt), muted)
        }
        // Conditional opposite-side triggers (awaiting averaging execution) kept as basic open orders
        for (c in conditionalOpposites) {
            any = true
            val oppositeDir = if (c.direction == "LONG") "SHORT" else "LONG"
            val box = makeItemCard(body)
            val header = row()
            header.addView(text(c.symbol, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("WAITING", 12f, yellow, true))
            box.addView(header)
            addLabel(box, "Position Side", oppositeDir, if (oppositeDir == "LONG") green else red, true)
            addLabel(box, "Order Type", "CONDITIONAL TRIGGER", yellow, true)
            addLabel(box, "Order Status", "WAITING", yellow, true)
            addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
            addLabel(box, "Order Quantity", "--", muted)
            addLabel(box, "Reduce Only", "NO", white, true)
            addLabel(box, "Created At", fmtDateTime(c.updatedAt), muted)
            addLabel(box, "Updated At", fmtDateTime(c.updatedAt), muted)
        }
        if (!any) body.addView(text("No basic open orders.", 13f, muted))
    }

    private fun renderOrdersTp(body: LinearLayout, openPositions: List<PaperCycleWithEntries>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        // Trailing Stop (Take Profit) orders - one per open executed entry, derived from actual engine state
        for (cwe in openPositions) {
            val c = cwe.cycle
            val openEntries = cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            for (e in openEntries) {
                any = true
                val px = prices[c.symbol]?.markPrice ?: prices[c.symbol]?.lastPrice
                val status = trailingStatusFor(c, e, px)
                val activationPrice = trailingActivationPriceFor(e)
                val box = makeItemCard(body)
                val header = row()
                header.addView(text(c.symbol, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(text(status, 12f, statusBadgeColor(status), true))
                box.addView(header)
                addLabel(box, "Position Side", c.direction, if (c.direction == "LONG") green else red, true)
                addLabel(box, "Order Type", "TRAILING STOP", yellow, true)
                addLabel(box, "Order ID", e.trailingOrderId?.toString() ?: "TS-${e.id}", muted)
                addLabel(box, "Current Price", currentPriceText(prices, c.symbol, priceState), white, true)
                val triggerValue = e.trailingStopPrice?.let { PriceFormatter.formatPrice(it) } ?: "Not activated"
                addProminentLabel(box, "Trailing Stop Price", triggerValue, yellow)
                addLabel(box, "Activation Price", if (activationPrice != null) PriceFormatter.formatPrice(activationPrice) else "--", white, true)
                addLabel(box, "Activation", "${PriceFormatter.formatPercent(e.trailingActivationPercent)}", white, true)
                addLabel(box, "Callback Rate", "${PriceFormatter.formatPercent(e.trailingCallbackPercent)}", white, true)
                addLabel(box, "Order Quantity", PriceFormatter.formatQty(e.remainingQuantity), white, true)
                addLabel(box, "Reduce Only", "YES", white, true)
                addLabel(box, "Created At", fmtDateTime(e.filledAt ?: e.createdAt), muted)
                addLabel(box, "Updated At", fmtDateTime(e.updatedAt), muted)
            }
        }
        if (!any) body.addView(text("No TP orders.", 13f, muted))
    }

    private fun renderOrdersSl(body: LinearLayout, protectiveSlOrders: List<PaperOrderEntity>, cycleById: Map<Long, PaperCycleEntity>, prices: Map<String, Ticker>, priceState: String) {
        var any = false
        // Stop Loss orders (actual active SL orders)
        for (o in protectiveSlOrders.sortedByDescending { it.updatedAt }) {
            any = true
            val cycle = cycleById[o.cycleId]
            val status = when (o.status) {
                "ACTIVE" -> "ACTIVE"
                "CANCELLING" -> "CLOSING"
                "FILLED" -> "FILLED"
                "CANCELLED", "CANCEL_VERIFIED" -> "CANCELLED"
                "REJECTED", "FAILED" -> "REJECTED"
                else -> o.status
            }
            val box = makeItemCard(body)
            val header = row()
            header.addView(text(o.symbol, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(status, 12f, statusBadgeColor(status), true))
            box.addView(header)
            val side = cycle?.direction ?: o.direction
            addLabel(box, "Position Side", side, if (side == "LONG") green else red, true)
            addLabel(box, "Order Type", "STOP LOSS", yellow, true)
            addLabel(box, "Order ID", o.exchangeOrderId ?: o.clientOrderId, muted)
            addLabel(box, "Current Price", currentPriceText(prices, o.symbol, priceState), white, true)
            addLabel(box, "Trigger Price", PriceFormatter.formatPrice(o.triggerPrice), red, true)
            addLabel(box, "Order Quantity", PriceFormatter.formatQty(o.quantity), white, true)
            addLabel(box, "Reduce Only", "YES", white, true)
            addLabel(box, "Created At", fmtDateTime(o.createdAt), muted)
            addLabel(box, "Updated At", fmtDateTime(o.updatedAt), muted)
        }
        if (!any) body.addView(text("No SL orders.", 13f, muted))
    }

    private fun renderHistoryFills(body: LinearLayout, fills: List<Pair<PaperCycleEntity, PaperEntryEntity>>) {
        if (fills.isEmpty()) { body.addView(text("No fills.", 13f, muted)); return }
        for ((c, e) in fills) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${c.symbol} ${e.direction}", 15f, if (e.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            val status = when {
                e.status == "FILLED" || e.status == "CLOSED" || e.filledAt != null -> "FILLED"
                else -> e.status
            }
            header.addView(text(status, 12f, if (status == "FILLED") green else muted, true))
            box.addView(header)
            addLabel(box, "Fill Price", PriceFormatter.formatPrice(e.fillPrice), white, true)
            val execQty = if (e.filledQuantity > 1e-12) e.filledQuantity else e.quantity
            addLabel(box, "Executed Quantity", if (execQty > 1e-12) PriceFormatter.formatQty(execQty) else "--", white, true)
            addLabel(box, "Margin", "${PriceFormatter.formatUsdt(e.margin)} USDT", white, true)
            addLabel(box, "Leverage", "${e.leverage}x", white, true)
            addLabel(box, "Execution Time", fmtDateTime(e.filledAt ?: e.updatedAt), muted)
            if (e.entryType.isNotBlank()) addLabel(box, "Entry Type", e.entryType.replace('_', ' '), muted)
        }
    }

    private fun renderHistoryCloses(body: LinearLayout, history: List<PaperHistoryCloseEntity>) {
        if (history.isEmpty()) { body.addView(text("No closes.", 13f, muted)); return }
        val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (h in history) {
            val box = makeItemCard(body)
            val header = row()
            header.addView(text("${h.symbol} ${h.direction}", 15f, if (h.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text(h.closingReason, 12f, when (h.closingReason) { "TP", "TRAILING_STOP" -> green; "SL","LIQUIDATION" -> red; else -> white }, true))
            box.addView(header)
            addLabel(box, "Closing Price", PriceFormatter.formatPrice(h.exitFillPrice), white, true)
            addLabel(box, "Closed Quantity", PriceFormatter.formatQty(h.closedQuantity), white, true)
            addLabel(box, "Realized PNL", "${PriceFormatter.formatPnl(h.realizedPnlUsdt)} USDT", pnlColor(h.realizedPnlUsdt), true)
            addLabel(box, "ROI", "${PriceFormatter.formatPercent(h.realizedPnlPercent)}", pnlColor(h.realizedPnlPercent), true)
            addLabel(box, "Closing Reason", h.closingReason.replace('_', ' '), if (h.closingReason == "TRAILING_STOP") green else white, true)
            addLabel(box, "Closing Time", tf.format(java.util.Date(h.exitTimestamp)), muted)
            addLabel(box, "Entry Price", PriceFormatter.formatPrice(h.entryFillPrice), muted, true)
            addLabel(box, "Margin / Leverage", "${PriceFormatter.formatUsdt(h.entryMargin)} USDT / ${h.leverage}x", muted)
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