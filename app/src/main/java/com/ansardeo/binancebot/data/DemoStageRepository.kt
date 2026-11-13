package com.ansardeo.binancebot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 2 Binance USDⓈ-M Futures Demo data/execution access.
 * Credentials are loaded from encrypted storage and never logged.
 */
class DemoStageRepository(private val client: BinanceDemoApiClient = BinanceDemoApiClient()) {

    data class DemoSnapshot(
        val ok: Boolean,
        val error: String? = null,
        val totalWalletBalance: Double? = null,
        val availableBalance: Double? = null,
        val positions: JSONArray = JSONArray(),
        val openOrders: JSONArray = JSONArray(),
        val openAlgoOrders: JSONArray = JSONArray(),
        val fetchedAt: Long = System.currentTimeMillis(),
    )

    suspend fun testConnection(context: Context): BinanceDemoApiClient.ApiResult<String> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        val res = client.testConnectivity(creds.apiKey, creds.secretKey)
        if (res.ok) DemoConnectionStore.save(context, "CONNECTED", "Connected to Binance Futures Demo")
        else DemoConnectionStore.save(context, "ERROR", res.error ?: "Connection failed")
        res
    }

    suspend fun fetchSnapshot(context: Context): DemoSnapshot = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext DemoSnapshot(false, error = "Demo API credentials are not configured")
        try {
            val acc = client.getAccount(creds.apiKey, creds.secretKey)
            val pos = client.getPositions(creds.apiKey, creds.secretKey)
            val ord = client.getOpenOrders(creds.apiKey, creds.secretKey)
            val algo = client.getAllOpenAlgoOrders(creds.apiKey, creds.secretKey)

            if (!acc.ok) {
                DemoConnectionStore.save(context, "ERROR", acc.error ?: "Failed to fetch Demo account")
                return@withContext DemoSnapshot(false, error = acc.error ?: "Failed to fetch Demo account")
            }
            val account = acc.data ?: JSONObject()
            val total = account.optString("totalWalletBalance").toDoubleOrNull()
                ?: account.optString("totalCrossWalletBalance").toDoubleOrNull()
            val avail = account.optString("availableBalance").toDoubleOrNull()
            DemoConnectionStore.save(context, "CONNECTED", "Demo data synchronized")
            DemoSnapshot(
                ok = true,
                totalWalletBalance = total,
                availableBalance = avail,
                positions = pos.data ?: JSONArray(),
                openOrders = ord.data ?: JSONArray(),
                openAlgoOrders = algo.data ?: JSONArray(),
            )
        } catch (_: Exception) {
            DemoConnectionStore.save(context, "ERROR", "Unable to sync Binance Futures Demo data")
            DemoSnapshot(false, error = "Unable to sync Binance Futures Demo data")
        }
    }

    suspend fun placeLimitOrder(
        context: Context, symbol: String, side: String, quantity: String, price: String,
        reduceOnly: Boolean = false, positionSide: String? = null, clientOrderId: String? = null
    ): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.placeLimitOrder(creds.apiKey, creds.secretKey, symbol, side, quantity, price, reduceOnly = reduceOnly, positionSide = positionSide, newClientOrderId = clientOrderId)
    }

    suspend fun placeMarketOrder(
        context: Context, symbol: String, side: String, quantity: String,
        reduceOnly: Boolean = false, positionSide: String? = null, clientOrderId: String? = null
    ): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.placeMarketOrder(creds.apiKey, creds.secretKey, symbol, side, quantity, reduceOnly = reduceOnly, positionSide = positionSide, newClientOrderId = clientOrderId)
    }

    suspend fun getOrder(context: Context, symbol: String, orderId: Long? = null, clientOrderId: String? = null): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.getOrder(creds.apiKey, creds.secretKey, symbol, orderId, clientOrderId)
    }

    suspend fun placeAlgoOrder(
        context: Context, symbol: String, side: String, type: String, quantity: String? = null,
        triggerPrice: String? = null, callbackRate: String? = null, reduceOnly: Boolean = false,
        positionSide: String? = null, clientAlgoId: String? = null
    ): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.placeAlgoOrder(creds.apiKey, creds.secretKey, symbol, side, type, quantity, triggerPrice, callbackRate, reduceOnly, positionSide, clientAlgoId)
    }

    suspend fun cancelAlgoOrder(context: Context, symbol: String, algoId: Long? = null, clientAlgoId: String? = null): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.cancelAlgoOrder(creds.apiKey, creds.secretKey, symbol, algoId, clientAlgoId)
    }

    suspend fun getAlgoOrder(context: Context, symbol: String, algoId: Long? = null, clientAlgoId: String? = null): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.getAlgoOrder(creds.apiKey, creds.secretKey, symbol, algoId, clientAlgoId)
    }

    suspend fun cancelOrder(context: Context, symbol: String, orderId: Long? = null, clientOrderId: String? = null): BinanceDemoApiClient.ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val creds = DemoCredentialsStore.load(context)
        if (!creds.hasBoth) return@withContext BinanceDemoApiClient.ApiResult(false, error = "Demo API credentials are not configured")
        client.cancelOrder(creds.apiKey, creds.secretKey, symbol, orderId, clientOrderId)
    }

    companion object {
        fun formatTs(ts: Long): String = try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts)) } catch (_: Exception) { ts.toString() }
    }
}
