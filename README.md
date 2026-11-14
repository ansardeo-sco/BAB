<!-- coderabbit-ai-generated:v1 -->

<!-- coderabbit-ai-generated-section:v1:877961ce2088522d2e6330b1cfa8b3afd29312271555120a33ce5b5a814960cd -->

# BAB-Next

BAB-Next is an Android application being converted to Stage 1 virtual trading. Stage 2 Demo and Stage 3 Live trading require separate approval.

## Current status

The public market client is limited to read-only Binance USDⓈ-M Futures market data. The former Demo credential and order submission paths have been removed. Virtual order submission and execution are not yet available: the stop-loss formula, averaging trigger, and local fill rules still need to be defined. Dashboard settings are stored locally. Do not treat this incomplete build as a trading simulator.

## Build

Use JDK 17, Gradle 8.7, and Android SDK 34. With those installed, run:

```sh
gradle --no-daemon testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. GitHub Actions also runs the build and unit tests and uploads the APK and test reports.


<!-- /coderabbit-ai-generated:v1 -->