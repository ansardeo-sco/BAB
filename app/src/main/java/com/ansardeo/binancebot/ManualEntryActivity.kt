package com.ansardeo.binancebot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.binancebot.data.*
import com.ansardeo.binancebot.paper.PaperEngineProvider
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone manual entry screen (LIMIT-only, spec-compliant). Stage 1/2 simulated only.
 */
class ManualEntryActivity : AppCompatActivity() {
    private val marketRepo = MarketRepository()
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Runnable? = null
    private var gen = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_entry)

        val symbolInput = findViewById<AutoCompleteTextView>(R.id.actvSymbol)
        val leverageSpinner = findViewById<Spinner>(R.id.spEntryLeverage)
        val directionGroup = findViewById<RadioGroup>(R.id.rgDirection)
        val marginInput = findViewById<EditText>(R.id.etEntryMargin)
        val entryPriceInput = findViewById<EditText>(R.id.etEntryPrice)
        val summary = findViewById<TextView>(R.id.txtEntrySummary)
        val livePriceTv = findViewById<TextView>(R.id.txtLivePrice)
        val liveStatus = findViewById<TextView>(R.id.txtLivePriceStatus)
        val statsStatus = findViewById<TextView>(R.id.txtStatsStatus)
        val stats24 = findViewById<TextView>(R.id.txtStats24h)
        val stats7 = findViewById<TextView>(R.id.txtStats7d)
        val statsRsi = findViewById<TextView>(R.id.txtStatsRsi)
        val statsDist = findViewById<TextView>(R.id.txtStatsDist)
        val submit = findViewById<Button>(R.id.btnSubmitEntry)
        val result = findViewById<TextView>(R.id.txtManualResult)

        leverageSpinner.adapter = darkAdapter(LeverageConfig.SUPPORTED_LEVERAGES.map { "${it}x" })
        leverageSpinner.setSelection(LeverageConfig.SUPPORTED_LEVERAGES.indexOf(6).coerceAtLeast(0))
        symbolInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>()))

        var livePrice: Double? = null
        var userEdited = false
        fun sym() = symbolInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
        fun lev() = LeverageConfig.SUPPORTED_LEVERAGES.getOrElse(leverageSpinner.selectedItemPosition) { 6 }
        fun dir() = if (directionGroup.checkedRadioButtonId == R.id.rbShort) "SHORT" else "LONG"
        fun stage() = StageStore.current(this)
        fun fmtTime(ts: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))

        lifecycleScope.launch {
            val symbols = marketRepo.getUsdmSymbols().getOrElse { FavoritesStore.load(this@ManualEntryActivity).ifEmpty { listOf("BTCUSDT","ETHUSDT") } }
            symbolInput.setAdapter(ArrayAdapter(this@ManualEntryActivity, android.R.layout.simple_dropdown_item_1line, symbols))
        }

        fun renderStats(s: MarketStats?) {
            if (s == null) { statsStatus.text = "Select a symbol."; stats24.text = ""; stats7.text = ""; statsRsi.text = ""; statsDist.text = ""; return }
            statsStatus.text = if (s.error != null) "Error/Stale: ${s.error}" else "Loaded ${fmtTime(s.fetchedAt)}"
            statsStatus.setTextColor(if (s.error != null) ContextCompat.getColor(this, R.color.red) else ContextCompat.getColor(this, R.color.green))
            fun r(v: Double?) = if (v == null) "--" else String.format(Locale.US, "%.0f", v)
            stats24.text = "24h High ${PriceFormatter.formatPrice(s.high24h)} | Low ${PriceFormatter.formatPrice(s.low24h)} | Change ${PriceFormatter.formatPercent(s.change24hPercent)} | Vol ${if (s.volume24h==null) "--" else String.format(Locale.US,"%,.0f",s.volume24h)}"
            stats7.text = "7d High ${PriceFormatter.formatPrice(s.high7d)} | Low ${PriceFormatter.formatPrice(s.low7d)}"
            statsRsi.text = "24h RSI ${r(s.rsi24h)} (${s.rsi24hInterval}) | 7d RSI ${r(s.rsi7d)} (${s.rsi7dInterval})"
            fun d(v: Double?) = if (v==null) "--" else PriceFormatter.formatPercent(v)
            statsDist.text = "Distance: 24h H ${d(s.distanceToHigh24hPct())} L ${d(s.distanceToLow24hPct())} | 7d H ${d(s.distanceToHigh7dPct())} L ${d(s.distanceToLow7dPct())}"
        }
        fun updateSummary() {
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val p = LeverageConfig.requireProfile(lev())
            val intended = entryPriceInput.text.toString().toDoubleOrNull()
            summary.text = "Symbol ${sym().ifBlank{"--"}} ${dir()} | LIMIT ${PriceFormatter.formatPrice(intended)} | Live ${PriceFormatter.formatPrice(livePrice)}\nMargin ${PriceFormatter.formatUsdt(margin)} ${lev()}x | TP ${p.tpPercent}% SL ${p.slPercent}% Avg ${p.averagingPercent}% Re ${p.reentryPullbackPercent}%\n${stage().shortLabel} simulated; all entries LIMIT."
        }
        fun load(symbol: String) {
            if (symbol.isBlank()) return
            val my = ++gen
            fun tick() {
                if (my != gen || sym() != symbol) return
                lifecycleScope.launch {
                    val px = marketRepo.getMarkPrice(symbol); val st = marketRepo.getMarketStats(symbol)
                    if (my != gen || sym() != symbol) return@launch
                    livePrice = px ?: st.livePrice
                    livePriceTv.text = "Live price: ${PriceFormatter.formatPrice(livePrice)} USDT"
                    liveStatus.text = if (livePrice != null) "Live (auto-refresh)" else "Unavailable"
                    if (!userEdited && livePrice != null && entryPriceInput.text.isNullOrBlank()) entryPriceInput.setText(PriceFormatter.formatPrice(livePrice))
                    renderStats(st); updateSummary()
                }
                val r = Runnable { tick() }; refresh = r; handler.postDelayed(r, 12000)
            }
            tick()
        }
        symbolInput.setOnItemClickListener { _,_,_,_ -> userEdited = false; load(sym()) }
        symbolInput.setOnFocusChangeListener { _, f -> if (!f) load(sym()) }
        entryPriceInput.addTextChangedListener(SimpleWatcher { if (entryPriceInput.hasFocus()) userEdited = true; updateSummary() })
        marginInput.addTextChangedListener(SimpleWatcher { updateSummary() })
        leverageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long)=updateSummary(); override fun onNothingSelected(p0: AdapterView<*>?){} }
        directionGroup.setOnCheckedChangeListener { _, _ -> updateSummary() }

        submit.setOnClickListener {
            val symbol = sym(); val intended = entryPriceInput.text.toString().toDoubleOrNull(); val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            if (!symbol.endsWith("USDT")) { toast("Select a USDⓈ-M symbol"); return@setOnClickListener }
            if (intended == null || intended <= 0.0) { toast("Enter intended LIMIT price"); return@setOnClickListener }
            if (margin <= 0.0) { toast("Enter margin"); return@setOnClickListener }
            val s = stage()
            if (s == TradingStage.STAGE_3) { result.text = "Stage 3 is disabled; no real order submitted."; result.setTextColor(ContextCompat.getColor(this, R.color.red)); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Confirm LIMIT Entry").setMessage("$symbol ${dir()} @ ${PriceFormatter.formatPrice(intended)} LIMIT\n${lev()}x margin ${PriceFormatter.formatUsdt(margin)}\n${s.shortLabel} simulated only.")
                .setPositiveButton("CONFIRM") { _, _ ->
                    submit.isEnabled = false
                    lifecycleScope.launch {
                        val res = PaperEngineProvider.get(this@ManualEntryActivity, s).openInitialLimit(this@ManualEntryActivity, symbol, dir(), margin, lev(), intended)
                        submit.isEnabled = true
                        result.text = if (res.ok) "[OK] ${res.message}" else "[X] ${res.message}"
                        result.setTextColor(ContextCompat.getColor(this@ManualEntryActivity, if (res.ok) R.color.green else R.color.red))
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        updateSummary()
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply { setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(20,20,20,20) }
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setBackgroundColor(ContextCompat.getColor(context, R.color.bg_input)); setPadding(24,24,24,24) }
    }
    private class SimpleWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { refresh?.let { handler.removeCallbacks(it) }; super.onDestroy() }
}
