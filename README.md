# BAB – Binance Android Bot

BAB (Binance Android Bot) is an Android application for managing USDⓈ-M Futures trading workflows with **three independent trading stages**:

1. **Stage 1 – Live Market Prices + Virtual Balance**: uses live public Binance USDⓈ-M Futures market prices with a **virtual 100 USDT** wallet. **No real Binance orders. No API keys required.**
2. **Stage 2 – Demo Simulation (current implementation)**: **LOCAL SIMULATION ONLY** in-app (virtual wallet, simulated positions/orders/pipeline/history) using live public market data. **Binance Futures Demo API is not connected; no real Binance Demo orders are submitted.** No real funds. (Intended future: Binance USDⓈ-M Futures Demo via API with same shared strategy.)
3. **Stage 3 – Live Trading**: scaffold for real Binance USDⓈ-M Futures integration. **Disabled by default.** This build **does not submit real Binance orders**. Any future live integration must require explicit user authorization, secure credential storage (Android Keystore), minimum permissions (no withdrawals), and thorough testing.

> **Safety**: Stage 1 and the current Stage 2 implementation are strictly local simulations. The app never submits authenticated Binance orders from Stage 1 or Stage 2. Stage 3 is disabled by default and blocked in this build.

## Features (current build)

- **5 permanent bottom tabs**: Home, Manual, Positions, Settings, More.
- **Home**: live USDⓈ-M market overview (public endpoints) — Top 10 by volume, gainers, losers, favorites; stale/error indicators and last update time.
- **Manual**: select trading stage, coin (favorites), LONG/SHORT, margin, leverage; entry preview (price, TP/SL, estimated qty/notional/fee); explicit confirmation before execution.
- **Positions**: 3 sections — **Positions** (active cycles with entry/avg/current price, qty, margin, leverage, unrealized PNL/ROI, TP/SL), **Open Orders** (active simulated TP/SL/re-entry orders), **Pipeline** (planned averaging/opposite-side/re-entry with status). Closed cycles shown in history area.
- **Trading engine (simulated stages)**: TP/SL, one-time averaging (warning/trigger, recalculates avg/TP/SL/protective orders), opposite-side plan/execution as separate cycle, re-entry **only after Take Profit** (Pipeline → activated Open Order → executed as new cycle), duplicate protection, per-stage data isolation.
- **Settings**: per-coin local settings (directions, margin, leverage, TP/SL, averaging, re-entry, opposite-side parameters).
- **Data isolation**: wallets, cycles, entries, orders, pipeline plans, and events are stored per stage in a local Room database.

## Repository / delivery

Target repository: `https://github.com/ansardeo-sco/BAB`

This project is designed to be built with **GitHub Actions** (no Android Studio required on the owner’s computer).

## Build the APK with GitHub Actions (simple steps)

1. Upload/push this entire project to the GitHub repository (branch `main` or any branch).
2. Open the repository on GitHub → **Actions** tab.
3. Select the workflow **“BAB Android CI”**.
4. If it didn’t run automatically, click **Run workflow**.
5. Wait for the run to finish (green check = success).
6. Open the successful run → **Artifacts** → download **`BAB-APK-debug`**.
7. Transfer the APK to your Android phone and install it (you may need to allow installs from unknown sources).

## What the CI workflow does

- Checks out the code.
- Sets up JDK 17 and Gradle.
- Verifies required Android project files.
- Compiles the debug APK (`assembleDebug`).
- Runs Android unit tests (`testDebugUnitTest`).
- Uploads the debug APK artifact.
- If a `backend/` Python component is present, runs backend compile/tests in a separate job.

## Local development (optional)

Not required for the owner. If a developer has Android SDK + JDK 17:

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

## Security & risk notes

- **Stage 1/2 never submit real orders.**
- **Stage 3 is disabled by default**; this build blocks Stage 3 execution and does not place real Binance orders.
- API credentials must **never** be hardcoded or committed. Future Stage 3 work must use Android Keystore-backed secure storage and minimum API permissions (no withdrawals).
- Always validate symbol/quantity/price/balance/leverage and handle errors/reconciliation before any real trading integration.

## Project structure (high level)

- `app/` — Android app (Kotlin, Jetpack, Room, coroutines).
  - `data/` — stage/store/market models.
  - `paper/db/` — Room entities/DAO/database (stage-isolated storage).
  - `paper/` — trading engine and monitor (simulated stages).
  - `ui/` — activities/screens.
- `.github/workflows/` — GitHub Actions CI/APK build.
- `backend/` — optional/legacy Python backend components (kept separate from Android code).

## Known limitations / notes

- Stage 3 live Binance order placement is **not implemented/enabled** in this build (safety). The architecture isolates stages and blocks real execution.
- Simulated fee/ROI/PNL use clear assumptions; live trading must use exchange-reported fills/fees.
- Market data depends on public Binance endpoints; the UI shows stale/error/last-update states.
- This is a trading management tool; always validate behavior carefully before any real-money use.

## Disclaimer

This software is provided for development/testing and trading management workflows. Cryptocurrency futures trading involves significant risk. Use Stage 3 (live trading) only after explicit authorization, full security review, and comprehensive testing. The owner/user is responsible for all trading decisions and risk controls.
