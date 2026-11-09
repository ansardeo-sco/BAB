package com.ansardeo.binancebot.paper.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PaperWalletEntity::class,
        PaperCycleEntity::class,
        PaperEntryEntity::class,
        PaperOrderEntity::class,
        PaperHistoryCloseEntity::class,
        PaperEventEntity::class,
        PaperReentryPlanEntity::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun dao(): PaperDao

    companion object {
        @Volatile private var INSTANCE: PaperDatabase? = null

        fun get(context: Context): PaperDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PaperDatabase::class.java,
                "bab_trading.db"
            ).fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
