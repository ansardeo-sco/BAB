package com.ansardeo.babnext.data.remote

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Reject all requests outside the Stage 1 public market-data surface. */
object PublicMarketPolicy {
    private val paths = setOf("/fapi/v1/ticker/24hr", "/fapi/v1/klines", "/fapi/v1/exchangeInfo")
    private val allowedQuery = setOf("symbol", "interval", "limit")
    private val allowedHeaders = setOf("accept", "accept-encoding", "user-agent")

    fun permits(request: Request): Boolean =
        request.method == "GET" && request.url.scheme == "https" &&
            request.url.host == "fapi.binance.com" && request.url.port == 443 &&
            request.url.encodedPath in paths &&
            request.url.queryParameterNames.all { it in allowedQuery } &&
            request.headers.names().all { it.lowercase() in allowedHeaders }
}

class PublicMarketInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!PublicMarketPolicy.permits(request)) throw IOException("Request rejected by the Stage 1 public market policy")
        return chain.proceed(request)
    }
}
