import os

# Safe defaults for imports (no live trading, no real credentials).
os.environ.setdefault("BINANCE_USE_TESTNET", "true")
os.environ.setdefault("LIVE_TRADING_ENABLED", "false")
os.environ.setdefault("BINANCE_API_KEY", "")
os.environ.setdefault("BINANCE_API_SECRET", "")
os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")
os.environ.setdefault("BOT_ADMIN_TOKEN", "")
