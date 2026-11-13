package com.ansardeo.binancebot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Binance USDⓈ-M Futures Demo REST client (Stage 2 only).
 * Base: https://demo-fapi.binance.com (never live endpoints).
 */
class BinanceDemoApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val BASE_URL = "https://demo-fapi.binance.com"
        private const val RECV_WINDOW = "5000"
    }

    data class ApiResult<T>(val ok: Boolean, val data: T? = null, val error: String? = null, val code: Int? = null)

    suspend fun testConnectivity(apiKey: String, secretKey: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val res = signedGet("/fapi/v2/account", apiKey, secretKey)
            if (res.optBoolean("__ok", false)) ApiResult(true, "Connected to Binance Futures Demo")
            else ApiResult(false, error = res.optString("__error", "Connection failed"), code = res.optInt("__code", -1).takeIf { it != -1 })
        } catch (_: Exception) {
            ApiResult(false, error = "Unable to connect to Binance Futures Demo")
        }
    }

    suspend fun getAccount(apiKey: String, secretKey: String): ApiResult<JSONObject> = signedResult("/fapi/v2/account", apiKey, secretKey)
    suspend fun getPositions(apiKey: String, secretKey: String): ApiResult<JSONArray> = signedArrayResult("/fapi/v2/positionRisk", apiKey, secretKey)
    suspend fun getOpenOrders(apiKey: String, secretKey: String, symbol: String? = null): ApiResult<JSONArray> =
        signedArrayResult("/fapi/v1/openOrders", apiKey, secretKey, if (symbol != null) mapOf("symbol" to symbol.uppercase()) else emptyMap())
    suspend fun getAllOpenAlgoOrders(apiKey: String, secretKey: String, symbol: String? = null): ApiResult<JSONArray> =
        signedArrayResult("/fapi/v1/openAlgoOrders", apiKey, secretKey, if (symbol != null) mapOf("symbol" to symbol.uppercase()) else emptyMap())

    /**
     * Place an algorithmic (conditional) order on Binance USDⓈ-M Futures Demo.
     * Supported types include STOP_MARKET, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET.
     * Used for per-entry Stop Loss and Take Profit / trailing protection.
     */
    suspend fun placeAlgoOrder(
        apiKey: String, secretKey: String,
        symbol: String, side: String, type: String, quantity: String? = null,
        triggerPrice: String? = null, callbackRate: String? = null,
        reduceOnly: Boolean = false, positionSide: String? = null,
        newClientOrderId: String? = null, workingType: String = "MARK_PRICE",
        priceProtect: Boolean = true
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(),
            "side" to side.uppercase(),
            "type" to type.uppercase(),
            "workingType" to workingType,
            "priceProtect" to priceProtect.toString()
        )
        if (!quantity.isNullOrBlank()) params["quantity"] = quantity
        if (!triggerPrice.isNullOrBlank()) params["triggerPrice"] = triggerPrice
        if (!callbackRate.isNullOrBlank()) params["callbackRate"] = callbackRate
        params["reduceOnly"] = reduceOnly.toString()
        if (!positionSide.isNullOrBlank()) params["positionSide"] = positionSide
        if (!newClientOrderId.isNullOrBlank()) params["newClientOrderId"] = newClientOrderId
        signedPostResult("/fapi/v1/algoOrder", apiKey, secretKey, params)
    }

    suspend fun cancelAlgoOrder(apiKey: String, secretKey: String, symbol: String, algoId: Long? = null, clientAlgoId: String? = null): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf("symbol" to symbol.uppercase())
        if (algoId != null) params["algoId"] = algoId.toString()
        if (!clientAlgoId.isNullOrBlank()) params["clientAlgoId"] = clientAlgoId
        signedDeleteResult("/fapi/v1/algoOrder", apiKey, secretKey, params)
    }

    suspend fun getAlgoOrder(apiKey: String, secretKey: String, symbol: String, algoId: Long? = null, clientAlgoId: String? = null): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf("symbol" to symbol.uppercase())
        if (algoId != null) params["algoId"] = algoId.toString()
        if (!clientAlgoId.isNullOrBlank()) params["clientAlgoId"] = clientAlgoId
        signedResult("/fapi/v1/algoOrder", apiKey, secretKey, params)
    }

    suspend fun getServerTime(): Long? = withContext(Dispatchers.IO) {
        try {
            val text = getText("https://demo-fapi.binance.com/fapi/v1/time")
            JSONObject(text).optLong("serverTime").takeIf { it > 0 }
        } catch (_: Exception) { null }
    }

    suspend fun placeLimitOrder(
        apiKey: String, secretKey: String,
        symbol: String, side: String, quantity: String, price: String,
        timeInForce: String = "GTC", reduceOnly: Boolean = false,
        positionSide: String? = null, newClientOrderId: String? = null
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(),
            "side" to side.uppercase(),
            "type" to "LIMIT",
            "timeInForce" to timeInForce,
            "quantity" to quantity,
            "price" to price,
            "reduceOnly" to reduceOnly.toString()
        )
        if (!positionSide.isNullOrBlank()) params["positionSide"] = positionSide
        if (!newClientOrderId.isNullOrBlank()) params["newClientOrderId"] = newClientOrderId
        signedPostResult("/fapi/v1/order", apiKey, secretKey, params)
    }

    suspend fun cancelOrder(apiKey: String, secretKey: String, symbol: String, orderId: Long? = null, origClientOrderId: String? = null): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf("symbol" to symbol.uppercase())
        if (orderId != null) params["orderId"] = orderId.toString()
        if (!origClientOrderId.isNullOrBlank()) params["origClientOrderId"] = origClientOrderId
        signedDeleteResult("/fapi/v1/order", apiKey, secretKey, params)
    }

    suspend fun getOrder(apiKey: String, secretKey: String, symbol: String, orderId: Long? = null, origClientOrderId: String? = null): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val params = linkedMapOf("symbol" to symbol.uppercase())
        if (orderId != null) params["orderId"] = orderId.toString()
        if (!origClientOrderId.isNullOrBlank()) params["origClientOrderId"] = origClientOrderId
        signedResult("/fapi/v1/order", apiKey, secretKey, params)
    }

    private suspend fun signedResult(path: String, apiKey: String, secretKey: String, extra: Map<String, String> = emptyMap()): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val obj = signedGet(path, apiKey, secretKey, extra)
        if (obj.optBoolean("__ok", false)) ApiResult(true, obj) else ApiResult(false, error = safeError(obj), code = obj.optInt("__code", -1).takeIf { it != -1 })
    }

    private suspend fun signedArrayResult(path: String, apiKey: String, secretKey: String, extra: Map<String, String> = emptyMap()): ApiResult<JSONArray> = withContext(Dispatchers.IO) {
        val text = signedRequestText("GET", path, apiKey, secretKey, extra)
        if (text.startsWith("__ERR__:")) {
            val parts = text.removePrefix("__ERR__:").split("|", limit = 2)
            ApiResult(false, error = parts.getOrNull(1)?.ifBlank { "Request failed" } ?: "Request failed", code = parts.getOrNull(0)?.toIntOrNull())
        } else try { ApiResult(true, JSONArray(text)) } catch (_: Exception) {
            try {
                val o = JSONObject(text)
                ApiResult(false, error = o.optString("msg", "Unexpected response"), code = o.optInt("code", -1).takeIf { it != -1 })
            } catch (_: Exception) { ApiResult(false, error = "Unexpected response") }
        }
    }

    private suspend fun signedPostResult(path: String, apiKey: String, secretKey: String, params: Map<String, String>): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val text = signedRequestText("POST", path, apiKey, secretKey, params)
        parseObjectResult(text)
    }

    private suspend fun signedDeleteResult(path: String, apiKey: String, secretKey: String, params: Map<String, String>): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val text = signedRequestText("DELETE", path, apiKey, secretKey, params)
        parseObjectResult(text)
    }

    private fun parseObjectResult(text: String): ApiResult<JSONObject> {
        if (text.startsWith("__ERR__:")) {
            val parts = text.removePrefix("__ERR__:").split("|", limit = 2)
            return ApiResult(false, error = parts.getOrNull(1)?.ifBlank { "Request failed" } ?: "Request failed", code = parts.getOrNull(0)?.toIntOrNull())
        }
        return try { ApiResult(true, JSONObject(text)) } catch (_: Exception) {
            try {
                val o = JSONObject(text)
                ApiResult(false, error = o.optString("msg", "Unexpected response"), code = o.optInt("code", -1).takeIf { it != -1 })
            } catch (_: Exception) { ApiResult(false, error = "Unexpected response") }
        }
    }

    private fun signedGet(path: String, apiKey: String, secretKey: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val text = signedRequestText("GET", path, apiKey, secretKey, extra)
        if (text.startsWith("__ERR__:")) {
            val parts = text.removePrefix("__ERR__:").split("|", limit = 2)
            return JSONObject().put("__ok", false).put("__code", parts.getOrNull(0)?.toIntOrNull() ?: -1).put("__error", parts.getOrNull(1) ?: "Request failed")
        }
        return try { JSONObject(text).put("__ok", true) } catch (_: Exception) {
            JSONObject().put("__ok", false).put("__error", "Unexpected response")
        }
    }

    private fun signedRequestText(method: String, path: String, apiKey: String, secretKey: String, params: Map<String, String>): String {
        if (apiKey.isBlank() || secretKey.isBlank()) return "__ERR__:-1|Missing Demo API credentials"
        val ts = System.currentTimeMillis().toString()
        val all = linkedMapOf<String, String>()
        params.forEach { (k, v) -> all[k] = v }
        all["timestamp"] = ts
        all["recvWindow"] = RECV_WINDOW
        val query = all.entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }
        val sig = hmacSha256(secretKey, query)
        val fullQuery = "$query&signature=$sig"
        val url = "$BASE_URL$path?$fullQuery"
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("X-MBX-APIKEY", apiKey)
        val req = when (method.uppercase()) {
            "POST" -> reqBuilder.post(FormBody.Builder().build()).build()
            "DELETE" -> reqBuilder.delete().build()
            else -> reqBuilder.get().build()
        }
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(body).optString("msg", body.take(120)) } catch (_: Exception) { body.take(120).ifBlank { "HTTP ${resp.code}" } }
                val code = try { JSONObject(body).optInt("code", resp.code) } catch (_: Exception) { resp.code }
                return "__ERR__:$code|${msg.ifBlank { "Request failed" }}"
            }
            return body.ifBlank { "{}" }
        }
    }

    private fun safeError(obj: JSONObject): String = obj.optString("__error", obj.optString("msg", "Request failed")).ifBlank { "Request failed" }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
