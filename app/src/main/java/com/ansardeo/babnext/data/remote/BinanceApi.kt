package com.ansardeo.babnext.data.remote

import retrofit2.http.*

interface BinanceApi {
    // Public market data
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

    // Demo trading (auth required via interceptor)
    @POST("/fapi/v1/order")
    suspend fun newOrder(@QueryMap params: Map<String, Any>): OrderResponseDto

    @DELETE("/fapi/v1/order")
    suspend fun cancelOrder(@Query("symbol") symbol: String, @Query("orderId") orderId: Long? = null, @Query("origClientOrderId") origClientOrderId: String? = null): OrderResponseDto

    @GET("/fapi/v1/openOrders")
    suspend fun openOrders(@Query("symbol") symbol: String? = null): List<OrderResponseDto>

    @GET("/fapi/v1/allOrders")
    suspend fun allOrders(@Query("symbol") symbol: String, @Query("limit") limit: Int = 50): List<OrderResponseDto>

    @GET("/fapi/v2/positionRisk")
    suspend fun positionRisk(@Query("symbol") symbol: String? = null): List<PositionRiskDto>

    @POST("/fapi/v1/algoOrder")
    suspend fun newAlgoOrder(@QueryMap params: Map<String, Any>): AlgoOrderResponseDto

    @DELETE("/fapi/v1/algoOrder")
    suspend fun cancelAlgoOrder(@Query("algoId") algoId: Long? = null, @Query("clientAlgoId") clientAlgoId: String? = null): AlgoOrderResponseDto

    @GET("/fapi/v1/openAlgoOrders")
    suspend fun openAlgoOrders(@Query("symbol") symbol: String? = null): List<AlgoOrderResponseDto>

    @GET("/fapi/v1/leverage")
    suspend fun changeLeverage(@Query("symbol") symbol: String, @Query("leverage") leverage: Int): LeverageResponseDto

    @POST("/fapi/v1/marginType")
    suspend fun changeMarginType(@Query("symbol") symbol: String, @Query("marginType") marginType: String): GenericResponseDto

    @POST("/fapi/v1/positionSide/dual")
    suspend fun changePositionMode(@Query("dualSidePosition") dualSidePosition: String): GenericResponseDto

    @GET("/fapi/v1/positionSide/dual")
    suspend fun getPositionMode(): PositionModeDto

    @POST("/fapi/v1/listenKey")
    suspend fun startListenKey(): ListenKeyDto

    @PUT("/fapi/v1/listenKey")
    suspend fun keepAliveListenKey()

    @DELETE("/fapi/v1/listenKey")
    suspend fun closeListenKey()
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

data class OrderResponseDto(
    val orderId: Long? = null,
    val clientOrderId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val type: String? = null,
    val origQty: String? = null,
    val executedQty: String? = null,
    val price: String? = null,
    val avgPrice: String? = null,
    val status: String? = null,
    val positionSide: String? = null
)

data class AlgoOrderResponseDto(
    val algoId: Long? = null,
    val clientAlgoId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val positionSide: String? = null,
    val type: String? = null,
    val quantity: String? = null,
    val activatePrice: String? = null,
    val callbackRate: String? = null,
    val algoStatus: String? = null
)

data class PositionRiskDto(
    val symbol: String,
    val positionAmt: String,
    val entryPrice: String,
    val markPrice: String,
    val unRealizedProfit: String,
    val leverage: String,
    val positionSide: String
)

data class LeverageResponseDto(val leverage: Int, val maxNotionalValue: String? = null)
data class GenericResponseDto(val code: Int? = null, val msg: String? = null)
data class PositionModeDto(val dualSidePosition: String)
data class ListenKeyDto(val listenKey: String)