
import logging
import math
from datetime import datetime, timezone

from database import SessionLocal
from models import ExchangePosition, CycleEntry

from binance_client import binance_client
from config import settings
from notification_manager import notification_manager


logger = logging.getLogger(__name__)

EPSILON = 0.00000001


def _number(value, default=0.0):
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


class ReconciliationManager:

    @staticmethod
    def _symbol_map(exchange):

        mapping = {}

        for raw_symbol in settings.ALLOWED_SYMBOLS:

            unified_symbol = binance_client.get_exchange_symbol(
                raw_symbol
            )

            market = exchange.market(unified_symbol)
            market_id = market.get("id")

            mapping[raw_symbol.upper()] = unified_symbol
            mapping[unified_symbol.upper()] = unified_symbol

            if market_id:
                mapping[str(market_id).upper()] = unified_symbol

        return mapping

    @staticmethod
    def _local_position_totals(db, symbol_map):

        rows = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.remaining_quantity > 0
            )
            .all()
        )

        totals = {}

        for entry in rows:

            raw_symbol = str(entry.symbol).upper()
            symbol = symbol_map.get(raw_symbol)

            if not symbol:
                continue

            direction = str(entry.direction).upper()

            if direction not in ("LONG", "SHORT"):
                continue

            quantity = _number(entry.remaining_quantity)

            if quantity <= EPSILON:
                continue

            key = (symbol.upper(), direction)

            totals[key] = (
                totals.get(key, 0.0) + quantity
            )

        return totals

    def sync_positions(self):

        settings.validate_trading_mode()

        if not settings.BINANCE_USE_TESTNET:
            raise RuntimeError(
                "Position reconciliation is restricted to Testnet."
            )

        db = SessionLocal()

        try:

            exchange = binance_client.exchange

            binance_client._load_markets_sync()

            symbol_map = self._symbol_map(exchange)

            symbols = sorted({
                symbol_map[raw.upper()]
                for raw in settings.ALLOWED_SYMBOLS
            })

            positions = exchange.fetch_positions(symbols)

            if positions is None:
                raise RuntimeError(
                    "Binance returned no valid position response."
                )

            parsed_positions = []
            recognized_count = 0

            # پہلے پوری Exchange response کی جانچ ہوگی۔
            # اس کے بعد ہی مقامی records تبدیل ہوں گے۔

            for position in positions:

                info = position.get("info") or {}

                raw_symbol = (
                    position.get("symbol")
                    or info.get("symbol")
                )

                if not raw_symbol:
                    continue

                symbol = symbol_map.get(
                    str(raw_symbol).upper()
                )

                if not symbol:
                    logger.warning(
                        "Skipping unrecognized exchange symbol: %s",
                        raw_symbol,
                    )
                    continue

                recognized_count += 1

                contracts = position.get("contracts")

                if contracts is None:
                    contracts = info.get("positionAmt", 0)

                quantity = abs(_number(contracts))

                position_side = str(
                    info.get("positionSide") or ""
                ).upper()

                if position_side == "BOTH":

                    if quantity > EPSILON:
                        raise RuntimeError(
                            "Binance reports one-way position mode. "
                            "This bot requires Hedge Mode."
                        )

                    continue

                if position_side not in ("LONG", "SHORT"):

                    unified_side = str(
                        position.get("side") or ""
                    ).upper()

                    if unified_side in ("LONG", "SHORT"):
                        position_side = unified_side

                    elif quantity > EPSILON:
                        raise RuntimeError(
                            f"Could not determine position side for {symbol}."
                        )

                    else:
                        continue

                parsed_positions.append({
                    "symbol": symbol,
                    "direction": position_side,
                    "quantity": quantity,
                    "entry_price": (
                        _number(position.get("entryPrice"), None)
                        if position.get("entryPrice") is not None
                        else None
                    ),
                    "mark_price": (
                        _number(position.get("markPrice"), None)
                        if position.get("markPrice") is not None
                        else None
                    ),
                    "unrealized_pnl": _number(
                        position.get("unrealizedPnl")
                    ),
                })

            if positions and recognized_count == 0:
                raise RuntimeError(
                    "Exchange returned positions, but none matched "
                    "the configured symbols. Local records were not changed."
                )

            now = datetime.now(timezone.utc)

            existing_records = (
                db.query(ExchangePosition)
                .filter(
                    ExchangePosition.symbol.in_(symbols)
                )
                .all()
            )

            for record in existing_records:

                record.exchange_quantity = 0.0
                record.unrealized_pnl = 0.0
                record.last_synced_at = now

            exchange_totals = {}
            synced = []

            for item in parsed_positions:

                symbol = item["symbol"]
                direction = item["direction"]
                quantity = item["quantity"]

                position_key = f"{symbol}:{direction}"

                record = (
                    db.query(ExchangePosition)
                    .filter(
                        ExchangePosition.exchange_position_key
                        == position_key
                    )
                    .first()
                )

                if not record:

                    record = ExchangePosition(
                        symbol=symbol,
                        direction=direction,
                        exchange_position_key=position_key,
                    )

                    db.add(record)

                record.exchange_quantity = quantity
                record.entry_price = item["entry_price"]
                record.mark_price = item["mark_price"]
                record.unrealized_pnl = item["unrealized_pnl"]
                record.last_synced_at = now

                key = (symbol.upper(), direction)

                exchange_totals[key] = (
                    exchange_totals.get(key, 0.0) + quantity
                )

                synced.append({
                    "symbol": symbol,
                    "direction": direction,
                    "quantity": quantity,
                    "entry_price": item["entry_price"],
                    "mark_price": item["mark_price"],
                    "unrealized_pnl": item["unrealized_pnl"],
                })

            local_totals = self._local_position_totals(
                db,
                symbol_map,
            )

            comparison_keys = (
                set(local_totals.keys())
                | set(exchange_totals.keys())
            )

            mismatches = []

            for key in sorted(comparison_keys):

                local_quantity = local_totals.get(key, 0.0)
                exchange_quantity = exchange_totals.get(key, 0.0)

                difference = abs(
                    local_quantity - exchange_quantity
                )

                if difference > EPSILON:

                    mismatches.append({
                        "symbol": key[0],
                        "direction": key[1],
                        "local_quantity": local_quantity,
                        "exchange_quantity": exchange_quantity,
                        "difference": difference,
                    })

            if mismatches:
                notification_manager.create(
                    db,
                    title="Position reconciliation mismatch",
                    message=(
                        f"{len(mismatches)} local/exchange position mismatch(es) detected. "
                        "Review mismatches before taking action."
                    ),
                    notification_type="RECONCILIATION_MISMATCH",
                    severity="WARNING",
                    commit=False,
                )

            db.commit()

            return {
                "success": True,
                "positions_synced": len(synced),
                "positions": synced,
                "comparison": {
                    "matched": len(mismatches) == 0,
                    "mismatch_count": len(mismatches),
                    "mismatches": mismatches,
                },
                "synced_at": now.isoformat(),
                "trading_mode": "TESTNET",
                "note": (
                    "Reconciliation reports differences only. "
                    "It does not modify entry quantities or submit orders."
                ),
            }

        except Exception:

            db.rollback()

            logger.exception(
                "Position reconciliation failed."
            )

            raise

        finally:
            db.close()


reconciliation_manager = ReconciliationManager()
