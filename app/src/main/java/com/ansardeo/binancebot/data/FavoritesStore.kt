package com.ansardeo.binancebot.data

import android.content.Context

object FavoritesStore {
    private const val PREFS = "manual_entry_prefs"
    private const val KEY = "favorites_symbols"
    const val MAX_FAVORITES = 10

    fun load(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "") ?: ""
        return raw.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_FAVORITES)
            .toMutableList()
    }

    fun save(context: Context, favorites: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clean = favorites.map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_FAVORITES)
        prefs.edit().putString(KEY, clean.joinToString(",")).apply()
    }

    fun isFavorite(context: Context, symbol: String): Boolean =
        load(context).contains(symbol.trim().uppercase())

    fun toggle(context: Context, symbol: String): ToggleResult {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return ToggleResult(false, load(context), "Invalid symbol")
        val favs = load(context)
        return if (favs.contains(sym)) {
            favs.remove(sym)
            save(context, favs)
            ToggleResult(false, favs, "Removed from favorites")
        } else if (favs.size >= MAX_FAVORITES) {
            ToggleResult(false, favs, "Favorites limit reached ($MAX_FAVORITES)")
        } else {
            favs.add(sym)
            save(context, favs)
            ToggleResult(true, favs, "Added to favorites")
        }
    }

    data class ToggleResult(val isFavorite: Boolean, val favorites: List<String>, val message: String)
}
