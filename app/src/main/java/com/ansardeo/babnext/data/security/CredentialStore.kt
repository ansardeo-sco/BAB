package com.ansardeo.babnext.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val masterKey = MasterKey.Builder(ctx)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "babnext_creds",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveDemoCredentials(apiKey: String, apiSecret: String) {
        prefs.edit().putString("demo_api_key", apiKey).putString("demo_api_secret", apiSecret).apply()
    }

    fun getDemoApiKey(): String? = prefs.getString("demo_api_key", null)
    fun getDemoApiSecret(): String? = prefs.getString("demo_api_secret", null)

    fun hasDemoCredentials(): Boolean =
        !getDemoApiKey().isNullOrBlank() && !getDemoApiSecret().isNullOrBlank()

    fun clearDemoCredentials() {
        prefs.edit().remove("demo_api_key").remove("demo_api_secret").apply()
    }
}