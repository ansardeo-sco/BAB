package com.ansardeo.babnext

import com.ansardeo.babnext.data.remote.PublicMarketPolicy
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageOnePolicyTest {
    @Test fun allowsPublicMarketDataOnly() {
        assertTrue(PublicMarketPolicy.permits(Request.Builder().url("https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT&interval=1h&limit=24").build()))
        for (url in listOf("https://demo-fapi.binance.com/fapi/v1/klines", "https://fapi.binance.com/fapi/v1/order", "https://fapi.binance.com/fapi/v1/klines?signature=abc", "http://fapi.binance.com/fapi/v1/klines")) {
            assertFalse(PublicMarketPolicy.permits(Request.Builder().url(url).build()))
        }
        assertFalse(PublicMarketPolicy.permits(Request.Builder().url("https://fapi.binance.com/fapi/v1/klines").header("X-MBX-APIKEY", "synthetic").build()))
        assertFalse(PublicMarketPolicy.permits(Request.Builder().url("https://fapi.binance.com/fapi/v1/klines").header("Cookie", "synthetic").build()))
        assertFalse(PublicMarketPolicy.permits(Request.Builder().url("https://fapi.binance.com/fapi/v1/klines").post(okhttp3.RequestBody.create(null, "")).build()))
    }
}
