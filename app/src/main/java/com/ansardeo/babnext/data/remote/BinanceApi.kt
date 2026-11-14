package com.ansardeo.babnext.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/** The Stage 1 network surface contains public, read-only market data only. */
interface BinanceApi {
    @GET("/fapi/v1/ticker/24hr")
    suspend fun ticker24hr(): List<Ticker24hDto>

    @GET("/fapi/v1/klines")
    suspend fun klines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 100
    ): List<List<Any>>

    @GET("/fapi/v1/exchangeInfo")
    suspend fun exchangeInfo(): ExchangeInfoDto
}

data class Ticker24hDto(
    val symbol: String,
    val lastPrice: String,
    val priceChangePercent: String,
    val quoteVolume: String,
    val highPrice: String,
    val lowPrice: String
)

data class ExchangeInfoDto(val symbols: List<SymbolInfoDto> = emptyList())
data class SymbolInfoDto(val symbol: String, val status: String)
