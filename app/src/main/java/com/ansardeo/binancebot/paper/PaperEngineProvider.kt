package com.ansardeo.binancebot.paper

import android.content.Context
import com.ansardeo.binancebot.data.TradingStage
import com.ansardeo.binancebot.paper.db.PaperDatabase

object PaperEngineProvider {
    @Volatile private var stage1: PaperEngine? = null
    @Volatile private var stage2: PaperEngine? = null
    @Volatile private var stage3: PaperEngine? = null

    fun get(context: Context, stage: TradingStage): PaperEngine {
        val app = context.applicationContext
        val db = PaperDatabase.get(app)
        return when (stage) {
            TradingStage.STAGE_1 -> stage1 ?: synchronized(this) { stage1 ?: PaperEngine(db, TradingStage.STAGE_1).also { stage1 = it } }
            TradingStage.STAGE_2 -> stage2 ?: synchronized(this) { stage2 ?: PaperEngine(db, TradingStage.STAGE_2).also { stage2 = it } }
            TradingStage.STAGE_3 -> stage3 ?: synchronized(this) { stage3 ?: PaperEngine(db, TradingStage.STAGE_3).also { stage3 = it } }
        }
    }

    fun resetInstances() {
        synchronized(this) { stage1 = null; stage2 = null; stage3 = null }
    }
}
