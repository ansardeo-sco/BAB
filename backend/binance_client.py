import asyncio
import logging
import math
from typing import Any, Dict, Optional

import ccxt

from config import settings


logger = logging.getLogger(__name__)


class BinanceFuturesClient:

    def __init__(self):

        if not settings.BINANCE_USE_TESTNET:
            raise RuntimeError(
                "Production Binance access is blocked. "
                "Set BINANCE_USE_TESTNET=true."
            )

        if settings.LIVE_TRADING_ENABLED:
            raise RuntimeError(
                "Live trading is disabled."
            )

        settings.validate_trading_mode()

        self.exchange = ccxt.binance({
            "apiKey": settings.BINANCE_API_KEY,
            "secret": settings.BINANCE_API_SECRET,
            "enableRateLimit": True,
            "timeout": 20000,
            "options": {
                "defaultType": "future",
                "adjustForTimeDifference": True,
            },
        })

        self.exchange.set_sandbox_mode(True)

        self._markets_loaded = False

    def _require_testnet(self):

        settings.validate_trading_mode()

        if not settings.BINANCE_USE_TESTNET:
            raise RuntimeError(
                "Binance Testnet is required."
            )

    def _load_markets_sync(self):

        self._require_testnet()

        if not self._markets_loaded:
            self.exchange.load_markets()
            self._markets_loaded = True

    def _resolve_market(self, symbol: str):

        if not isinstance(symbol, str) or not symbol.strip():
            raise ValueError("Trading symbol is required.")

        self._load_markets_sync()

        normalized = symbol.strip().upper()

        market = self.exchange.markets.get(normalized)

        if market is None:

            candidates = (
                self.exchange.markets_by_id.get(normalized, [])
            )

            if isinstance(candidates, dict):
                candidates = [candidates]

            for candidate in candidates:

                if (
                    candidate.get("contract")
                    and candidate.get("linear") is True
                    and candidate.get("quote") == "USDT"
                ):
                    market = candidate
                    break

        if not market:
            raise ValueError(
                f"Unsupported Binance Futures market: {symbol}"
            )

        if not market.get("contract"):
            raise ValueError(
                f"{symbol} is not a Futures contract market."
            )

        if market.get("linear") is not True:
            raise ValueError(
                f"{symbol} is not a linear USDT-margined Futures market."
            )

        if market.get("quote") != "USDT":
            raise ValueError(
                f"{symbol} is not a USDT-quoted Futures market."
            )

        return market

    def get_exchange_symbol(self, symbol: str) -> str:

        market = self._resolve_market(symbol)

        return market["symbol"]

    def _validate_symbol(self, symbol: str):

        return self._resolve_market(symbol)

    def ensure_hedge_mode(self, exchange_symbol: str):

        self._require_testnet()

        try:

            mode = self.exchange.fetch_position_mode(
                exchange_symbol
            )

        except Exception as error:

            raise RuntimeError(
                "Unable to verify Binance Hedge Mode. "
                "No order will be submitted."
            ) from error

        hedged = mode.get("hedged")

        if hedged is not True:
            raise RuntimeError(
                "Binance Hedge Mode is not enabled. "
                "Enable Hedge Mode on Binance Testnet first."
            )

        return True

    def ensure_isolated_margin(self, exchange_symbol: str):

        self._require_testnet()

        try:

            self.exchange.set_margin_mode(
                "isolated",
                exchange_symbol,
            )

        except Exception as error:

            message = str(error).lower()

            already_isolated = (
                "-4046" in message
                or "no need to change margin type" in message
            )

            if not already_isolated:
                raise

        return True

    def prepare_symbol_for_order(
        self,
        symbol: str,
        leverage: Optional[int] = None,
    ):

        self._require_testnet()

        exchange_symbol = self.get_exchange_symbol(symbol)

        self.ensure_hedge_mode(exchange_symbol)

        self.ensure_isolated_margin(exchange_symbol)

        if leverage is not None:

            if isinstance(leverage, bool):
                raise ValueError("Invalid leverage.")

            leverage = int(leverage)

            if not (
                1 <= leverage <= settings.MAX_LEVERAGE
            ):
                raise ValueError("Leverage is outside permitted limits.")

            self.exchange.set_leverage(
                leverage,
                exchange_symbol,
            )

        return exchange_symbol

    def format_order_quantity(
        self,
        exchange_symbol: str,
        raw_quantity: float,
        reference_price: Optional[float] = None,
        check_min_cost: bool = True,
    ) -> float:

        self._require_testnet()

        try:
            raw_quantity = float(raw_quantity)
        except (TypeError, ValueError):
            raise ValueError("Invalid order quantity.")

        if not math.isfinite(raw_quantity) or raw_quantity <= 0:
            raise ValueError("Order quantity must be positive and finite.")

        quantity = float(
            self.exchange.amount_to_precision(
                exchange_symbol,
                raw_quantity,
            )
        )

        if not math.isfinite(quantity) or quantity <= 0:
            raise ValueError(
                "Calculated order quantity is zero or invalid."
            )

        market = self.exchange.market(exchange_symbol)
        limits = market.get("limits") or {}

        amount_limits = limits.get("amount") or {}

        min_amount = amount_limits.get("min")
        max_amount = amount_limits.get("max")

        if (
            min_amount is not None
            and quantity < float(min_amount)
        ):
            raise ValueError(
                f"Order quantity {quantity} is below "
                f"minimum quantity {min_amount}."
            )

        if (
            max_amount is not None
            and quantity > float(max_amount)
        ):
            raise ValueError(
                f"Order quantity {quantity} exceeds "
                f"maximum quantity {max_amount}."
            )

        if check_min_cost and reference_price is not None:

            cost_limits = limits.get("cost") or {}

            min_cost = cost_limits.get("min")
            max_cost = cost_limits.get("max")

            estimated_cost = quantity * float(reference_price)

            if (
                min_cost is not None
                and estimated_cost < float(min_cost)
            ):
                raise ValueError(
                    "Order value is below exchange minimum cost."
                )

            if (
                max_cost is not None
                and estimated_cost > float(max_cost)
            ):
                raise ValueError(
                    "Order value exceeds exchange maximum cost."
                )

        return quantity

    def _fetch_mark_price_sync(self, symbol: str) -> float:

        self._require_testnet()

        market = self._validate_symbol(symbol)

        response = self.exchange.request(
            "premiumIndex",
            "fapiPublic",
            "GET",
            {
                "symbol": market["id"],
            },
        )

        mark_price = response.get("markPrice")

        if mark_price is None:
            raise ValueError(
                f"Mark price unavailable for {symbol}."
            )

        price = float(mark_price)

        if not math.isfinite(price) or price <= 0:
            raise ValueError(
                f"Invalid mark price received for {symbol}."
            )

        return price

    async def get_market_price(self, symbol: str) -> float:

        try:

            return await asyncio.to_thread(
                self._fetch_mark_price_sync,
                symbol,
            )

        except Exception:

            logger.exception(
                "Market price request failed for %s.",
                symbol,
            )

            raise

    def _verify_connection_sync(self) -> Dict[str, Any]:

        if (
            not settings.BINANCE_API_KEY
            or not settings.BINANCE_API_SECRET
        ):
            return {
                "status": "Disconnected",
                "error": "Binance API credentials are not configured.",
                "testnet": True,
                "trading_mode": "TESTNET",
            }

        try:

            self._require_testnet()

            balance = self.exchange.fetch_balance({
                "type": "future",
            })

            usdt = balance.get("USDT") or {}

            return {
                "status": "Connected",
                "testnet": True,
                "trading_mode": "TESTNET",
                "free_usdt": float(usdt.get("free") or 0),
                "total_usdt": float(usdt.get("total") or 0),
            }

        except Exception as error:

            logger.exception(
                "Binance Testnet connection verification failed."
            )

            return {
                "status": "Disconnected",
                "testnet": True,
                "trading_mode": "TESTNET",
                "error": str(error),
            }

    def verify_connection(self) -> Dict[str, Any]:

        return self._verify_connection_sync()

    async def get_balance(self) -> Dict[str, Any]:

        return await asyncio.to_thread(
            self._verify_connection_sync
        )

    def close(self):

        try:

            result = self.exchange.close()

            if asyncio.iscoroutine(result):
                return result

        except Exception:

            logger.exception(
                "Exchange connection close warning."
            )


binance_client = BinanceFuturesClient()
