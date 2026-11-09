package com.ansardeo.binancebot.data

import android.content.Context
import org.json.JSONObject

data class LocalCoinSettings(
    val symbol: String,
    val enabledLong: Boolean = true,
    val enabledShort: Boolean = true,
    val marginAmount: Double = 2.0,
    val leverage: Int = 6,
    val tpPercent: Double = 3.0,
    val slPercent: Double = 12.0,
    val warningPercent: Double = 6.0,
    val oppositeEnabled: Boolean = true,
    val oppositePercent: Double = 7.0,
    val oppositeMargin: Double = 2.0,
    val averagingEnabled: Boolean = true,
    val averagingPercent: Double = 9.0,
    val averagingMargin: Double = 2.0,
    val reentryEnabled: Boolean = true,
    val reentryPercent: Double = 1.0,
)

object LocalCoinSettingsStore {
    private const val PREFS = "app_settings_local"

    fun load(context: Context, symbol: String): LocalCoinSettings {
        val sym = symbol.uppercase()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("settings_$sym", null) ?: return LocalCoinSettings(sym)
        return try {
            val j = JSONObject(raw)
            LocalCoinSettings(
                symbol = sym,
                enabledLong = j.optBoolean("enabledLong", true),
                enabledShort = j.optBoolean("enabledShort", true),
                marginAmount = j.optDouble("marginAmount", 2.0),
                leverage = j.optInt("leverage", 6),
                tpPercent = j.optDouble("tpPercent", 3.0),
                slPercent = j.optDouble("slPercent", 12.0),
                warningPercent = j.optDouble("warningPercent", 6.0),
                oppositeEnabled = j.optBoolean("oppositeEnabled", true),
                oppositePercent = j.optDouble("oppositePercent", 7.0),
                oppositeMargin = j.optDouble("oppositeMargin", 2.0),
                averagingEnabled = j.optBoolean("averagingEnabled", true),
                averagingPercent = j.optDouble("averagingPercent", 9.0),
                averagingMargin = j.optDouble("averagingMargin", 2.0),
                reentryEnabled = j.optBoolean("reentryEnabled", true),
                reentryPercent = j.optDouble("reentryPercent", 1.0),
            )
        } catch (_: Exception) {
            LocalCoinSettings(sym)
        }
    }
}
