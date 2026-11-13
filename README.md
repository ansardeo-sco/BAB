# BAB-Next — Binance Android Bot

Android application for managing Binance USDⓈ-M Futures trading in **Demo mode first**.

## Phase 1 — Binance Demo Trading (implemented)
- Live market data (public endpoints, no credentials needed).
- Secure Demo API credential entry (Android Keystore).
- Manual LIMIT entry orders (Long/Short).
- Exchange-side trailing stop (activation 33% ROI, callback 3% ROI).
- Basic positions/orders/history tabs.
- Control Dashboard (basic foundation).
- GitHub Actions CI with downloadable debug APK.

## Phase 2 — Binance Real Trading (not implemented)
Disabled. Will be planned only after Demo completion and explicit approval.

## Build
Push to GitHub; Actions builds the debug APK. Download from Actions → Artifacts → BAB-Next-APK-debug.

## Configure Demo Credentials
Open app → Settings → enter Demo API Key/Secret. Stored in Keystore. Never in source/GitHub.