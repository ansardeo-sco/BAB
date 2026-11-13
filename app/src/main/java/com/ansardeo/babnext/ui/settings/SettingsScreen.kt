package com.ansardeo.babnext.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ansardeo.babnext.data.security.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

@HiltViewModel
class SettingsViewModel @Inject constructor(val creds: CredentialStore) : ViewModel() {
    var apiKey by mutableStateOf(creds.getDemoApiKey() ?: "")
    var apiSecret by mutableStateOf(creds.getDemoApiSecret() ?: "")
    var message by mutableStateOf("")

    fun save() {
        if (apiKey.isBlank() || apiSecret.isBlank()) { message = "Both fields required"; return }
        creds.saveDemoCredentials(apiKey.trim(), apiSecret.trim())
        message = "Demo credentials saved securely (Keystore)."
    }
    fun clear() { creds.clearDemoCredentials(); apiKey = ""; apiSecret = ""; message = "Cleared." }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Binance Demo API Credentials (stored in Keystore)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = vm.apiKey, onValueChange = { vm.apiKey = it }, label = { Text("Demo API Key") })
        OutlinedTextField(value = vm.apiSecret, onValueChange = { vm.apiSecret = it }, label = { Text("Demo API Secret") }, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }) { Text("Save") }
            OutlinedButton(onClick = { vm.clear() }) { Text("Clear") }
        }
        if (vm.message.isNotBlank()) Text(vm.message)
        Spacer(Modifier.height(16.dp))
        Text("Control Dashboard (basic foundation)", style = MaterialTheme.typography.titleMedium)
        Text("Trailing activation ROI: 33%  |  Callback ROI: 3%")
        Text("Leverage default: 10x  |  Max leverage cap: 20x")
        Text("Averaging trigger: -60% ROI  |  Max 1 event/cycle")
        Text("Emergency Stop / Pause / Cancel Pending / Panic Close available in More.")
    }
}