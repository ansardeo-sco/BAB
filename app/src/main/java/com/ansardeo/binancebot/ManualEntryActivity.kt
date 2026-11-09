package com.ansardeo.binancebot

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ansardeo.binancebot.data.FavoritesStore
import com.ansardeo.binancebot.data.LocalCoinSettingsStore
import com.ansardeo.binancebot.data.MarketRepository
import com.ansardeo.binancebot.data.StageStore
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.PaperEngineProvider
import com.ansardeo.binancebot.util.PriceFormatter
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Standalone manual entry screen (manifest compatibility). Main flow uses Manual tab in MainActivity.
 * BAB: stage-aware, explicit confirmation, Stage 1/2 simulated only, Stage 3 blocked in this build.
 */
class ManualEntryActivity : AppCompatActivity() {
    private val marketRepo = MarketRepository()
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Runnable? = null
    private var currentLivePrice: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_entry)
        val stage = StageStore.current(this)
        lifecycleScope.launch { PaperEngineProvider.get(this@ManualEntryActivity, stage).ensureWallet() }

        val symbolSpinner = findViewById<Spinner>(R.id.spEntrySymbol)
        val leverageSpinner = findViewById<Spinner>(R.id.spEntryLeverage)
        val directionGroup = findViewById<RadioGroup>(R.id.rgDirection)
        val marginInput = findViewById<EditText>(R.id.etEntryMargin)
        val summary = findViewById<TextView>(R.id.txtEntrySummary)
        val livePriceTv = findViewById<TextView>(R.id.txtLivePrice)
        val liveStatus = findViewById<TextView>(R.id.txtLivePriceStatus)
        val submit = findViewById<Button>(R.id.btnSubmitEntry)
        val result = findViewById<TextView>(R.id.txtManualResult)
        findViewById<TextView?>(R.id.txtManualModeBadge)?.text = "● ${stage.title} — Stage 1/2 simulated only. No real Binance orders from Stage 1/2."

        val favs = FavoritesStore.load(this)
        if (favs.isEmpty()) {
            symbolSpinner.adapter = darkAdapter(listOf("No favorites — add from Home/Detail"))
            submit.isEnabled = false
            summary.text = "No favorite coins available. Add favorites from Home or Coin Detail, then return."
            return
        }
        symbolSpinner.adapter = darkAdapter(favs)
        val leverages = (1..125).map { "${it}x" }
        leverageSpinner.adapter = darkAdapter(leverages)
        leverageSpinner.setSelection(5)
        symbolSpinner.setPopupBackgroundDrawable(bg())
        leverageSpinner.setPopupBackgroundDrawable(bg())

        fun sym() = symbolSpinner.selectedItem?.toString()
        fun updateSummary() {
            val s = sym() ?: "--"
            val dir = if (directionGroup.checkedRadioButtonId == R.id.rbShort) "SHORT" else "LONG"
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = (leverageSpinner.selectedItemPosition + 1).coerceIn(1, 125)
            val cs = if (s != "--") LocalCoinSettingsStore.load(this, s) else null
            val px = currentLivePrice
            val tp = if (px != null && cs != null) if (dir == "LONG") px * (1 + cs.tpPercent/100.0) else px * (1 - cs.tpPercent/100.0) else null
            val sl = if (px != null && cs != null) if (dir == "LONG") px * (1 - cs.slPercent/100.0) else px * (1 + cs.slPercent/100.0) else null
            summary.text = "Stage: ${StageStore.current(this).title}\nPair: $s\nDirection: $dir\nPrice: ${PriceFormatter.formatPrice(px)} USDT\nMargin: ${String.format(Locale.US, "%.4f", margin)} | ${lev}x\nTP: ${PriceFormatter.formatPrice(tp)} | SL: ${PriceFormatter.formatPrice(sl)}\n\nPreview. Explicit confirmation required."
        }
        fun fetch() {
            val s = sym() ?: return
            lifecycleScope.launch {
                val px = marketRepo.getMarkPrice(s)
                if (sym() != s) return@launch
                currentLivePrice = px
                livePriceTv.text = PriceFormatter.formatPrice(px)
                liveStatus.text = if (px != null) "Live public mark/price" else "Price unavailable — retrying"
                liveStatus.setTextColor(ContextCompat.getColor(this@ManualEntryActivity, if (px != null) R.color.text_muted else R.color.red))
                updateSummary()
            }
            refresh?.let { handler.removeCallbacks(it) }
            val r = Runnable { fetch() }; refresh = r; handler.postDelayed(r, 8000)
        }
        val l = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { fetch(); updateSummary() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        symbolSpinner.onItemSelectedListener = l; leverageSpinner.onItemSelectedListener = l
        directionGroup.setOnCheckedChangeListener { _, _ -> updateSummary() }
        marginInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { updateSummary() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        submit.setOnClickListener {
            val s = sym() ?: return@setOnClickListener
            val px = currentLivePrice ?: run { Toast.makeText(this, "Live price unavailable", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val dir = if (directionGroup.checkedRadioButtonId == R.id.rbShort) "SHORT" else "LONG"
            val margin = marginInput.text.toString().toDoubleOrNull() ?: 0.0
            val lev = (leverageSpinner.selectedItemPosition + 1).coerceIn(1, 125)
            val stg = StageStore.current(this)
            AlertDialog.Builder(this).setTitle("Confirm Entry")
                .setMessage("${stg.title}\n${if (stg==TradingStage.STAGE_3) "Stage 3 blocked in this build." else "Simulated only. No real order."}\n\n$s $dir @ ${PriceFormatter.formatPrice(px)}")
                .setPositiveButton("CONFIRM") { _, _ ->
                    if (stg == TradingStage.STAGE_3) {
                        result.setTextColor(ContextCompat.getColor(this, R.color.red))
                        result.text = "✗ Stage 3 Live Trading is disabled. No real Binance order submitted."
                        return@setPositiveButton
                    }
                    submit.isEnabled = false; result.text = "Submitting simulated entry..."
                    lifecycleScope.launch {
                        val res = PaperEngineProvider.get(this@ManualEntryActivity, stg).openInitial(this@ManualEntryActivity, s, dir, margin, lev, px)
                        submit.isEnabled = true
                        res.onSuccess { c ->
                            result.setTextColor(ContextCompat.getColor(this@ManualEntryActivity, R.color.green))
                            result.text = "✓ ${stg.shortLabel} simulated cycle #${c.id} opened: $s $dir @ ${PriceFormatter.formatPrice(px)}"
                        }.onFailure { e ->
                            result.setTextColor(ContextCompat.getColor(this@ManualEntryActivity, R.color.red))
                            result.text = "✗ ${e.message}"
                        }
                    }
                }.setNegativeButton("CANCEL", null).show()
        }
        fetch(); updateSummary()
    }

    private fun bg() = GradientDrawable().apply {
        setColor(ContextCompat.getColor(this@ManualEntryActivity, R.color.bg_input))
        cornerRadius = (10 * resources.displayMetrics.density)
    }
    private fun darkAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? TextView) ?: TextView(context)).apply { text = getItem(position); setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setPadding(16, 12, 16, 12) }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? TextView) ?: TextView(context)).apply { text = getItem(position); setTextColor(ContextCompat.getColor(context, R.color.text_primary)); setBackgroundColor(ContextCompat.getColor(context, R.color.bg_input)); setPadding(18, 14, 18, 14) }
        }
    override fun onDestroy() { refresh?.let { handler.removeCallbacks(it) }; super.onDestroy() }
}
