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
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import org.json.JSONObject
import java.math.RoundingMode
import java.math.BigDecimal
import com.ansardeo.binancebot.paper.db.PaperCycleEntity
import com.ansardeo.binancebot.paper.db.PaperCycleWithEntries
import com.ansardeo.binancebot.paper.db.PaperDatabase
import com.ansardeo.binancebot.paper.db.PaperEntryEntity
import com.ansardeo.binancebot.paper.db.PaperHistoryCloseEntity
import com.ansardeo.binancebot.paper.db.PaperOrderEntity
import com.ansardeo.binancebot.paper.db.PaperReentryPlanEntity
import com.ansardeo.binancebot.paper.db.DemoOrderEntity
import com.ansardeo.binancebot.ui.CoinDetailActivity
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val marketRepo = MarketRepository()
    private val demoRepo = DemoStageRepository()
    private val paperDb by lazy { PaperDatabase.get(this) }
    private fun stage() = StageStore.current(this)
    private fun engine(): PaperEngine = PaperEngineProvider.get(this, stage()).apply {
        symbolRulesProvider = { sym -> marketRepo.getCachedSymbolRules(sym) ?: marketRepo.getSymbolRules(sym).rules }
    }
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
                    if (stage() == TradingStage.STAGE_2) {
                        balSub.text = "Fetching Binance Futures Demo account..."
                        val snap = demoRepo.fetchSnapshot(this@MainActivity)
                        if (snap.ok) {
                            val avail = snap.availableBalance ?: 0.0
                            val total = snap.totalWalletBalance ?: avail
                            balText.text = PriceFormatter.formatUsdt(total)
                            balSub.text = "Available ${PriceFormatter.formatUsdt(avail)} | Positions: ${snap.positions.length()} | Open Orders: ${snap.openOrders.length()} | Algo: ${snap.openAlgoOrders.length()} (Binance Demo)"
                        } else {
                            balText.text = "-- USDT"
                            balSub.text = snap.error ?: "Unable to load Binance Demo account. Configure credentials in Settings."
                        }
                        return@launch
                    }
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
                } catch (_: Exception) { balSub.text = if (stage() == TradingStage.STAGE_2) "Unable to load Binance Demo wallet." else "Unable to load virtual wallet." }
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

        val savedDefaults = LocalCoinSettingsStore.loadGlobal(this)
        val levAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.gravity = Gravity.CENTER; v.textAlignment = View.TEXT_ALIGNMENT_CENTER
                v.setPadding(dp(8), dp(10), dp(8), dp(10)); return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView as? TextView) ?: TextView(context)
                v.text = getItem(position); v.setTextColor(white); v.setBackgroundColor(inputBg); v.gravity = Gravity.CENTER; v.textAlignment = View.TEXT_ALIGNMENT_CENTER
                v.setPadding(dp(12), dp(12), dp(12), dp(12)); return v
            }
        }
        leverageSpinner.adapter = levAdapter
        leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(savedDefaults.leverage).coerceAtLeast(0))
        leverageSpinner.setPopupBackgroundDrawable(rounded(inputBg, 10))
        marginInput.setText(String.format(Locale.US, "%.4f", savedDefaults.marginAmount).trimEnd('0').trimEnd('.').ifBlank { "2" })
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
            val warn = when (s) {
                TradingStage.STAGE_3 -> "STAGE 3 LIVE TRADING is disabled in this build. No real order will be placed."
                TradingStage.STAGE_2 -> "Stage 2 submits a REAL LIMIT order to Binance USDâ“ˆ-M Futures Demo. Use Demo credentials only. Order execution/state is confirmed by Binance."
                else -> "${s.shortLabel} is local simulation. No real Binance order will be submitted."
            }
            AlertDialog.Builder(this)
                .setTitle("Confirm $dir LIMIT Entry")
                .setMessage("Stage: ${s.title}\n$warn\n\n$sym $dir\nIntended LIMIT price: ${PriceFormatter.formatPrice(intended)}\nLive price: ${PriceFormatter.formatPrice(currentLivePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} | ${lev}x\n\nPlace this $dir LIMIT entry?")
                .setPositiveButton("CONFIRM") { _, _ ->
                    if (s == TradingStage.STAGE_3) { result.setTextColor(red); result.text = "[X] Stage 3 is disabled by default and real order submission is not enabled in this build."; return@setPositiveButton }
                    setButtonsEnabled(false)
                    if (s == TradingStage.STAGE_2) {
                        result.setTextColor(muted); result.text = "Submitting $dir LIMIT order to Binance Futures Demo..."
                        lifecycleScope.launch {
                            try {
                                val creds = DemoCredentialsStore.load(this@MainActivity)
                                if (!creds.hasBoth) {
                                    result.setTextColor(red); result.text = "[X] Binance Demo API credentials not configured in Settings."
                                    setButtonsEnabled(true); return@launch
                                }
                                val settings = runCatching { LocalCoinSettingsStore.load(this@MainActivity, sym) }.getOrNull()
                                    ?: runCatching { LocalCoinSettingsStore.loadGlobal(this@MainActivity) }.getOrNull()
                                if (dir == "LONG" && settings?.enabledLong == false) { result.setTextColor(red); result.text = "[X] LONG entries disabled in settings."; setButtonsEnabled(true); return@launch }
                                if (dir == "SHORT" && settings?.enabledShort == false) { result.setTextColor(red); result.text = "[X] SHORT entries disabled in settings."; setButtonsEnabled(true); return@launch }

                                val rules = marketRepo.getCachedSymbolRules(sym) ?: marketRepo.getSymbolRules(sym).rules
                                val px = rules?.roundPrice(intended, RoundingMode.DOWN) ?: intended
                                val qtyRaw = if (px > 0) (margin * lev) / px else 0.0
                                val qty = rules?.roundQty(qtyRaw, RoundingMode.DOWN) ?: qtyRaw
                                if (qty <= 0.0 || px <= 0.0) {
                                    result.setTextColor(red); result.text = "[X] Invalid price/quantity after Binance filter rounding."; setButtonsEnabled(true); return@launch
                                }
                                rules?.validatePrice(px)?.let { result.setTextColor(red); result.text = "[X] Price rejected: $it"; setButtonsEnabled(true); return@launch }
                                rules?.validateQty(qty)?.let { result.setTextColor(red); result.text = "[X] Quantity rejected: $it"; setButtonsEnabled(true); return@launch }
                                rules?.validateNotional(px, qty)?.let { result.setTextColor(red); result.text = "[X] Notional rejected: $it"; setButtonsEnabled(true); return@launch }

                                val side = if (dir == "LONG") "BUY" else "SELL"
                                val pxStr = BigDecimal.valueOf(px).stripTrailingZeros().toPlainString()
                                val qtyStr = BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString()
                                val clientId = "bab-demo-${System.currentTimeMillis()}-${(Math.random()*1_000_000).toInt()}"
                                val apiRes = demoRepo.placeLimitOrder(this@MainActivity, sym, side, qtyStr, pxStr, reduceOnly = false, clientOrderId = clientId)
                                if (apiRes.ok) {
                                    val d = apiRes.data
                                    val oid = d?.optString("orderId") ?: d?.optLong("orderId")?.toString() ?: ""
                                    val st = d?.optString("status") ?: "SUBMITTED"
                                    // Persist Demo order for reconciliation/restart recovery and fill detection.
                                    val db = paperDb
                                    val dao = db.demoOrderDao()
                                    lifecycleScope.launch {
                                        runCatching {
                                            dao.insert(DemoOrderEntity(
                                                stage = "STAGE_2", symbol = sym, direction = dir, side = side,
                                                orderType = "ENTRY_LIMIT", triggerPrice = px, limitPrice = px,
                                                quantity = qty, leverage = lev, status = st, clientOrderId = clientId,
                                                exchangeOrderId = oid.ifBlank { null }, reduceOnly = false
                                            ))
                                        }
                                    }
                                    DemoConnectionStore.save(this@MainActivity, "CONNECTED", "Last Demo order submitted")
                                    result.setTextColor(green)
                                    result.text = "[OK] Binance Demo LIMIT submitted: $sym $dir @ $pxStr qty $qtyStr (status=$st${if (oid.isNotBlank()) ", orderId=$oid" else ""}). Tracking for fills."
                                    toast("Demo LIMIT order submitted")
                                    // Poll for fill status in background.
                                    lifecycleScope.launch {
                                        var attempts = 0
                                        while (attempts < 60) {
                                            kotlinx.coroutines.delay(5000)
                                            attempts++
                                            val stRes = demoRepo.getOrder(this@MainActivity, sym, clientOrderId = clientId)
                                            if (stRes.ok) {
                                                val o = stRes.data ?: org.json.JSONObject()
                                                val newStatus = o.optString("status", st)
                                                val filledQty = o.optString("executedQty").toDoubleOrNull() ?: 0.0
                                                val avgPrice = o.optString("avgPrice").toDoubleOrNull()
                                                runCatching {
                                                    dao.getByClientOrderId(clientId)?.let { rec ->
                                                        dao.update(rec.copy(status = newStatus, filledPrice = avgPrice, filledQuantity = filledQty,
                                                            filledAt = if (newStatus == "FILLED") System.currentTimeMillis() else rec.filledAt,
                                                            updatedAt = System.currentTimeMillis()))
                                                    }
                                                }
                                                if (newStatus in setOf("FILLED", "CANCELED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH")) {
                                                    result.text = "[OK] Binance Demo LIMIT $sym $dir: status=$newStatus${if (filledQty > 0) ", filled=$filledQty" else ""}${if (avgPrice != null) " @ $avgPrice" else ""}"
                                                    break
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    DemoConnectionStore.save(this@MainActivity, "ERROR", apiRes.error ?: "Order submission failed")
                                    result.setTextColor(red)
                                    result.text = "[X] Binance Demo order failed: ${apiRes.error ?: "Request failed"}${apiRes.code?.let { " (code $it)" } ?: ""}"
                                }
                            } catch (_: Exception) {
                                DemoConnectionStore.save(this@MainActivity, "ERROR", "Order submission failed")
                                result.setTextColor(red); result.text = "[X] Binance Demo order submission failed."
                            } finally {
                                setButtonsEnabled(true)
                            }
                        }
                    } else {
                        result.setTextColor(muted); result.text = "Placing simulated $dir LIMIT entry..."
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
    private fun renderDemoPositionsPage(body: LinearLayout) {
        body.removeAllViews()
        body.addView(text("Stage 2 - Binance USDⓈ-M Futures Demo", 14f, yellow, true))
        addSpace(body, 4)
        val statusTv = text("Loading Demo data...", 12f, muted)
        body.addView(statusTv)
        lifecycleScope.launch {
            try {
                val snap = demoRepo.fetchSnapshot(this@MainActivity)
                if (!snap.ok) {
                    statusTv.text = snap.error ?: "Unable to load Binance Demo data. Configure credentials in Settings."
                    statusTv.setTextColor(red)
                    return@launch
                }
                statusTv.text = "Binance Demo data synchronized."
                statusTv.setTextColor(green)
                // Positions
                val posCard = addCard(body, "DEMO POSITIONS", "Actual Binance USDⓈ-M Futures Demo positions.")
                val positions = snap.positions
                if (positions.length() == 0) {
                    posCard.addView(text("No open Demo positions.", 12f, muted))
                } else {
                    for (i in 0 until positions.length()) {
                        val p = positions.optJSONObject(i) ?: continue
                        val sym = p.optString("symbol")
                        val amt = p.optString("positionAmt").toDoubleOrNull() ?: 0.0
                        if (kotlin.math.abs(amt) <= 1e-12) continue
                        val side = if (amt > 0) "LONG" else "SHORT"
                        val entry = p.optString("entryPrice").toDoubleOrNull()
                        val mark = p.optString("markPrice").toDoubleOrNull()
                        val pnl = p.optString("unRealizedProfit").toDoubleOrNull()
                        val lev = p.optString("leverage").toIntOrNull() ?: 0
                        val box = makeItemCard(posCard)
                        val h = row()
                        h.addView(text(sym, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                        h.addView(text(side, 12f, if (side == "LONG") green else red, true))
                        box.addView(h)
                        addLabel(box, "Quantity", PriceFormatter.formatQty(kotlin.math.abs(amt)), white, true)
                        addLabel(box, "Entry Price", entry?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
                        addLabel(box, "Mark Price", mark?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
                        addLabel(box, "Unrealized PNL", pnl?.let { PriceFormatter.formatPnl(it) } ?: "--", pnlColor(pnl), true)
                        addLabel(box, "Leverage", "${lev}x", white, true)
                    }
                }
                // Open orders (basic)
                val ordCard = addCard(body, "DEMO OPEN ORDERS (BASIC)", "Actual Binance Demo open orders.")
                val orders = snap.openOrders
                if (orders.length() == 0) {
                    ordCard.addView(text("No open Demo orders.", 12f, muted))
                } else {
                    for (i in 0 until orders.length()) {
                        val o = orders.optJSONObject(i) ?: continue
                        val sym = o.optString("symbol")
                        val side = o.optString("side")
                        val type = o.optString("type")
                        val price = o.optString("price").toDoubleOrNull()
                        val qty = o.optString("origQty").toDoubleOrNull()
                        val status = o.optString("status")
                        val oid = o.optString("orderId")
                        val box = makeItemCard(ordCard)
                        val h = row()
                        h.addView(text("$sym $side", 15f, if (side == "BUY") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
                        h.addView(text(status, 12f, statusBadgeColor(status), true))
                        box.addView(h)
                        addLabel(box, "Order Type", type, yellow, true)
                        addLabel(box, "Order Price", price?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
                        addLabel(box, "Quantity", qty?.let { PriceFormatter.formatQty(it) } ?: "--", white, true)
                        addLabel(box, "Order ID", oid, muted)
                    }
                }
                // Algo orders (TP/SL)
                val algoCard = addCard(body, "DEMO OPEN ALGO ORDERS (TP/SL)", "Actual Binance Demo algo/protective orders.")
                val algos = snap.openAlgoOrders
                if (algos.length() == 0) {
                    algoCard.addView(text("No open Demo algo orders.", 12f, muted))
                } else {
                    for (i in 0 until algos.length()) {
                        val a = algos.optJSONObject(i) ?: continue
                        val sym = a.optString("symbol")
                        val side = a.optString("side")
                        val type = a.optString("type")
                        val trigger = a.optString("triggerPrice").toDoubleOrNull() ?: a.optString("stopPrice").toDoubleOrNull()
                        val qty = a.optString("quantity").toDoubleOrNull() ?: a.optString("origQty").toDoubleOrNull()
                        val status = a.optString("algoStatus").ifBlank { a.optString("status") }
                        val aid = a.optString("algoId").ifBlank { a.optString("orderId") }
                        val box = makeItemCard(algoCard)
                        val h = row()
                        h.addView(text("$sym $side", 15f, if (side == "BUY") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
                        h.addView(text(status, 12f, statusBadgeColor(status), true))
                        box.addView(h)
                        addLabel(box, "Order Type", type, yellow, true)
                        addLabel(box, "Trigger Price", trigger?.let { PriceFormatter.formatPrice(it) } ?: "--", red, true)
                        addLabel(box, "Quantity", qty?.let { PriceFormatter.formatQty(it) } ?: "--", white, true)
                        addLabel(box, "Algo Order ID", aid, muted)
                    }
                }
                addSpace(body, 8)
                addButton(body, "REFRESH DEMO DATA", yellow).setOnClickListener { showPage("Future") }
            } catch (e: Exception) {
                statusTv.text = "Unable to load Demo data: ${e.message?.take(120)}"
                statusTv.setTextColor(red)
            }
        }
    }

    private fun renderDemoPositionsOpen(body: LinearLayout, snap: DemoStageRepository.DemoSnapshot) {
        body.removeAllViews()
        if (!snap.ok) { body.addView(text(snap.error ?: "Unable to load Binance Demo data.", 13f, red)); return }
        val posCard = addCard(body, "DEMO POSITIONS (OPEN)", "Actual Binance USDⓈ-M Futures Demo positions.")
        val positions = snap.positions
        var any = false
        for (i in 0 until positions.length()) {
            val p = positions.optJSONObject(i) ?: continue
            val sym = p.optString("symbol")
            val amt = p.optString("positionAmt").toDoubleOrNull() ?: 0.0
            if (kotlin.math.abs(amt) <= 1e-12) continue
            any = true
            val side = if (amt > 0) "LONG" else "SHORT"
            val entry = p.optString("entryPrice").toDoubleOrNull()
            val mark = p.optString("markPrice").toDoubleOrNull()
            val pnl = p.optString("unRealizedProfit").toDoubleOrNull()
            val lev = p.optString("leverage").toIntOrNull() ?: 0
            val box = makeItemCard(posCard)
            val h = row()
            h.addView(text(sym, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(side, 12f, if (side == "LONG") green else red, true))
            box.addView(h)
            addLabel(box, "Quantity", PriceFormatter.formatQty(kotlin.math.abs(amt)), white, true)
            addLabel(box, "Avg Entry Price", entry?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            addLabel(box, "Mark Price", mark?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            addLabel(box, "Unrealized PNL", pnl?.let { PriceFormatter.formatPnl(it) } ?: "--", pnlColor(pnl), true)
            addLabel(box, "Leverage", "${lev}x", white, true)
        }
        if (!any) posCard.addView(text("No open Demo positions.", 12f, muted))
        addSpace(body, 8)
        addButton(body, "REFRESH", yellow).setOnClickListener { showPage("Future") }
    }

    private fun renderDemoOrdersBasic(body: LinearLayout, snap: DemoStageRepository.DemoSnapshot) {
        body.removeAllViews()
        if (!snap.ok) { body.addView(text(snap.error ?: "Unable to load.", 13f, red)); return }
        val card = addCard(body, "DEMO OPEN ORDERS (BASIC)", "Regular Binance Demo open orders (incl. manual).")
        val orders = snap.openOrders
        if (orders.length() == 0) { card.addView(text("No open Demo orders.", 12f, muted)); return }
        for (i in 0 until orders.length()) {
            val o = orders.optJSONObject(i) ?: continue
            val sym = o.optString("symbol")
            val side = o.optString("side")
            val type = o.optString("type")
            val price = o.optString("price").toDoubleOrNull()
            val qty = o.optString("origQty").toDoubleOrNull()
            val filled = o.optString("executedQty").toDoubleOrNull()
            val status = o.optString("status")
            val oid = o.optString("orderId")
            val box = makeItemCard(card)
            val h = row()
            h.addView(text("$sym $side", 15f, if (side == "BUY") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(status, 12f, statusBadgeColor(status), true))
            box.addView(h)
            addLabel(box, "Order Type", type, yellow, true)
            addLabel(box, "Price", price?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            addLabel(box, "Quantity", qty?.let { PriceFormatter.formatQty(it) } ?: "--", white, true)
            addLabel(box, "Filled", filled?.let { PriceFormatter.formatQty(it) } ?: "--", white, true)
            addLabel(box, "Remaining", if (qty != null && filled != null) PriceFormatter.formatQty((qty - filled).coerceAtLeast(0.0)) else "--", white, true)
            addLabel(box, "Order ID", oid, muted)
        }
    }

    private fun renderDemoOrdersConditional(body: LinearLayout, snap: DemoStageRepository.DemoSnapshot) {
        body.removeAllViews()
        if (!snap.ok) { body.addView(text(snap.error ?: "Unable to load.", 13f, red)); return }
        val card = addCard(body, "DEMO OPEN ALGO ORDERS (CONDITIONAL)", "Binance Demo algo/protective orders (TP/SL/Trailing).")
        val algos = snap.openAlgoOrders
        if (algos.length() == 0) { card.addView(text("No open Demo algo orders.", 12f, muted)); return }
        for (i in 0 until algos.length()) {
            val a = algos.optJSONObject(i) ?: continue
            val sym = a.optString("symbol")
            val side = a.optString("side")
            val type = a.optString("type")
            val trigger = a.optString("triggerPrice").toDoubleOrNull() ?: a.optString("stopPrice").toDoubleOrNull()
            val qty = a.optString("quantity").toDoubleOrNull() ?: a.optString("origQty").toDoubleOrNull()
            val status = a.optString("algoStatus").ifBlank { a.optString("status") }
            val aid = a.optString("algoId").ifBlank { a.optString("orderId") }
            val callback = a.optString("callbackRate").ifBlank { a.optString("callbackRatePercent") }
            val box = makeItemCard(card)
            val h = row()
            h.addView(text("$sym $side", 15f, if (side == "BUY") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(status, 12f, statusBadgeColor(status), true))
            box.addView(h)
            addLabel(box, "Order Type", type, yellow, true)
            addLabel(box, "Trigger Price", trigger?.let { PriceFormatter.formatPrice(it) } ?: "--", red, true)
            addLabel(box, "Quantity", qty?.let { PriceFormatter.formatQty(it) } ?: "--", white, true)
            if (callback.isNotBlank()) addLabel(box, "Callback Rate", callback, white, true)
            addLabel(box, "Algo Order ID", aid, muted)
        }
    }

    private fun renderDemoHistoryOpen(body: LinearLayout) {
        body.removeAllViews()
        val dao = paperDb.dao()
        val sid = stageId()
        val card = addCard(body, "ACTIVE CYCLES / PENDING", "Stage 2 local tracking of detected positions and pending plans.")
        val active = dao.getActiveCyclesWithEntries(sid).filter { cwe -> cwe.cycle.status in listOf("PENDING_ENTRY","ACTIVE","CLOSING") }
        if (active.isEmpty()) { card.addView(text("No active Stage 2 cycles.", 12f, muted)) }
        for (cwe in active) {
            val c = cwe.cycle
            val box = makeItemCard(card)
            val h = row()
            h.addView(text("${c.symbol} ${c.direction}", 15f, if (c.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(c.status, 12f, yellow, true))
            box.addView(h)
            val openEntries = cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            val ts = openEntries.firstOrNull()?.trailingStopPrice
            addLabel(box, "Avg Entry", c.averageEntryPrice?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            addLabel(box, "Remaining Qty", PriceFormatter.formatQty(c.totalRemainingQuantity), white, true)
            addLabel(box, "Trailing Status", if (openEntries.any { it.trailingActivated }) "TRAILING ACTIVE" else "WAITING FOR ACTIVATION", if (openEntries.any { it.trailingActivated }) green else yellow, true)
            if (ts != null) addLabel(box, "Trailing Stop Price", PriceFormatter.formatPrice(ts), yellow, true)
            addLabel(box, "Averaging", if (c.averagingDone) "DONE" else if (c.averagingOrdersPlaced) "PLACED" else if (c.averagingTriggeredAtRoi) "TRIGGERED" else "PENDING", muted, true)
        }
        val plans = dao.getPendingReentryPlans(sid)
        if (plans.isNotEmpty()) {
            addSpace(card, 6)
            card.addView(text("Pending Re-entry Plans: ${plans.size}", 12f, yellow, true))
            for (p in plans) {
                addLabel(card, "${p.symbol} ${p.direction}", "${p.status} @ ${PriceFormatter.formatPrice(p.triggerPrice)}", muted, true)
            }
        }
        addSpace(body, 8)
        addButton(body, "REFRESH", yellow).setOnClickListener { showPage("Future") }
    }

    private fun renderOrdersConditional(body: LinearLayout, openPositions: List<PaperCycleWithEntries>, protectiveSlOrders: List<PaperOrderEntity>, cycleById: Map<Long, PaperCycleEntity>, prices: Map<String, Ticker>, priceState: String) {
        body.removeAllViews()
        // Trailing Stop (TP) conditional orders
        body.addView(text("Trailing Stop (TP) Orders", 13f, yellow, true)); addSpace(body, 4)
        renderOrdersTp(body, openPositions, prices, priceState)
        addSpace(body, 8)
        body.addView(text("Stop Loss Orders", 13f, yellow, true)); addSpace(body, 4)
        renderOrdersSl(body, protectiveSlOrders, cycleById, prices, priceState)
    }

    private fun renderHistoryOpen(body: LinearLayout, active: List<PaperCycleWithEntries>, reentries: List<PaperReentryPlanEntity>, openPositions: List<PaperCycleWithEntries>) {
        body.removeAllViews()
        if (active.isEmpty() && reentries.isEmpty()) { body.addView(text("No active cycles or pending plans.", 13f, muted)); return }
        for (cwe in active) {
            val c = cwe.cycle
            val box = makeItemCard(body)
            val h = row()
            h.addView(text("${c.symbol} ${c.direction}", 15f, if (c.direction == "LONG") green else red, true), LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(c.status, 12f, yellow, true))
            box.addView(h)
            addLabel(box, "Avg Entry", c.averageEntryPrice?.let { PriceFormatter.formatPrice(it) } ?: "--", white, true)
            addLabel(box, "Remaining Qty", PriceFormatter.formatQty(c.totalRemainingQuantity), white, true)
            val openEntries = cwe.entries.filter { it.status == "FILLED" && it.remainingQuantity > 1e-12 }
            val ts = openEntries.firstOrNull()?.trailingStopPrice
            addLabel(box, "Trailing Status", if (openEntries.any { it.trailingActivated }) "TRAILING ACTIVE" else "WAITING FOR ACTIVATION", if (openEntries.any { it.trailingActivated }) green else yellow, true)
            if (ts != null) addLabel(box, "Trailing Stop Price", PriceFormatter.formatPrice(ts), yellow, true)
            addLabel(box, "Averaging", if (c.averagingDone) "DONE" else if (c.averagingOrdersPlaced) "PLACED" else if (c.averagingTriggeredAtRoi) "TRIGGERED" else "PENDING", muted, true)
        }
        if (reentries.isNotEmpty()) {
            addSpace(body, 6)
            body.addView(text("Pending Re-entry Plans: ${reentries.size}", 12f, yellow, true))
            for (r in reentries) {
                addLabel(body, "${r.symbol} ${r.direction}", "${r.status} @ ${PriceFormatter.formatPrice(r.triggerPrice)}", muted, true)
            }
        }
    }

    private fun positionsPage() /* Future tab */ {
        stopRefresh(); updateTopStatus()
        val root = pageContainer()
        val tabBar = row().apply { background = rounded(card, 12); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(tabBar); addSpace(root, 8)
        val subBar = row().apply { background = rounded(card, 10); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        root.addView(subBar); addSpace(root, 8)
        val body = column(); root.addView(body)

        if (stage() == TradingStage.STAGE_2) {
            // Stage 2: show actual Binance Demo positions/open orders instead of empty local simulation.
            renderDemoPositionsPage(body)
            return
        }

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
        var renderTrigger: (() -> Unit)? = null
        fun rebuildSubTabs() {
            subBar.removeAllViews()
            val count = 2
            subViews = (0 until count).map { i ->
                TextView(this).apply {
                    gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 11f
                    setOnClickListener {
                        when (primaryTab) {
                            0 -> if (positionSub != i) { positionSub = i; renderTrigger?.invoke() }
                            1 -> if (orderSub != i) { orderSub = i; renderTrigger?.invoke() }
                            2 -> if (historySub != i) { historySub = i; renderTrigger?.invoke() }
                        }
                    }
                }.also { subBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            }
        }
        fun renderDemoSnapshot(body: LinearLayout, snap: DemoStageRepository.DemoSnapshot) {
            body.removeAllViews()
            if (!snap.ok) {
                body.addView(text(snap.error ?: "Unable to load Binance Demo data. Configure credentials and test connection in Settings.", 13f, red))
                return
            }
            fun renderJsonArray(title: String, arr: org.json.JSONArray, max: Int = 40, filterNonZeroPosition: Boolean = false) {
                val card = makeItemCard(body)
                var shown = 0
                card.addView(text(title, 14f, yellow, true))
                addSpace(card, 4)
                for (i in 0 until arr.length()) {
                    if (shown >= max) break
                    val o = arr.optJSONObject(i) ?: continue
                    if (filterNonZeroPosition) {
                        val amt = o.optString("positionAmt", "0").toDoubleOrNull() ?: 0.0
                        if (kotlin.math.abs(amt) <= 1e-12) continue
                    }
                    val sym = o.optString("symbol", "")
                    val side = o.optString("positionSide", o.optString("side", ""))
                    val amt = o.optString("positionAmt", o.optString("origQty", o.optString("executedQty", "")))
                    val px = o.optString("entryPrice", o.optString("price", o.optString("avgPrice", "")))
                    val st = o.optString("status", o.optString("algoStatus", ""))
                    val typ = o.optString("type", o.optString("orderType", ""))
                    val pnl = o.optString("unRealizedProfit", o.optString("unrealizedProfit", ""))
                    val line = buildString {
                        if (sym.isNotBlank()) append(sym)
                        if (side.isNotBlank()) append(" ").append(side)
                        if (typ.isNotBlank()) append(" ").append(typ)
                        if (amt.isNotBlank() && amt != "0" && amt != "0.0" && amt != "0.00000000") append("  qty=").append(amt)
                        if (px.isNotBlank() && px != "0" && px != "0.0" && px != "0.00000") append("  px=").append(px)
                        if (st.isNotBlank()) append("  [").append(st).append("]")
                        if (pnl.isNotBlank() && pnl != "0" && pnl != "0.0" && pnl != "0.00000000") append("  uPnL=").append(pnl)
                    }.ifBlank { o.toString().take(200) }
                    card.addView(text(line, 11f, white))
                    addSpace(card, 3)
                    shown++
                }
                if (shown == 0) card.addView(text("None", 12f, muted))
            }

            val info = makeItemCard(body)
            info.addView(text("Binance Futures Demo (actual account data)", 14f, yellow, true))
            addSpace(info, 4)
            addLabel(info, "Total Wallet Balance", snap.totalWalletBalance?.let { "${PriceFormatter.formatUsdt(it)} USDT" } ?: "--", green, true)
            addLabel(info, "Available Balance", snap.availableBalance?.let { "${PriceFormatter.formatUsdt(it)} USDT" } ?: "--", white, true)
            addLabel(info, "Open Orders", "${snap.openOrders.length()}", white)
            addLabel(info, "Open Algo Orders", "${snap.openAlgoOrders.length()}", white)
            addLabel(info, "Synced", DemoStageRepository.formatTs(snap.fetchedAt), muted)

            when (primaryTab) {
                0 -> {
                    if (positionSub == 0) renderJsonArray("Positions (non-zero)", snap.positions, filterNonZeroPosition = true)
                    else {
                        val c = makeItemCard(body)
                        c.addView(text("Account", 14f, yellow, true)); addSpace(c, 4)
                        c.addView(text("Wallet/available balance and position/order counts shown above are retrieved from Binance Futures Demo API. Stage 1 local simulation data is separate.", 12f, muted))
                    }
                }
                1 -> {
                    if (orderSub == 0) renderJsonArray("Open Orders", snap.openOrders) else renderJsonArray("Open Algo/Conditional Orders", snap.openAlgoOrders)
                    if (orderSub == 2) {
                        val c = makeItemCard(body); c.addView(text("SL/TP visibility depends on Binance order/algo representation. App-managed trailing/strategy automation sync is limited in this build.", 12f, muted))
                    }
                }
                else -> {
                    val note = makeItemCard(body)
                    note.addView(text("History / Fills (Binance Demo)", 14f, yellow, true))
                    addSpace(note, 4)
                    note.addView(text("Authoritative executed fills and close history for Stage 2 are on Binance Demo. Full in-app sync/reconciliation of trade/fill history is not yet implemented; local Stage 1 history remains separate.", 12f, muted))
                }
            }
        }

        fun render() {
            lifecycleScope.launch {
                try {
                    if (stage() == TradingStage.STAGE_2) {
                        subBar.visibility = View.VISIBLE
                        val snap = demoRepo.fetchSnapshot(this@MainActivity)
                        val posCount = if (snap.ok) (0 until snap.positions.length()).count { i ->
                            val a = snap.positions.optJSONObject(i)?.optString("positionAmt")?.toDoubleOrNull() ?: 0.0
                            kotlin.math.abs(a) > 1e-12
                        } else 0
                        val basicCount = if (snap.ok) snap.openOrders.length() else 0
                        val condCount = if (snap.ok) snap.openAlgoOrders.length() else 0
                        val closedCount = paperDb.dao().getHistoryCloses(stageId(), 500).size
                        stylePrimary(posCount, basicCount, closedCount)
                        when (primaryTab) {
                            0 -> styleSub(listOf("Open ($posCount)", "Closed ($closedCount)"))
                            1 -> styleSub(listOf("Basic ($basicCount)", "Conditional ($condCount)"))
                            2 -> styleSub(listOf("Open", "Closed ($closedCount)"))
                        }
                        body.removeAllViews()
                        when (primaryTab) {
                            0 -> if (positionSub == 0) renderDemoPositionsOpen(body, snap) else renderPositionsClosed(body, paperDb.dao().getHistoryCloses(stageId(), 500))
                            1 -> if (orderSub == 0) renderDemoOrdersBasic(body, snap) else renderDemoOrdersConditional(body, snap)
                            2 -> if (historySub == 0) renderDemoHistoryOpen(body) else renderHistoryCloses(body, paperDb.dao().getHistoryCloses(stageId(), 500))
                        }
                        return@launch
                    }

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
                    val openOrderCount = basicCount // Main Open Orders tab shows Basic orders only
                    val historyCount = history.size // Main History tab shows Closes only
                    stylePrimary(openPositionCount, openOrderCount, historyCount)
                    subBar.visibility = View.VISIBLE
                    when (primaryTab) {
                        0 -> styleSub(listOf("Open ($openPositionCount)", "Closed (${history.size})"))
                        1 -> styleSub(listOf("Basic ($basicCount)", "Conditional ($tpCount)"))
                        2 -> styleSub(listOf("Open", "Closed (${history.size})"))
                    }
                    body.removeAllViews()
                    when (primaryTab) {
                        0 -> if (positionSub == 0) renderPositionsOpen(body, openPositions, prices, priceState) else renderPositionsClosed(body, history)
                        1 -> if (orderSub == 0) renderOrdersBasic(body, openEntryOrders, reentries, conditionalOpposites, prices, priceState) else renderOrdersConditional(body, openPositions, protectiveSlOrders, cycleById, prices, priceState)
                        2 -> if (historySub == 0) renderHistoryOpen(body, active, reentries, openPositions) else renderHistoryCloses(body, history)
                    }
                } catch (e: Exception) {
                    body.removeAllViews(); body.addView(text("Unable to load: ${e.message?.take(60) ?: ""}", 12f, red))
                }
            }
        }
        renderTrigger = { render() }
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
            addLabel(box, "Current Price", currentPriceText(prices, o.symbol, priceState), white, true)
            addLabel(box, "Trigger Price", PriceFormatter.formatPrice(o.triggerPrice), red, true)
            addLabel(box, "Order Quantity", PriceFormatter.formatQty(o.quantity), white, true)
            addLabel(box, "Reduce Only", "YES", white, true)
            addLabel(box, "Order ID", o.exchangeOrderId ?: o.clientOrderId, muted)
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
        root.addView(text("Default settings (applied to trading). Trailing/SL/Averaging/Re-entry percentages are auto-selected from the fixed leverage table (1xâ€“20x, 25x) and are not editable. Fixed Take Profit is not used.", 12f, muted))
        val st = addCard(root, "ACTIVE STAGE", "Current: ${stage().title}")
        addActionButton(st, "CHANGE STAGE") {
            val opts = TradingStage.entries.map { it.title }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Select Trading Stage").setItems(opts) { _, i -> StageStore.setCurrent(this, TradingStage.entries[i]); updateTopStatus(); showPage("Settings") }.show()
        }
        val box = column(); root.addView(box)
        box.removeAllViews()
        val s = LocalCoinSettingsStore.loadGlobal(this)
        val sc = addCard(box, "DEFAULT CONFIGURATION", "Saved locally. Trading % come from the fixed leverage table (1xâ€“20x, 25x).")
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
        sc.addView(text("Leverage (1xâ€“20x, 25x)", 12f, muted, true))
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

        // ---- Binance Demo API Configuration (Stage 2 only) ----
        if (stage() == TradingStage.STAGE_2) {
            val demoCard = addCard(root, "BINANCE DEMO API CONFIGURATION", "Stage 2 only: Binance USDÃ¢â€œË†-M Futures Demo (https://demo-fapi.binance.com). Credentials are encrypted on-device (Android Keystore-backed). Never used for Live Trading.")
            val creds = DemoCredentialsStore.load(this)
            val statusLabel = text("", 12f, muted, true)
            fun refreshDemoStatus() {
                val st = DemoConnectionStore.status(this)
                val m = DemoConnectionStore.message(this)
                val ts = DemoConnectionStore.checkedAt(this)
                val color = when (st) { "CONNECTED" -> green; "ERROR" -> red; else -> muted }
                statusLabel.setTextColor(color)
                statusLabel.text = "Status: $st${if (m.isNotBlank()) " - $m" else ""}${if (ts > 0) " (${DemoStageRepository.formatTs(ts)})" else ""}"
            }
            demoCard.addView(statusLabel); addSpace(demoCard, 6)

            fun mkSecretInput(label: String, initial: String): Pair<EditText, Switch> {
                demoCard.addView(text(label, 12f, muted, true))
                val et = EditText(this).apply {
                    setText(initial); setTextColor(white); setHintTextColor(muted); setBackgroundColor(inputBg)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    transformationMethod = PasswordTransformationMethod.getInstance()
                }
                demoCard.addView(et, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                val sw = Switch(this).apply { text = "Show"; isChecked = false; setTextColor(muted) }
                sw.setOnCheckedChangeListener { _, checked ->
                    val sel = et.selectionStart
                    if (checked) { et.transformationMethod = null; et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
                    else { et.transformationMethod = PasswordTransformationMethod.getInstance(); et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    et.setSelection(maxOf(0, sel))
                }
                demoCard.addView(sw); addSpace(demoCard, 6)
                return et to sw
            }
            val (apiKeyEt, _) = mkSecretInput("Demo API Key", creds.apiKey)
            val (secretEt, _) = mkSecretInput("Demo Secret Key", creds.secretKey)
            val demoMsg = text("", 12f, muted); demoCard.addView(demoMsg); addSpace(demoCard, 4)

            val rowBtns = row()
            fun smallBtn(t: String, bgc: Int): TextView = TextView(this).apply {
                text = t; setTextColor(if (bgc == yellow) 0xFF000000.toInt() else white); setTypeface(typeface, Typeface.BOLD)
                textSize = 11f; gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(bgc, 10)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
            }
            val saveBtn = smallBtn("SAVE / UPDATE", green)
            val delBtn = smallBtn("DELETE / CLEAR", red)
            val testBtn = smallBtn("TEST CONNECTION", yellow)
            rowBtns.addView(saveBtn); rowBtns.addView(delBtn); rowBtns.addView(testBtn.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 })
            demoCard.addView(rowBtns)

            saveBtn.setOnClickListener {
                val ak = apiKeyEt.text.toString().trim()
                val sk = secretEt.text.toString().trim()
                if (ak.isBlank() || sk.isBlank()) { demoMsg.setTextColor(red); demoMsg.text = "Enter both Demo API Key and Secret Key."; return@setOnClickListener }
                val ok = DemoCredentialsStore.save(this, ak, sk)
                if (ok) {
                    DemoConnectionStore.save(this, "DISCONNECTED", "Credentials saved. Run Test Connection.")
                    demoMsg.setTextColor(green); demoMsg.text = "Demo credentials saved securely on this device (Stage 2 only)."
                    toast("Demo credentials saved")
                } else {
                    demoMsg.setTextColor(red); demoMsg.text = "Failed to save credentials securely."
                }
                refreshDemoStatus()
            }
            delBtn.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Delete Demo Credentials")
                    .setMessage("Remove stored Binance Futures Demo API credentials from this device?")
                    .setPositiveButton("DELETE") { _, _ ->
                        DemoCredentialsStore.clear(this); DemoConnectionStore.clear(this)
                        apiKeyEt.setText(""); secretEt.setText("")
                        demoMsg.setTextColor(yellow); demoMsg.text = "Demo credentials cleared."
                        refreshDemoStatus(); toast("Demo credentials cleared")
                    }.setNegativeButton("CANCEL", null).show()
            }
            testBtn.setOnClickListener {
                val ak = apiKeyEt.text.toString().trim().ifBlank { DemoCredentialsStore.load(this).apiKey }
                val sk = secretEt.text.toString().trim().ifBlank { DemoCredentialsStore.load(this).secretKey }
                if (ak.isBlank() || sk.isBlank()) { demoMsg.setTextColor(red); demoMsg.text = "Save Demo credentials first."; return@setOnClickListener }
                demoMsg.setTextColor(muted); demoMsg.text = "Testing Binance Futures Demo connection..."
                lifecycleScope.launch {
                    val res = demoRepo.testConnection(this@MainActivity)
                    if (res.ok) { demoMsg.setTextColor(green); demoMsg.text = "Connected to Binance Futures Demo." }
                    else { demoMsg.setTextColor(red); demoMsg.text = "Connection failed: ${res.error ?: "Unable to connect"}" }
                    refreshDemoStatus()
                }
            }
            refreshDemoStatus()
        }
    }

    // Save APK functionality removed from app UI (APK distribution handled via GitHub Actions).


    // ---------------- MORE ----------------
    // ---------------- MORE ----------------
    private fun morePage() {
        val root = pageContainer()
        root.addView(text("More", 22f, white, true))
        addSpace(root, 4)

        fun statusDot(color: Int): TextView = text("â—", 13f, color, true).apply { setPadding(0, 0, dp(6), 0) }
        fun statusRow(label: String, value: String, valueColor: Int): LinearLayout {
            val r = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(text(label, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            r.addView(text(value, 12f, valueColor, true).apply {
                gravity = Gravity.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            })
            return r
        }
        fun expandableSection(title: String, initiallyExpanded: Boolean = false): Pair<LinearLayout, LinearLayout> {
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(this@MainActivity.card, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            root.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val header = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            val titleTv = text(title, 14f, yellow, true).apply {
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val arrow = text(if (initiallyExpanded) "â–¼" else "â–¶", 14f, muted, true).apply {
                gravity = Gravity.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                setPadding(dp(8), 0, 0, 0)
            }
            header.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(arrow, LinearLayout.LayoutParams(-2, -2))
            val content = column().apply {
                visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
                setPadding(0, dp(8), 0, 0)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            header.setOnClickListener {
                val expanded = content.visibility == View.VISIBLE
                content.visibility = if (expanded) View.GONE else View.VISIBLE
                arrow.text = if (expanded) "â–¶" else "â–¼"
            }
            cardView.addView(header)
            cardView.addView(content)
            return cardView to content
        }

        val activeStage = stage()

        // 1. System Information
        val (_, sys) = expandableSection("System Information", true)
        sys.addView(statusRow("Application Name", "Binance Android Bot", white))
        sys.addView(statusRow("Application Version", BuildConfig.VERSION_NAME, white))
        sys.addView(statusRow("Build Number", BuildConfig.VERSION_CODE.toString(), white))
        sys.addView(statusRow("Build Type", if (BuildConfig.DEBUG) "Debug" else "Release", white))

        // 2. Current Trading Stage
        val (_, stgSec) = expandableSection("Current Trading Stage", true)
        val stTitle = when (activeStage) {
            TradingStage.STAGE_1 -> "Stage 1 - Virtual Trading"
            TradingStage.STAGE_2 -> "Stage 2 - Binance Demo Trading"
            TradingStage.STAGE_3 -> "Stage 3 - Live Trading"
        }
        val stDesc = when (activeStage) {
            TradingStage.STAGE_1 -> "Live public market data with a local virtual wallet (5,000.00 USDT, USDâ“ˆ-M). Simulated execution only. No real Binance orders."
            TradingStage.STAGE_2 -> "Binance USDâ“ˆ-M Futures Demo (demo-fapi). Actual Demo account, wallet, orders, and positions when configured with Demo API credentials. Demo credentials only; never used for Live."
            TradingStage.STAGE_3 -> "Live trading is disabled in this build. Requires deliberate activation and authorized, secure Live API integration (not enabled)."
        }
        val stRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        stRow.addView(statusDot(if (activeStage == TradingStage.STAGE_3) red else green))
        stRow.addView(text(stTitle, 13f, white, true))
        stgSec.addView(stRow)
        stgSec.addView(text("Active stage: ${activeStage.shortLabel}", 11f, if (activeStage == TradingStage.STAGE_3) red else green, true))
        addSpace(stgSec, 4)
        stgSec.addView(text(stDesc, 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })

        // 3. Connection Status
        val (_, connSec) = expandableSection("Connection Status", true)
        val marketStatusTv = text("Checking...", 12f, muted, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        val marketRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        marketRow.addView(text("Binance Market Data", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        marketRow.addView(marketStatusTv)
        connSec.addView(marketRow)

        var demoStatusTv: TextView? = null
        var demoMsgTv: TextView? = null
        if (activeStage == TradingStage.STAGE_2) {
            val demoRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
            demoRow.addView(text("Binance Demo API", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            val dst = text("Checking...", 12f, muted, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
            demoStatusTv = dst
            demoRow.addView(dst)
            connSec.addView(demoRow)
            val dmt = text("", 11f, muted).apply { visibility = View.GONE; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
            demoMsgTv = dmt
            connSec.addView(dmt)
        }

        val lastUpdateRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        lastUpdateRow.addView(text("Last Successful Data Update", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val lastUpdateTv = text("Checking...", 12f, white, true).apply { gravity = Gravity.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        lastUpdateRow.addView(lastUpdateTv)
        connSec.addView(lastUpdateRow)

        val marketErrorTv = text("", 11f, red).apply { visibility = View.GONE; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        connSec.addView(marketErrorTv)

        if (activeStage != TradingStage.STAGE_2) {
            connSec.addView(text("Demo API connection status is shown in Stage 2 only.", 11f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        }

        fun applyMarketOverview(ov: MarketOverview?) {
            val connected = ov?.dataAvailable == true
            val stale = ov?.stale == true
            val status = when {
                ov == null -> "Disconnected"
                connected && !stale -> "Connected"
                connected && stale -> "Stale"
                else -> "Disconnected"
            }
            val color = when (status) { "Connected" -> green; "Stale" -> yellow; else -> red }
            marketStatusTv.text = status
            marketStatusTv.setTextColor(color)
            val ts = ov?.lastUpdate?.takeIf { it.isNotBlank() }
            lastUpdateTv.text = ts ?: "Not available"
            val err = ov?.error?.takeIf { it.isNotBlank() }
            if (!connected && err != null) {
                marketErrorTv.text = err
                marketErrorTv.visibility = View.VISIBLE
            } else {
                marketErrorTv.text = ""
                marketErrorTv.visibility = View.GONE
            }
        }

        fun refreshConnectionStatus() {
            // Always verify with a fresh public Binance market data check (read-only, no Demo API credentials).
            applyMarketOverview(lastOverview)
            lifecycleScope.launch {
                try {
                    val ov = marketRepo.getOverview()
                    lastOverview = ov
                    applyMarketOverview(ov)
                } catch (e: Exception) {
                    val fallback = lastOverview
                    if (fallback?.dataAvailable == true) {
                        applyMarketOverview(fallback)
                    } else {
                        marketStatusTv.text = "Disconnected"
                        marketStatusTv.setTextColor(red)
                        lastUpdateTv.text = lastOverview?.lastUpdate?.takeIf { it.isNotBlank() } ?: "Not available"
                        marketErrorTv.text = e.message?.takeIf { it.isNotBlank() } ?: "Unable to reach Binance market data."
                        marketErrorTv.visibility = View.VISIBLE
                    }
                }
            }
            if (activeStage == TradingStage.STAGE_2) {
                val st = DemoConnectionStore.status(this)
                val dColor = when (st) { "CONNECTED" -> green; "ERROR" -> red; else -> muted }
                val dLabel = when (st) { "CONNECTED" -> "Connected"; "ERROR" -> "Error"; else -> "Disconnected" }
                demoStatusTv?.text = dLabel
                demoStatusTv?.setTextColor(dColor)
                val msg = DemoConnectionStore.message(this)
                if (!msg.isNullOrBlank()) { demoMsgTv?.text = msg; demoMsgTv?.visibility = View.VISIBLE } else { demoMsgTv?.visibility = View.GONE }
            }
        }
        refreshConnectionStatus()

        // 4. Wallet Management
        val (_, walletSec) = expandableSection("Wallet Management", true)
        when (activeStage) {
            TradingStage.STAGE_1 -> {
                val balTv = text("Loading virtual wallet...", 12f, muted)
                val startTv = text("Starting Virtual Wallet Balance: 5,000.00 USDT", 12f, white)
                walletSec.addView(balTv)
                walletSec.addView(startTv)
                lifecycleScope.launch {
                    try {
                        val w = engine().getWallet()
                        balTv.text = "Current Virtual Wallet Balance: ${PriceFormatter.formatUsdt(w.availableBalance + w.reservedMargin)} (Available ${PriceFormatter.formatUsdt(w.availableBalance)} / Reserved ${PriceFormatter.formatUsdt(w.reservedMargin)})"
                        balTv.setTextColor(white)
                        balTv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        startTv.text = "Starting Virtual Wallet Balance: ${PriceFormatter.formatUsdt(w.startingBalance)}"
                    } catch (_: Exception) {
                        balTv.text = "Unable to load virtual wallet."
                        balTv.setTextColor(red)
                    }
                }
                addSpace(walletSec, 6)
                val resetBtn = addButton(walletSec, "RESET VIRTUAL WALLET", red)
                resetBtn.setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Reset Stage 1 Virtual Wallet?")
                        .setMessage("This will reset the Stage 1 virtual wallet to 5,000.00 USDT and clear Stage 1 simulated trading records. This action requires confirmation and does not affect Stage 2 or Stage 3.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("RESET") { _, _ ->
                            lifecycleScope.launch {
                                engine().resetDemo(5000.0)
                                toast("Stage 1 virtual wallet reset to 5,000.00 USDT")
                                showPage("More")
                            }
                        }.show()
                }
            }
            TradingStage.STAGE_2 -> {
                val demoBalTv = text("Loading Binance Demo wallet...", 12f, muted)
                walletSec.addView(demoBalTv)
                walletSec.addView(text("Source: Binance USDâ“ˆ-M Futures Demo account (not Stage 1 virtual balance).", 11f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
                lifecycleScope.launch {
                    try {
                        val snap = demoRepo.fetchSnapshot(this@MainActivity)
                        if (snap.ok) {
                            val avail = snap.availableBalance
                            val total = snap.totalWalletBalance
                            demoBalTv.text = "Available Balance: ${avail?.let { PriceFormatter.formatUsdt(it) } ?: "-- USDT"}\nTotal Wallet Balance: ${total?.let { PriceFormatter.formatUsdt(it) } ?: "-- USDT"}"
                            demoBalTv.setTextColor(white)
                        } else {
                            demoBalTv.text = "Demo wallet unavailable: ${snap.error ?: "Not connected/configured"}"
                            demoBalTv.setTextColor(red)
                        }
                    } catch (_: Exception) {
                        demoBalTv.text = "Unable to load Binance Demo wallet."
                        demoBalTv.setTextColor(red)
                    }
                }
                addSpace(walletSec, 6)
                addButton(walletSec, "SYNC DEMO ACCOUNT", yellow).setOnClickListener {
                    lifecycleScope.launch { toast("Syncing Binance Futures Demo..."); demoRepo.fetchSnapshot(this@MainActivity); showPage("More") }
                }
            }
            TradingStage.STAGE_3 -> {
                walletSec.addView(text("Live USDâ“ˆ-M wallet information is not available because Live Trading is disabled and no authorized secure Live API integration is active in this build.", 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
                addSpace(walletSec, 4)
                walletSec.addView(text("No reset action is available for Demo or Live wallets.", 11f, muted))
            }
        }

        // 5. Trading Safety
        val (_, safeSec) = expandableSection("Trading Safety", false)
        val liveStatus = "Disabled" // Stage 3 live trading remains disabled by default/build
        val apiSecurity = when {
            activeStage == TradingStage.STAGE_2 && DemoCredentialsStore.hasCredentials(this) -> "Protected (Demo credentials encrypted on-device)"
            activeStage == TradingStage.STAGE_2 -> "Not Configured (Demo API credentials required)"
            else -> "Protected (no Demo/Live credentials used by this stage)"
        }
        safeSec.addView(statusRow("Trading Stage Isolation", "Enabled", green))
        safeSec.addView(statusRow("Live Trading Status", liveStatus, if (liveStatus == "Enabled") red else green))
        safeSec.addView(statusRow("API Security Status", if (apiSecurity.startsWith("Protected")) "Protected" else "Not Configured", if (apiSecurity.startsWith("Protected")) green else yellow))
        safeSec.addView(text(apiSecurity, 11f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        addSpace(safeSec, 4)
        safeSec.addView(text("â€¢ Stage 1 uses virtual funds only (local simulation). No real Binance orders.", 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        safeSec.addView(text("â€¢ Stage 2 uses Binance Futures Demo funds/API only. Demo credentials are not used for Live.", 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        safeSec.addView(text("â€¢ Stage 3 requires deliberate activation and authorized Live API credentials. Live trading is not active in this build.", 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })

        // 6. About
        val (_, aboutSec) = expandableSection("About", false)
        aboutSec.addView(text("About BAB", 13f, white, true))
        aboutSec.addView(text("Binance Android Bot (BAB) is a multi-stage Binance USDâ“ˆ-M trading app with strict isolation between Virtual, Demo, and Live environments.", 12f, muted).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        addSpace(aboutSec, 6)
        aboutSec.addView(statusRow("Application", "BAB - Binance Android Bot", white))
        aboutSec.addView(statusRow("Version", BuildConfig.VERSION_NAME, white))
        aboutSec.addView(statusRow("Build", "${BuildConfig.VERSION_CODE} (${if (BuildConfig.DEBUG) "Debug" else "Release"})", white))
        aboutSec.addView(statusRow("Project", "ansardeo-sco/BAB", white))
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
