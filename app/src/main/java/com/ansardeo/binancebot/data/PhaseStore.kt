package com.ansardeo.binancebot.data

import android.content.Context

enum class TradingPhase(val title: String) {
    PHASE1_PAPER("Phase 1 - Paper Trading"),
    PHASE2_DEMO("Phase 2 - Demo / Testnet"),
    PHASE3_LIVE("Phase 3 - Live Trading"),
}

object PhaseStore {
    private const val PREFS = "app_phase_prefs"
    private const val KEY = "selected_phase"

    fun current(context: Context): TradingPhase = TradingPhase.PHASE1_PAPER // locked to Phase 1 for now

    fun isLocked(phase: TradingPhase): Boolean = phase != TradingPhase.PHASE1_PAPER

    fun lockReason(phase: TradingPhase): String = when (phase) {
        TradingPhase.PHASE1_PAPER -> "Active: live public prices + virtual $100 (no API keys, no server)"
        TradingPhase.PHASE2_DEMO -> "Locked: unlock after Phase 1 validation (requires backend/VPS + Binance Testnet API)"
        TradingPhase.PHASE3_LIVE -> "Locked: unlock after Demo validation (real funds; remains disabled by default)"
    }
}
