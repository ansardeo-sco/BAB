package com.ansardeo.babnext

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.ansardeo.babnext.data.settings.SettingValue
import com.ansardeo.babnext.data.settings.SettingsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "settings-persistence-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun settingsSurviveDatabaseRecreation() = runBlocking {
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, SettingsDatabase::class.java, name).build()
        first.settings().save(SettingValue("", "margin", "50"))
        first.settings().save(SettingValue("BTCUSDT", "margin", "80"))
        first.close()
        val second = Room.databaseBuilder(context, SettingsDatabase::class.java, name).build()
        try {
            assertEquals(setOf(SettingValue("", "margin", "50"), SettingValue("BTCUSDT", "margin", "80")), second.settings().observeAll().first().toSet())
            second.settings().clearOverride("BTCUSDT", "margin")
            assertEquals(listOf(SettingValue("", "margin", "50")), second.settings().observeAll().first())
        } finally { second.close() }
    }
}
