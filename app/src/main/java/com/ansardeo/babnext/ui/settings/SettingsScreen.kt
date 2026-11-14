package com.ansardeo.babnext.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ansardeo.babnext.data.settings.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val dao: SettingsDao) : ViewModel() {
    val values = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var message by mutableStateOf("")
        private set

    fun save(symbol: String, definition: SettingDefinition, value: String) {
        val trimmed = value.trim()
        if (!definition.validate(trimmed)) { message = "Invalid ${definition.label}"; return }
        if (symbol.isNotEmpty() && !symbol.matches(Regex("[A-Z0-9]{1,24}"))) { message = "Invalid symbol"; return }
        viewModelScope.launch {
            try { dao.save(SettingValue(symbol, definition.key, trimmed)); message = "Saved ${definition.label}" }
            catch (_: Exception) { message = "Could not save ${definition.label}" }
        }
    }
    fun inherit(symbol: String, definition: SettingDefinition) {
        viewModelScope.launch {
            try { dao.clearOverride(symbol, definition.key); message = "Override cleared" }
            catch (_: Exception) { message = "Could not clear override" }
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val values by vm.values.collectAsState()
    var symbol by remember { mutableStateOf("") }
    val scope = symbol.trim().uppercase()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Settings Dashboard", style = MaterialTheme.typography.titleLarge) }
        item { Text("Stage 1: Virtual  |  Stage 2: unavailable  |  Stage 3: unavailable") }
        item { Text("Virtual execution is not yet available.") }
        item { OutlinedTextField(symbol, { symbol = it }, label = { Text("Symbol override (blank = global)") }, singleLine = true) }
        item { if (vm.message.isNotBlank()) Text(vm.message) }
        item { Text("Averaging trigger is derived from leverage; formula awaiting definition.") }
        items(SettingsCatalog.definitions, key = { it.key }) { definition ->
            val (current, source) = SettingsCatalog.effective(values, scope, definition)
            Column {
                Text("${definition.label} — $source")
                if (definition.boolean) {
                    Switch(checked = current == "true", onCheckedChange = { vm.save(scope, definition, it.toString()) })
                } else {
                    var draft by remember(scope, definition.key, current) { mutableStateOf(current) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), singleLine = true)
                        Button(onClick = { vm.save(scope, definition, draft) }) { Text("Save") }
                    }
                }
                if (scope.isNotEmpty() && source == "Symbol Override") {
                    TextButton(onClick = { vm.inherit(scope, definition) }) { Text("Use global") }
                }
            }
        }
    }
}
