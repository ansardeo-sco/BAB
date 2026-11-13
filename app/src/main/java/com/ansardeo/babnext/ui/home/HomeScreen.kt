package com.ansardeo.babnext.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("BAB-Next Home", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { vm.refresh() }, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        if (state.stale) Text("Stale data", color = MaterialTheme.colorScheme.tertiary)
        if (state.lastUpdate > 0) Text("Last update: ${state.lastUpdate}", style = MaterialTheme.typography.labelSmall)

        LazyColumn {
            item { SectionTitle("Top 10 by Volume") }
            items(state.topVolume) { TickerRow(it) }
            item { SectionTitle("Top 10 Gainers") }
            items(state.gainers) { TickerRow(it) }
            item { SectionTitle("Top 10 Losers") }
            items(state.losers) { TickerRow(it) }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun TickerRow(t: com.ansardeo.babnext.core.model.MarketTicker) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.symbol)
        Text(String.format("%.4f", t.price))
        Text(String.format("%.2f%%", t.priceChangePercent))
    }
}