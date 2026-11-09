import time

from market_data_service import BinanceFuturesMarketDataService


def test_apply_ticker_array_parses_ws_fields_filters_non_tradable_and_dedupes():
    svc = BinanceFuturesMarketDataService()
    svc._tradable_usdt_perps = {"BTCUSDT", "ETHUSDT"}
    svc._tickers["BTCUSDT"] = {
        "symbol": "BTCUSDT",
        "lastPrice": 1.0,
        "priceChangePercent": 0.0,
        "quoteVolume": 1.0,
    }

    items = [
        # WS-style fields for tradable symbol (updates existing)
        {"s": "BTCUSDT", "c": "50000.5", "P": "2.5", "q": "1000000"},
        # Tradable symbol
        {"symbol": "ETHUSDT", "lastPrice": "3000", "priceChangePercent": "-1.2", "quoteVolume": "500000"},
        # Non-tradable / not in tradable set
        {"s": "XRPUSDT", "c": "1", "P": "5", "q": "10"},
        # Invalid/missing price
        {"s": "ETHUSDT", "c": None, "P": "9", "q": "9"},
        # Duplicate symbol in same payload (last valid wins)
        {"s": "BTCUSDT", "c": "51000", "P": "3.0", "q": "2000000"},
    ]

    svc._apply_ticker_array(items)

    assert set(svc._tickers.keys()) == {"BTCUSDT", "ETHUSDT"}
    assert svc._tickers["BTCUSDT"]["lastPrice"] == 51000.0
    assert svc._tickers["BTCUSDT"]["priceChangePercent"] == 3.0
    assert svc._tickers["BTCUSDT"]["quoteVolume"] == 2000000.0
    assert svc._tickers["ETHUSDT"]["lastPrice"] == 3000.0
    assert svc._last_update_ts is not None


def test_apply_ticker_array_handles_missing_fields_with_defaults():
    svc = BinanceFuturesMarketDataService()
    svc._tradable_usdt_perps = {"BTCUSDT"}

    svc._apply_ticker_array([{"s": "BTCUSDT", "c": "100"}])

    assert svc._tickers["BTCUSDT"]["priceChangePercent"] == 0.0
    assert svc._tickers["BTCUSDT"]["quoteVolume"] == 0.0


def test_overview_ranking_and_featured():
    svc = BinanceFuturesMarketDataService()
    svc._status = "Live"
    svc._last_update_ts = time.time()
    svc._tickers = {
        "BTCUSDT": {"symbol": "BTCUSDT", "lastPrice": 50000, "priceChangePercent": 1.0, "quoteVolume": 999},
        "DOGEUSDT": {"symbol": "DOGEUSDT", "lastPrice": 0.1, "priceChangePercent": -2.0, "quoteVolume": 888},
        "AUSDT": {"symbol": "AUSDT", "lastPrice": 1, "priceChangePercent": 5.0, "quoteVolume": 300},
        "BUSDT": {"symbol": "BUSDT", "lastPrice": 1, "priceChangePercent": 3.0, "quoteVolume": 500},
        "CUSDT": {"symbol": "CUSDT", "lastPrice": 1, "priceChangePercent": -4.0, "quoteVolume": 100},
        "DUSDT": {"symbol": "DUSDT", "lastPrice": 1, "priceChangePercent": -1.0, "quoteVolume": 700},
    }

    overview = svc.get_overview()

    assert overview["status"] == "Live"
    assert overview["featured"]["BTCUSDT"]["symbol"] == "BTCUSDT"
    assert overview["featured"]["DOGEUSDT"]["symbol"] == "DOGEUSDT"
    assert [x["symbol"] for x in overview["volume_leaders"][:3]] == ["BTCUSDT", "DOGEUSDT", "DUSDT"]
    assert [x["symbol"] for x in overview["gainers"]] == ["AUSDT", "BUSDT", "BTCUSDT"]
    assert [x["symbol"] for x in overview["losers"]] == ["CUSDT", "DOGEUSDT", "DUSDT"]


def test_overview_stale_and_unavailable_status():
    svc = BinanceFuturesMarketDataService()
    svc._running = True
    svc._status = "Live"
    svc._last_update_ts = time.time() - 999
    svc._tickers = {"BTCUSDT": {"symbol": "BTCUSDT", "lastPrice": 1, "priceChangePercent": 0, "quoteVolume": 1}}

    stale = svc.get_overview()
    assert stale["status"] == "Stale"
    assert stale["stale"] is True
    assert stale["data_available"] is True

    svc2 = BinanceFuturesMarketDataService()
    svc2._status = "Unavailable"
    svc2._tickers = {}
    unavail = svc2.get_overview()
    assert unavail["status"] == "Unavailable"
    assert unavail["data_available"] is False
