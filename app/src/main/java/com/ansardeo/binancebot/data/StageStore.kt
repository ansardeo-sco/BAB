package com.ansardeo.binancebot.data

import android.content.Context

object StageStore {
    private const val PREFS = "bab_stage_prefs"
    private const val KEY = "selected_stage"
    private const val KEY_LIVE_ENABLED = "stage3_live_enabled"
    private const val KEY_LIVE_ACK = "stage3_live_ack"

    fun current(context: Context): TradingStage =
        TradingStage.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, TradingStage.STAGE_1.id))

    fun setCurrent(context: Context, stage: TradingStage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, stage.id).apply()
    }

    /** Live Trading (Stage 3) is disabled by default and requires explicit user enablement. */
    fun isLiveEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE_ENABLED, false)

    fun setLiveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
    }

    fun isLiveAcknowledged(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE_ACK, false)

    fun setLiveAcknowledged(context: Context, ack: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE_ACK, ack).apply()
    }
}
