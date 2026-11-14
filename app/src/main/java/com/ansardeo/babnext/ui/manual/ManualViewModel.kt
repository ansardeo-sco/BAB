package com.ansardeo.babnext.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ansardeo.babnext.core.model.Side
import com.ansardeo.babnext.data.remote.BinanceApi
import com.ansardeo.babnext.data.settings.SettingsCatalog
import com.ansardeo.babnext.data.settings.SettingsDao
import com.ansardeo.babnext.data.settings.SettingValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManualState(
    val symbol: String = "BTCUSDT",
    val side: Side = Side.LONG,
    val entryPrice: String = "",
    val margin: String = "100",
    val leverage: String = "10",
    val marketPrice: Double = 0.0,
    val stats: ManualStats = ManualStats(),
    val status: String = "",
    val submitting: Boolean = false
)

data class ManualStats(
    val high7d: Double = 0.0, val low7d: Double = 0.0, val rsi7d: Double = 0.0,
    val high24h: Double = 0.0, val low24h: Double = 0.0, val rsi24h: Double = 0.0,
    val change24h: Double = 0.0, val volume24h: Double = 0.0
)

@HiltViewModel
class ManualViewModel @Inject constructor(
    private val api: BinanceApi,
    private val settings: SettingsDao
) : ViewModel() {
    private val _state = MutableStateFlow(ManualState())
    val state: StateFlow<ManualState> = _state
    private var values: List<SettingValue> = emptyList()
    private var marginEdited = false
    private var leverageEdited = false

    init {
        viewModelScope.launch {
            settings.observeAll().collect {
                values = it
                applyDefaults()
            }
        }
    }

    private fun applyDefaults() {
        val current = _state.value
        fun setting(key: String) = SettingsCatalog.effective(values, current.symbol, SettingsCatalog.definitions.first { it.key == key }).first
        _state.value = current.copy(
            margin = if (marginEdited) current.margin else setting("margin"),
            leverage = if (leverageEdited) current.leverage else setting("leverage")
        )
    }

    fun setSymbol(s: String) {
        marginEdited = false
        leverageEdited = false
        _state.value = _state.value.copy(symbol = s.uppercase(), marketPrice = 0.0, entryPrice = "", stats = ManualStats())
        applyDefaults()
    }
    fun setSide(s: Side) { _state.value = _state.value.copy(side = s) }
    fun setEntryPrice(p: String) { _state.value = _state.value.copy(entryPrice = p) }
    fun setMargin(m: String) { marginEdited = true; _state.value = _state.value.copy(margin = m) }
    fun setLeverage(l: String) { leverageEdited = true; _state.value = _state.value.copy(leverage = l) }

    fun refreshMarket() {
        viewModelScope.launch {
            val s = _state.value.symbol
            try {
                val tickers = api.ticker24hr()
                val t = tickers.firstOrNull { it.symbol == s }
                if (t != null && _state.value.symbol == s) {
                    val price = t.lastPrice.toDoubleOrNull() ?: 0.0
                    _state.value = _state.value.copy(
                        entryPrice = _state.value.entryPrice.ifBlank { if (price > 0) t.lastPrice else "" },
                        marketPrice = price,
                        stats = _state.value.stats.copy(
                            high24h = t.highPrice.toDoubleOrNull() ?: 0.0,
                            low24h = t.lowPrice.toDoubleOrNull() ?: 0.0,
                            change24h = t.priceChangePercent.toDoubleOrNull() ?: 0.0,
                            volume24h = t.quoteVolume.toDoubleOrNull() ?: 0.0
                        )
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "Market error: ${e.message}")
            }
        }
    }

    fun submitLimitOrder() {
        _state.value = _state.value.copy(status = "Virtual order execution is not yet available")
    }
}
