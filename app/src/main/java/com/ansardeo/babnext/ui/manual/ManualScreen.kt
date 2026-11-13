package com.ansardeo.babnext.ui.manual

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ManualScreen(vm: ManualViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.symbol) { vm.refreshMarket() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Manual Entry", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = s.symbol, onValueChange = { vm.setSymbol(it) }, label = { Text("Symbol") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.side.name == "LONG", onClick = { vm.setSide(com.ansardeo.babnext.core.model.Side.LONG) }, label = { Text("LONG") })
            FilterChip(selected = s.side.name == "SHORT", onClick = { vm.setSide(com.ansardeo.babnext.core.model.Side.SHORT) }, label = { Text("SHORT") })
        }
        OutlinedTextField(value = s.entryPrice, onValueChange = { vm.setEntryPrice(it) }, label = { Text("Entry LIMIT price") })
        OutlinedTextField(value = s.margin, onValueChange = { vm.setMargin(it) }, label = { Text("Margin (USDT)") })
        OutlinedTextField(value = s.leverage, onValueChange = { vm.setLeverage(it) }, label = { Text("Leverage") })
        Text("Market price: ${s.marketPrice}")
        Text("24h: high ${s.stats.high24h} low ${s.stats.low24h} change ${s.stats.change24h}% vol ${s.stats.volume24h}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.submitLimitOrder() }, enabled = !s.submitting) { Text(if (s.submitting) "Submitting..." else "Submit LIMIT Order") }
        if (s.status.isNotBlank()) Text(s.status)
        if (!s.hasCredentials) Text("Configure Demo API credentials in Settings", color = MaterialTheme.colorScheme.error)
    }
}