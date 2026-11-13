package com.ansardeo.babnext.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ansardeo.babnext.core.model.MarketTicker
import com.ansardeo.babnext.data.remote.BinanceApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val loading: Boolean = false,
    val error: String? = null,
    val stale: Boolean = false,
    val lastUpdate: Long = 0L,
    val topVolume: List<MarketTicker> = emptyList(),
    val gainers: List<MarketTicker> = emptyList(),
    val losers: List<MarketTicker> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val api: BinanceApi) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val tickers = api.ticker24hr()
                val mapped = tickers.map {
                    MarketTicker(
                        symbol = it.symbol,
                        price = it.lastPrice.toDoubleOrNull() ?: 0.0,
                        priceChangePercent = it.priceChangePercent.toDoubleOrNull() ?: 0.0,
                        volume = it.quoteVolume.toDoubleOrNull() ?: 0.0,
                        high24h = it.highPrice.toDoubleOrNull() ?: 0.0,
                        low24h = it.lowPrice.toDoubleOrNull() ?: 0.0
                    )
                }
                val topVolume = mapped.sortedByDescending { it.volume }.take(10)
                val gainers = mapped.sortedByDescending { it.priceChangePercent }.take(10)
                val losers = mapped.sortedBy { it.priceChangePercent }.take(10)
                _state.value = HomeState(
                    loading = false,
                    topVolume = topVolume,
                    gainers = gainers,
                    losers = losers,
                    lastUpdate = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error", stale = true)
            }
        }
    }
}