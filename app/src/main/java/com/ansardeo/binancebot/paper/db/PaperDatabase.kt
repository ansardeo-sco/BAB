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
        DemoOrderEntity::class,
    ],
    version = 4,
    exportSchema = false
)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun dao(): PaperDao
    abstract fun demoOrderDao(): DemoOrderDao

    companion object {
        @Volatile private var INSTANCE: PaperDatabase? = null

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS demo_orders (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "stage TEXT NOT NULL, " +
                        "cycleId INTEGER, " +
                        "entryId INTEGER, " +
                        "symbol TEXT NOT NULL, " +
                        "direction TEXT NOT NULL, " +
                        "side TEXT NOT NULL, " +
                        "orderType TEXT NOT NULL, " +
                        "triggerPrice REAL NOT NULL, " +
                        "limitPrice REAL, " +
                        "quantity REAL NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "clientOrderId TEXT NOT NULL, " +
                        "exchangeOrderId TEXT, " +
                        "filledPrice REAL, " +
                        "filledQuantity REAL NOT NULL, " +
                        "reduceOnly INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "filledAt INTEGER, " +
                        "closedAt INTEGER, " +
                        "lastError TEXT" +
                        ")"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_demo_orders_clientOrderId ON demo_orders(clientOrderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_demo_orders_exchangeOrderId ON demo_orders(exchangeOrderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_demo_orders_stage ON demo_orders(stage)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_demo_orders_status ON demo_orders(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_demo_orders_symbol ON demo_orders(symbol)")
            }
        }

        fun get(context: Context): PaperDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PaperDatabase::class.java,
                "bab_trading.db"
            )
                .addMigrations(MIGRATION_3_4)
                // Safe migrations required for schema changes; do not destroy user trading records.
                .build().also { INSTANCE = it }
        }
    }
}
