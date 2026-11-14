package com.ansardeo.babnext

import com.ansardeo.babnext.data.settings.SettingValue
import com.ansardeo.babnext.data.settings.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test fun globalAndOverridesHaveExplicitSources() {
        val margin = SettingsCatalog.definitions.first { it.key == "margin" }
        val values = listOf(SettingValue("", "margin", "50"), SettingValue("BTCUSDT", "margin", "80"))
        assertEquals("50" to "Global", SettingsCatalog.effective(values, "", margin))
        assertEquals("80" to "Symbol Override", SettingsCatalog.effective(values, "BTCUSDT", margin))
        assertEquals("50" to "Inherited", SettingsCatalog.effective(values, "ETHUSDT", margin))
    }
    @Test fun valuesMustBeFiniteAndPositive() {
        val leverage = SettingsCatalog.definitions.first { it.key == "leverage" }
        assertTrue(leverage.validate("10"))
        for (invalid in listOf("NaN", "Infinity", "0", "-1", "1.5", "")) assertFalse(leverage.validate(invalid))
    }
}
