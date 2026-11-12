package com.ansardeo.binancebot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Binance USDⓈ-M Futures Demo API credentials (Stage 2 only).
 * Uses Android Keystore-backed encryption. Never logs or exposes secrets.
 */
object DemoCredentialsStore {
    private const val PREFS = "bab_demo_api_secure"
    private const val KEY_API = "demo_api_key"
    private const val KEY_SECRET = "demo_api_secret"

    data class Credentials(val apiKey: String, val secretKey: String) {
        val hasBoth: Boolean get() = apiKey.isNotBlank() && secretKey.isNotBlank()
    }

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(context: Context): Credentials = try {
        val p = prefs(context)
        Credentials(
            apiKey = p.getString(KEY_API, "") ?: "",
            secretKey = p.getString(KEY_SECRET, "") ?: ""
        )
    } catch (_: Exception) {
        // If keystore/encryption state is invalid, do not expose details.
        Credentials("", "")
    }

    fun save(context: Context, apiKey: String, secretKey: String): Boolean = try {
        prefs(context).edit()
            .putString(KEY_API, apiKey.trim())
            .putString(KEY_SECRET, secretKey.trim())
            .apply()
        true
    } catch (_: Exception) {
        false
    }

    fun clear(context: Context): Boolean = try {
        prefs(context).edit().clear().apply()
        true
    } catch (_: Exception) {
        false
    }

    fun hasCredentials(context: Context): Boolean = load(context).hasBoth
}
