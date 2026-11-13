# BAB — Binance Android Bot

BAB (Binance Android Bot) is an Android application for managing USDⓈ-M Futures trading workflows with **three independent trading stages**:

1. **Stage 1 — Live Market Prices + Virtual Balance**: uses live public Binance USDⓈ-M Futures market prices with a **virtual 5,000 USDT** wallet (USDⓈ-M). **No real Binance orders. No API keys required.** All trading is locally simulated.
2. **Stage 2 — Binance USDⓈ-M Futures Demo**: connects to the Binance USDⓈ-M Futures **Demo** environment (`https://demo-fapi.binance.com`) using Demo API credentials. Uses the actual Demo wallet and submits actual Demo LIMIT orders. Stage 1 local simulation state is separate.
3. **Stage 3 — Live Trading**: scaffold for real Binance USDⓈ-M Futures integration. **Disabled by default.** This build **does not submit real Binance orders**. Any future live integration must require explicit user authorization, secure credential storage (Android Keystore), minimum permissions (no withdrawals), and thorough testing.

> **Safety**: Stage 1 is strictly local simulation. Stage 2 uses Binance Demo funds only (no real money). Stage 3 is disabled by default and blocked in this build. The app never submits real-money orders.

## Features (current build)

- **5 permanent bottom tabs**: Home, Manual, Positions, Settings, More.
- **Home**: live USDⓈ-M market overview (public endpoints) — Top 10 by volume, gainers, losers, favorites; stale/error indicators and last update time.
- **Manual**: select trading stage, coin, LONG/SHORT, margin, leverage; entry preview (price, TP/SL, estimated qty/notional); explicit confirmation before execution. Settings defaults populate Manual fields; per-entry edits do not change permanent defaults.
- **Positions**: 3 sections — **Positions** (active cycles with entry/avg/current price, qty, margin, leverage, unrealized PNL/ROI, TP/SL), **Open Orders** (active simulated TP/SL/re-entry orders: Basic/TP/SL), **History** (fills and closes). Closed cycles shown in history area.
- **Trading engine (simulated Stage 1)**: TP/SL, one-time averaging (trigger at -60% ROI, recalculates avg/TP/SL/protective orders), opposite-side plan/execution as separate cycle, re-entry **only after trailing stop** (Pipeline → activated Open Order → executed as new cycle), duplicate protection, per-stage data isolation.
- **Settings**: per-coin local settings (directions, margin, leverage, averaging, re-entry, opposite-side parameters). Binance Demo API Configuration shown only in Stage 2 (encrypted on-device via Android Keystore). Trailing/SL/Averaging/Re-entry percentages are auto-selected from the fixed leverage table.
- **Data isolation**: wallets, cycles, entries, orders, pipeline plans, and events are stored per stage in a local Room database.
- **More**: System Information, Current Trading Stage, Connection Status (including Binance Demo API in Stage 2), Wallet Management, Trading Safety, About.

## Repository / delivery

Target repository: `https://github.com/ansardeo-sco/BAB`

This project is designed to be built with **GitHub Actions** (no Android Studio required on the owner's computer).

## Build the APK with GitHub Actions (simple steps)

1. Push this project to the GitHub repository (branch `main`).
2. Open the repository on GitHub → **Actions** tab.
3. Select the workflow **BAB Android CI**.
4. If it didn't run automatically, click **Run workflow**.
5. Wait for the run to finish (green check = success).
6. Open the successful run → **Artifacts** → download **`Binance-Bot-APK-debug`**.
7. Transfer the APK to your Android phone and install it (you may need to allow installs from unknown sources).

## What the CI workflow does

- Checks out the code.
- Sets up JDK 17 and Gradle.
- Verifies required Android project files.
- Compiles the debug APK (`assembleDebug`).
- Runs Android unit tests (`testDebugUnitTest`).
- Uploads the debug APK artifact.
- If a `backend/` Python component is present, runs backend compile/tests in a separate (optional, continue-on-error) job.

## Local development (optional)

Not required for the owner. If a developer has Android SDK + JDK 17:

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

## Security & risk notes

- **Stage 1 never submits real orders** (local simulation only).
- **Stage 2 uses Binance Demo funds only**; Demo credentials stored encrypted on-device (Android Keystore). Never committed to source/GitHub.
- **Stage 3 is disabled by default**; this build blocks Stage 3 execution and does not place real Binance orders.
- API credentials must **never** be hardcoded or committed.
- Always validate symbol/quantity/price/balance/leverage and handle errors/reconciliation before any real trading integration.

## Project structure (high level)

- `app/` — Android app (Kotlin, Jetpack, Room, coroutines).
  - `data/` — stage/store/market models, Binance Demo API client, exchange rules.
  - `paper/db/` — Room entities/DAO/database (stage-isolated storage).
  - `paper/` — trading engine and monitor (Stage 1 local simulation).
  - `ui/` — activities/screens.
- `.github/workflows/` — GitHub Actions CI/APK build.
- `backend/` — optional/legacy Python backend components (kept separate; not used by Android app; rules differ from approved Android strategy).

## Known limitations / notes

- Stage 2 Demo trading currently supports manual LIMIT entry submission with symbol-filter validation and Demo data display. Full automated strategy integration (fill detection, protective orders, averaging, re-entry, opposite-side, trailing, reconciliation) is in progress per the staged implementation plan.
- Stage 3 live Binance order placement is **not implemented/enabled** in this build (safety). The architecture isolates stages and blocks real execution.
- Simulated fee/ROI/PNL use clear assumptions; live trading must use exchange-reported fills/fees.
- Market data depends on public Binance endpoints; the UI shows stale/error/last-update states.
- This is a trading management tool; always validate behavior carefully before any real-money use.

## Disclaimer

This software is provided for development/testing and trading management workflows. Cryptocurrency futures trading involves significant risk. Use Stage 3 (live trading) only after explicit authorization, full security review, and comprehensive testing. The owner/user is responsible for all trading decisions and risk controls.
