package com.ansardeo.babnext.ui.positions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PositionsScreen() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Positions", "Open Orders", "History")
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Positions", style = MaterialTheme.typography.titleLarge)
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> {
                var sub by remember { mutableStateOf(0) }
                TabRow(selectedTabIndex = sub) {
                    listOf("Open", "Closed").forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) }) }
                }
                Text("Open/Closed positions will appear here after Demo trading.", Modifier.padding(16.dp))
            }
            1 -> {
                var sub by remember { mutableStateOf(0) }
                TabRow(selectedTabIndex = sub) {
                    listOf("Basic", "Conditional").forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) }) }
                }
                Text("Open orders (basic/conditional) will appear here.", Modifier.padding(16.dp))
            }
            2 -> {
                var sub by remember { mutableStateOf(0) }
                TabRow(selectedTabIndex = sub) {
                    listOf("Open", "Closed").forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) }) }
                }
                Text("History will appear here.", Modifier.padding(16.dp))
            }
        }
    }
}