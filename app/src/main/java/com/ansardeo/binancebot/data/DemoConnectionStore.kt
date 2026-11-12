package com.ansardeo.binancebot.data

import android.content.Context

object DemoConnectionStore {
    private const val PREFS = "bab_demo_conn"
    private const val KEY_STATUS = "status" // DISCONNECTED, CONNECTED, ERROR
    private const val KEY_MESSAGE = "message"
    private const val KEY_CHECKED_AT = "checked_at"

    fun save(context: Context, status: String, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_MESSAGE, message.take(200))
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, "DISCONNECTED") ?: "DISCONNECTED"

    fun message(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MESSAGE, "") ?: ""

    fun checkedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHECKED_AT, 0L)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
