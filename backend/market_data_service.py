import asyncio
import json
import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set

import requests
import websockets

logger = logging.getLogger(__name__)

FAPI_BASE = "https://fapi.binance.com"
WS_URL = "wss://fstream.binance.com/stream?streams=!ticker@arr"
SNAPSHOT_URL = f"{FAPI_BASE}/fapi/v1/ticker/24hr"
EXCHANGE_INFO_URL = f"{FAPI_BASE}/fapi/v1/exchangeInfo"

STALE_AFTER_SECONDS = 20
RECONNECT_MIN_SECONDS = 2
RECONNECT_MAX_SECONDS = 30
EXCHANGE_INFO_REFRESH_SECONDS = 300
SNAPSHOT_REFRESH_SECONDS = 60


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _to_float(value: Any) -> Optional[float]:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


class BinanceFuturesMarketDataService:
    """
    Read-only Binance USDⓈ-M Futures market data (no API keys, no trading).
    Uses 24h ticker WebSocket for continuous updates and REST snapshot for
    initial/recovery data.
    """

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._task: Optional[asyncio.Task] = None
        self._running = False
        self._ws = None
        self._status = "Disconnected"  # Live, Connecting, Disconnected, Unavailable
        self._last_update_ts: Optional[float] = None
        self._last_error: Optional[str] = None
        self._tradable_usdt_perps: Set[str] = set()
        self._exchange_info_ts: Optional[float] = None
        self._tickers: Dict[str, Dict[str, Any]] = {}

    @property
    def is_running(self) -> bool:
        return self._running

    async def start(self) -> None:
        async with self._lock:
            if self._running:
                return
            self._running = True
            self._status = "Connecting"
            self._task = asyncio.create_task(self._run(), name="market-data-ws")
            logger.info("Market data service started.")

    async def stop(self) -> None:
        async with self._lock:
            self._running = False
            self._status = "Disconnected"
            task = self._task
            self._task = None
            ws = self._ws
            self._ws = None
        if ws is not None:
            try:
                await ws.close()
            except Exception:
                pass
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            except Exception:
                logger.exception("Market data task stop error.")
        logger.info("Market data service stopped.")

    def _is_stale(self) -> bool:
        if not self._last_update_ts:
            return True
        return (time.time() - self._last_update_ts) > STALE_AFTER_SECONDS

    def _effective_status(self) -> str:
        if not self._running and self._status == "Disconnected":
            return "Disconnected"
        if self._status == "Unavailable" and not self._tickers:
            return "Unavailable"
        if self._status in ("Connecting", "Disconnected") and self._tickers and self._is_stale():
            return "Stale"
        if self._tickers and self._is_stale():
            return "Stale"
        if self._status == "Live" and self._is_stale():
            return "Stale"
        return self._status

    async def _refresh_exchange_info(self, force: bool = False) -> None:
        now = time.time()
        if (
            not force
            and self._exchange_info_ts
            and (now - self._exchange_info_ts) < EXCHANGE_INFO_REFRESH_SECONDS
            and self._tradable_usdt_perps
        ):
            return
        try:
            data = await asyncio.to_thread(self._fetch_exchange_info_sync)
            symbols = data.get("symbols") or []
            tradable: Set[str] = set()
            for item in symbols:
                if not isinstance(item, dict):
                    continue
                if item.get("contractType") != "PERPETUAL":
                    continue
                if item.get("quoteAsset") != "USDT":
                    continue
                if item.get("status") != "TRADING":
                    continue
                symbol = item.get("symbol")
                if isinstance(symbol, str) and symbol:
                    tradable.add(symbol.upper())
            if tradable:
                self._tradable_usdt_perps = tradable
                self._exchange_info_ts = now
        except Exception as e:
            logger.warning("Market exchangeInfo refresh failed: %s", e)
            self._last_error = str(e)

    def _fetch_exchange_info_sync(self) -> Dict[str, Any]:
        resp = requests.get(EXCHANGE_INFO_URL, timeout=15)
        resp.raise_for_status()
        return resp.json()

    async def _refresh_snapshot(self) -> None:
        try:
            data = await asyncio.to_thread(self._fetch_snapshot_sync)
            if isinstance(data, list):
                self._apply_ticker_array(data)
                if self._tickers:
                    self._status = "Live" if self._running else self._status
        except Exception as e:
            logger.warning("Market snapshot refresh failed: %s", e)
            self._last_error = str(e)
            if not self._tickers:
                self._status = "Unavailable"

    def _fetch_snapshot_sync(self) -> Any:
        resp = requests.get(SNAPSHOT_URL, timeout=15)
        resp.raise_for_status()
        return resp.json()

    def _apply_ticker_array(self, items: List[Any]) -> None:
        tradable = self._tradable_usdt_perps
        updated = False
        for item in items:
            if not isinstance(item, dict):
                continue
            # REST: symbol,lastPrice,priceChangePercent,quoteVolume
            # WS !ticker@arr: s,c,P,q
            symbol = item.get("symbol") or item.get("s")
            if not isinstance(symbol, str) or not symbol:
                continue
            symbol = symbol.upper()
            if tradable and symbol not in tradable:
                continue
            if not tradable and not symbol.endswith("USDT"):
                continue

            last = _to_float(item.get("lastPrice", item.get("c")))
            pct = _to_float(item.get("priceChangePercent", item.get("P")))
            qvol = _to_float(item.get("quoteVolume", item.get("q")))
            if last is None:
                continue
            self._tickers[symbol] = {
                "symbol": symbol,
                "lastPrice": last,
                "priceChangePercent": pct if pct is not None else 0.0,
                "quoteVolume": qvol if qvol is not None else 0.0,
            }
            updated = True
        if updated:
            self._last_update_ts = time.time()
            self._last_error = None

    async def _run(self) -> None:
        backoff = RECONNECT_MIN_SECONDS
        last_snapshot = 0.0
        while self._running:
            try:
                await self._refresh_exchange_info(force=not self._tradable_usdt_perps)
                await self._refresh_snapshot()
                last_snapshot = time.time()
                if self._tickers:
                    self._status = "Live"
                self._status = "Connecting" if not self._tickers else "Live"

                async with websockets.connect(
                    WS_URL,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=5,
                    max_size=10 * 1024 * 1024,
                ) as ws:
                    self._ws = ws
                    backoff = RECONNECT_MIN_SECONDS
                    if self._tickers:
                        self._status = "Live"
                    else:
                        self._status = "Connecting"

                    async for message in ws:
                        if not self._running:
                            break
                        now = time.time()
                        if (now - self._exchange_info_ts if self._exchange_info_ts else now) >= EXCHANGE_INFO_REFRESH_SECONDS:
                            await self._refresh_exchange_info()
                        if (now - last_snapshot) >= SNAPSHOT_REFRESH_SECONDS and self._is_stale():
                            await self._refresh_snapshot()
                            last_snapshot = now
                        try:
                            payload = json.loads(message)
                        except Exception:
                            continue
                        data = payload.get("data") if isinstance(payload, dict) else payload
                        if isinstance(data, list):
                            self._apply_ticker_array(data)
                            if self._tickers:
                                self._status = "Live"
            except asyncio.CancelledError:
                break
            except Exception as e:
                if not self._running:
                    break
                logger.warning("Market data WebSocket error: %s", e)
                self._last_error = str(e)
                self._ws = None
                if self._tickers:
                    self._status = "Stale" if self._is_stale() else "Disconnected"
                else:
                    self._status = "Unavailable"
                # REST fallback before reconnect
                try:
                    await self._refresh_exchange_info(force=not self._tradable_usdt_perps)
                    await self._refresh_snapshot()
                except Exception:
                    pass
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, RECONNECT_MAX_SECONDS)
            finally:
                self._ws = None

        self._status = "Disconnected"

    def get_overview(self) -> Dict[str, Any]:
        status = self._effective_status()
        last_update = (
            datetime.fromtimestamp(self._last_update_ts, tz=timezone.utc).isoformat()
            if self._last_update_ts
            else None
        )
        items = list(self._tickers.values())

        def by_quote_vol(x: Dict[str, Any]) -> float:
            return float(x.get("quoteVolume") or 0.0)

        def by_pct(x: Dict[str, Any]) -> float:
            return float(x.get("priceChangePercent") or 0.0)

        # Rank by correct 24h fields (USDT perpetuals, tradable-filtered)
        volume_leaders = sorted(items, key=by_quote_vol, reverse=True)[:10]
        gainers = sorted(
            [x for x in items if by_pct(x) > 0], key=by_pct, reverse=True
        )[:10]
        losers = sorted([x for x in items if by_pct(x) < 0], key=by_pct)[:10]

        data_available = bool(self._tickers) and not (status in ("Unavailable",) and not self._tickers)
        # If stale/unavailable, clearly indicate; still return last known values if any.
        return {
            "source": "binance_usdm_futures",
            "status": status,
            "live": status == "Live",
            "stale": status == "Stale",
            "data_available": bool(self._tickers),
            "last_update": last_update,
            "timestamp": _utc_now_iso(),
            "error": self._last_error if status in ("Unavailable", "Stale", "Disconnected") else None,
            "featured": {
                "BTCUSDT": self._tickers.get("BTCUSDT"),
                "DOGEUSDT": self._tickers.get("DOGEUSDT"),
            },
            "volume_leaders": volume_leaders,
            "gainers": gainers,
            "losers": losers,
            "categories": {
                "volume": volume_leaders,
                "gainers": gainers,
                "losers": losers,
            },
            # Symbol lookup for client-side favorites/category resolution (read-only).
            "symbols": dict(self._tickers),
        }


market_data_service = BinanceFuturesMarketDataService()
