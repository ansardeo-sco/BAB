package com.ansardeo.babnext.data.di

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ansardeo.babnext.data.settings.SettingsDatabase
import com.ansardeo.babnext.data.settings.SettingsDao
import com.ansardeo.babnext.data.remote.BinanceApi
import com.ansardeo.babnext.data.remote.PublicMarketInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(PublicMarketInterceptor()).followRedirects(false).followSslRedirects(false).build()

    @Provides
    @Singleton
    fun provideSettingsDatabase(@ApplicationContext context: Context): SettingsDatabase =
        Room.databaseBuilder(context, SettingsDatabase::class.java, "bab-settings.db").build()

    @Provides
    fun provideSettingsDao(database: SettingsDatabase): SettingsDao = database.settings()

    @Provides
    @Singleton
    fun provideBinanceApi(client: OkHttpClient): BinanceApi = Retrofit.Builder()
        .baseUrl("https://fapi.binance.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BinanceApi::class.java)
}
