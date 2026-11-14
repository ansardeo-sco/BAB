package com.ansardeo.babnext.data.settings

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(primaryKeys = ["symbol", "key"])
data class SettingValue(val symbol: String, val key: String, val value: String)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM SettingValue")
    fun observeAll(): Flow<List<SettingValue>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: SettingValue)

    @Query("DELETE FROM SettingValue WHERE symbol = :symbol AND `key` = :key")
    suspend fun clearOverride(symbol: String, key: String)
}

@Database(entities = [SettingValue::class], version = 1, exportSchema = false)
abstract class SettingsDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
}

data class SettingDefinition(val key: String, val label: String, val default: String, val boolean: Boolean = false, val integer: Boolean = false) {
    fun validate(value: String): Boolean {
        if (boolean) return value == "true" || value == "false"
        val number = value.toDoubleOrNull() ?: return false
        if (!number.isFinite() || number <= 0) return false
        return !integer || (number % 1.0 == 0.0 && number <= Int.MAX_VALUE.toDouble())
    }
}

object SettingsCatalog {
    const val GLOBAL = ""
    val definitions = listOf(
        SettingDefinition("bot", "Bot enabled", "false", boolean = true),
        SettingDefinition("trailing", "Trailing stop enabled", "true", boolean = true),
        SettingDefinition("activation", "Activation ROI (%)", "33"),
        SettingDefinition("callback", "Callback ROI (%)", "3"),
        SettingDefinition("reentry", "Re-entry enabled", "false", boolean = true),
        SettingDefinition("reentryOffset", "Re-entry offset (%)", ""),
        SettingDefinition("reentryLongMargin", "Long re-entry margin", ""),
        SettingDefinition("reentryShortMargin", "Short re-entry margin", ""),
        SettingDefinition("reentryLongLeverage", "Long re-entry leverage", "", integer = true),
        SettingDefinition("reentryShortLeverage", "Short re-entry leverage", "", integer = true),
        SettingDefinition("averaging", "Averaging enabled", "false", boolean = true),
        SettingDefinition("averagingMargin", "Averaging margin", ""),
        SettingDefinition("opposite", "Opposite-side enabled", "false", boolean = true),
        SettingDefinition("oppositeTrigger", "Opposite trigger (%)", ""),
        SettingDefinition("oppositeMargin", "Opposite-side margin", ""),
        SettingDefinition("margin", "Default margin", "100"),
        SettingDefinition("leverage", "Default leverage", "10", integer = true),
        SettingDefinition("long", "Long enabled", "true", boolean = true),
        SettingDefinition("short", "Short enabled", "true", boolean = true)
    )

    fun effective(values: List<SettingValue>, symbol: String, definition: SettingDefinition): Pair<String, String> {
        val override = values.firstOrNull { it.symbol == symbol && it.key == definition.key && symbol.isNotEmpty() }
        if (override != null) return override.value to "Symbol Override"
        val global = values.firstOrNull { it.symbol == GLOBAL && it.key == definition.key }?.value ?: definition.default
        return global to if (symbol.isEmpty()) "Global" else "Inherited"
    }
}
