package com.ansardeo.babnext.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ansardeo.babnext.core.model.Side
import com.ansardeo.babnext.data.remote.BinanceApi
import com.ansardeo.babnext.data.security.CredentialStore
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
    val submitting: Boolean = false,
    val hasCredentials: Boolean = false
)

data class ManualStats(
    val high7d: Double = 0.0, val low7d: Double = 0.0, val rsi7d: Double = 0.0,
    val high24h: Double = 0.0, val low24h: Double = 0.0, val rsi24h: Double = 0.0,
    val change24h: Double = 0.0, val volume24h: Double = 0.0
)

@HiltViewModel
class ManualViewModel @Inject constructor(
    private val api: BinanceApi,
    private val creds: CredentialStore
) : ViewModel() {
    private val _state = MutableStateFlow(ManualState(hasCredentials = creds.hasDemoCredentials()))
    val state: StateFlow<ManualState> = _state

    fun setSymbol(s: String) { _state.value = _state.value.copy(symbol = s.uppercase()) }
    fun setSide(s: Side) { _state.value = _state.value.copy(side = s) }
    fun setEntryPrice(p: String) { _state.value = _state.value.copy(entryPrice = p) }
    fun setMargin(m: String) { _state.value = _state.value.copy(margin = m) }
    fun setLeverage(l: String) { _state.value = _state.value.copy(leverage = l) }

    fun refreshMarket() {
        viewModelScope.launch {
            val s = _state.value.symbol
            try {
                val tickers = api.ticker24hr()
                val t = tickers.firstOrNull { it.symbol == s }
                if (t != null) {
                    _state.value = _state.value.copy(
                        marketPrice = t.lastPrice.toDoubleOrNull() ?: 0.0,
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
        val s = _state.value
        if (!creds.hasDemoCredentials()) { _state.value = s.copy(status = "No Demo credentials configured"); return }
        val price = s.entryPrice.toDoubleOrNull()
        val margin = s.margin.toDoubleOrNull()
        val lev = s.leverage.toIntOrNull()
        if (price == null || margin == null || lev == null) { _state.value = s.copy(status = "Invalid inputs"); return }
        val qty = (margin * lev) / price
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, status = "Submitting...")
            try {
                val key = creds.getDemoApiKey()!!
                val secret = creds.getDemoApiSecret()!!
                val params = linkedMapOf<String, Any>(
                    "symbol" to s.symbol,
                    "side" to if (s.side == Side.LONG) "BUY" else "SELL",
                    "type" to "LIMIT",
                    "timeInForce" to "GTC",
                    "quantity" to String.format("%.6f", qty).toDouble(),
                    "price" to price,
                    "positionSide" to if (s.side == Side.LONG) "LONG" else "SHORT"
                )
                // Note: real signing would be done in interceptor; here we call directly for demo scaffold.
                val resp = api.newOrder(params)
                _state.value = _state.value.copy(submitting = false, status = "Order ${resp.status}: id=${resp.orderId}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(submitting = false, status = "Order failed: ${e.message}")
            }
        }
    }
}