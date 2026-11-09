package com.ansardeo.binancebot.data

import android.content.Context
import org.json.JSONObject

/**
 * Per-symbol BAB settings. Trading percentages (TP/SL/Averaging/Re-entry pullback) are
 * automatically derived from [leverage] via [LeverageConfig] and are NOT user-editable.
 */
data class LocalCoinSettings(
    val symbol: String,
    val enabledLong: Boolean = true,
    val enabledShort: Boolean = true,
    val marginAmount: Double = 2.0,
    val leverage: Int = 6,
    val oppositeEnabled: Boolean = true,
    val oppositeMargin: Double = 2.0,
    val averagingEnabled: Boolean = true,
    val averagingMargin: Double = 2.0,
    val reentryEnabled: Boolean = true,
) {
    init {
        LeverageConfig.requireSupported(leverage)
        require(marginAmount > 0.0) { "Margin must be positive" }
        require(averagingMargin > 0.0) { "Averaging margin must be positive" }
        require(oppositeMargin > 0.0) { "Opposite margin must be positive" }
    }

    val profile: LeverageProfile get() = LeverageConfig.requireProfile(leverage)
    val tpPercent: Double get() = profile.tpPercent
    val slPercent: Double get() = profile.slPercent
    val averagingPercent: Double get() = profile.averagingPercent
    val reentryPercent: Double get() = profile.reentryPullbackPercent

    companion object {
        fun sanitized(
            symbol: String,
            enabledLong: Boolean = true,
            enabledShort: Boolean = true,
            marginAmount: Double = 2.0,
            leverage: Int = 6,
            oppositeEnabled: Boolean = true,
            oppositeMargin: Double = 2.0,
            averagingEnabled: Boolean = true,
            averagingMargin: Double = 2.0,
            reentryEnabled: Boolean = true,
        ): LocalCoinSettings {
            val lev = if (LeverageConfig.isSupported(leverage)) leverage else 6
            fun pos(v: Double, d: Double) = if (v.isFinite() && v > 0.0) v else d
            return LocalCoinSettings(
                symbol = symbol.uppercase(),
                enabledLong = enabledLong,
                enabledShort = enabledShort,
                marginAmount = pos(marginAmount, 2.0),
                leverage = lev,
                oppositeEnabled = oppositeEnabled,
                oppositeMargin = pos(oppositeMargin, 2.0),
                averagingEnabled = averagingEnabled,
                averagingMargin = pos(averagingMargin, 2.0),
                reentryEnabled = reentryEnabled,
            )
        }
    }
}

object LocalCoinSettingsStore {
    private const val PREFS = "app_settings_local"

    fun load(context: Context, symbol: String): LocalCoinSettings {
        val sym = symbol.uppercase()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("settings_$sym", null) ?: return LocalCoinSettings.sanitized(sym)
        return try {
            val j = JSONObject(raw)
            LocalCoinSettings.sanitized(
                symbol = sym,
                enabledLong = j.optBoolean("enabledLong", true),
                enabledShort = j.optBoolean("enabledShort", true),
                marginAmount = j.optDouble("marginAmount", 2.0),
                leverage = j.optInt("leverage", 6),
                oppositeEnabled = j.optBoolean("oppositeEnabled", true),
                oppositeMargin = j.optDouble("oppositeMargin", j.optDouble("marginAmount", 2.0)),
                averagingEnabled = j.optBoolean("averagingEnabled", true),
                averagingMargin = j.optDouble("averagingMargin", j.optDouble("marginAmount", 2.0)),
                reentryEnabled = j.optBoolean("reentryEnabled", true),
            )
        } catch (_: Exception) {
            LocalCoinSettings.sanitized(sym)
        }
    }

    fun save(context: Context, settings: LocalCoinSettings) {
        LeverageConfig.requireSupported(settings.leverage)
        val j = JSONObject().apply {
            put("enabledLong", settings.enabledLong)
            put("enabledShort", settings.enabledShort)
            put("marginAmount", settings.marginAmount)
            put("leverage", settings.leverage)
            put("oppositeEnabled", settings.oppositeEnabled)
            put("oppositeMargin", settings.oppositeMargin)
            put("averagingEnabled", settings.averagingEnabled)
            put("averagingMargin", settings.averagingMargin)
            put("reentryEnabled", settings.reentryEnabled)
            // Percentages intentionally not stored; derived from leverage table.
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("settings_${settings.symbol.uppercase()}", j.toString())
            .apply()
    }
}
