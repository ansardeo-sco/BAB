package com.ansardeo.babnext.ui.more

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MoreScreen() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("More", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("BAB-Next — Binance Android Bot (Demo)")
        Text("Version 1.0.0")
        Spacer(Modifier.height(8.dp))
        Text("Risk Controls", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {}) { Text("Emergency Stop") }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {}) { Text("Pause New Entries") }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {}) { Text("Cancel Pending Orders") }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = {}) { Text("Panic Close All (confirm)") }
        Spacer(Modifier.height(8.dp))
        Text("Diagnostics & Logs", style = MaterialTheme.typography.titleMedium)
        Text("Logs will appear here.")
    }
}