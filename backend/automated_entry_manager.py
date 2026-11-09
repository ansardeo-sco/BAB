import logging

from config import settings
from binance_client import binance_client
from models import CycleEntry, CycleOrder
from settings_manager import settings_manager


logger = logging.getLogger(__name__)

EPSILON = 0.00000001


class AutomatedEntryManager:

    def place_entry(self, db, cycle, entry_type, margin):

        settings.validate_trading_mode()

        if not settings.BINANCE_USE_TESTNET:
            raise RuntimeError(
                "Automated entries are restricted to Testnet."
            )

        if cycle.status != "ACTIVE":
            raise RuntimeError(
                "Entries can only be placed in an ACTIVE cycle."
            )

        if entry_type not in ("AVERAGING", "OPPOSITE"):
            raise ValueError(
                "Invalid automated entry type."
            )

        if cycle.direction not in ("LONG", "SHORT"):
            raise ValueError(
                "Invalid cycle direction."
            )

        symbol_settings = settings_manager.get_symbol_settings(
            db,
            cycle.symbol,
        )

        if entry_type == "OPPOSITE":
            # Opposite entry opens in this cycle's direction; verify it is enabled.
            direction_enabled = (
                symbol_settings["enabled_long"]
                if cycle.direction == "LONG"
                else symbol_settings["enabled_short"]
            )

            if not direction_enabled:
                raise RuntimeError(
                    f"{cycle.direction} trading is disabled "
                    f"for {cycle.symbol}. "
                    "Opposite entry was blocked."
                )

        margin = float(margin or 0)

        if not (
            settings.MIN_MARGIN_USDT
            <= margin
            <= settings.MAX_MARGIN_USDT
        ):
            raise ValueError(
                "Margin is outside the permitted limits."
            )

        leverage = int(cycle.leverage)

        if not (
            1 <= leverage <= settings.MAX_LEVERAGE
        ):
            raise ValueError("Invalid leverage.")

        # Check duplicate entry/order records BEFORE contacting Binance.
        # This makes duplicate protection deterministic and allows tests
        # to run without any exchange network access.
        existing = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.cycle_id == cycle.id,
                CycleEntry.entry_type == entry_type,
            )
            .first()
        )

        if existing:
            raise RuntimeError(
                f"{entry_type} entry already exists for this cycle. "
                "Check its exchange status before taking further action."
            )

        suffix = (
            "A" if entry_type == "AVERAGING" else "O"
        )
        client_order_id = f"bot{cycle.id}{suffix}"

        duplicate_order = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.client_order_id == client_order_id
            )
            .first()
        )

        if duplicate_order:
            raise RuntimeError(
                "This automated order ID already exists. "
                "Exchange reconciliation is required."
            )

        # Credentials are checked only after local validation and duplicate
        # protection, but before any exchange request is attempted.
        if (
            not settings.BINANCE_API_KEY
            or not settings.BINANCE_API_SECRET
        ):
            raise RuntimeError(
                "Binance API credentials are missing."
            )

        exchange = binance_client.exchange
        symbol = cycle.symbol

        exchange_symbol = (
            binance_client.prepare_symbol_for_order(
                symbol,
                leverage,
            )
        )

        market_price = binance_client._fetch_mark_price_sync(
            symbol
        )

        if not market_price or market_price <= 0:
            raise ValueError("Invalid market price.")

        raw_quantity = (
            margin * leverage
        ) / market_price

        quantity = binance_client.format_order_quantity(
            exchange_symbol,
            raw_quantity,
            reference_price=market_price,
            check_min_cost=True,
        )

        side = (
            "buy" if cycle.direction == "LONG" else "sell"
        )

        entry = CycleEntry(
            cycle_id=cycle.id,
            symbol=symbol,
            direction=cycle.direction,
            entry_type=entry_type,
            status="PENDING",
            requested_margin=margin,
            leverage=leverage,
            requested_quantity=quantity,
            filled_quantity=0.0,
            remaining_quantity=0.0,
            stop_loss_percent=cycle.sl_percent,
            client_order_id=client_order_id,
        )

        order = CycleOrder(
            cycle_id=cycle.id,
            entry_id=None,
            symbol=symbol,
            order_type="MARKET",
            side=side,
            status="PENDING",
            requested_quantity=quantity,
            filled_quantity=0.0,
            client_order_id=client_order_id,
            reduce_position=False,
        )

        try:
            db.add(entry)
            db.flush()

            order.entry_id = entry.id

            db.add(order)

            # Save local records before sending the exchange request.
            db.commit()

        except Exception:
            db.rollback()
            raise

        try:
            result = exchange.create_order(
                symbol=exchange_symbol,
                type="market",
                side=side,
                amount=quantity,
                params={
                    "positionSide": cycle.direction,
                    "newClientOrderId": client_order_id,
                },
            )

            order_id = result.get("id")

            if not order_id:
                raise RuntimeError(
                    "Exchange did not return an order ID."
                )

            order.exchange_order_id = str(order_id)
            entry.exchange_order_id = str(order_id)

            db.commit()

            confirmed = exchange.fetch_order(
                order_id,
                exchange_symbol,
            )

            filled = float(
                confirmed.get("filled") or 0
            )

            if filled < 0 or filled > quantity + EPSILON:
                raise RuntimeError(
                    "Exchange returned an invalid filled quantity."
                )

            average = confirmed.get("average")

            order.filled_quantity = filled

            order.average_fill_price = (
                float(average)
                if average is not None
                else None
            )

            entry.filled_quantity = filled
            entry.remaining_quantity = filled

            entry.entry_price = (
                float(average)
                if average is not None
                else None
            )

            status = str(
                confirmed.get("status") or "open"
            ).lower()

            is_terminal = status in (
                "closed",
                "canceled",
                "cancelled",
                "expired",
                "rejected",
            )

            is_fully_filled = (
                quantity > 0
                and filled + EPSILON >= quantity
            )

            if is_terminal and is_fully_filled:
                order.status = "FILLED"
                entry.status = "FILLED"

            elif is_terminal and filled > 0:
                order.status = "CANCELLED_PARTIAL"
                entry.status = "PARTIALLY_FILLED"

            elif is_terminal:
                order.status = "CANCELLED"
                entry.status = "CANCELLED"

            elif filled > 0:
                order.status = "PARTIALLY_FILLED"
                entry.status = "PARTIALLY_FILLED"

            else:
                order.status = "PENDING"
                entry.status = "PENDING"

            db.commit()

            return {
                "success": True,
                "entry_id": entry.id,
                "order_id": order_id,
                "entry_type": entry_type,
                "filled_quantity": filled,
                "average_price": average,
                "status": order.status,
            }

        except Exception as error:
            db.rollback()

            saved_entry = (
                db.query(CycleEntry)
                .filter(
                    CycleEntry.client_order_id == client_order_id
                )
                .first()
            )

            saved_order = (
                db.query(CycleOrder)
                .filter(
                    CycleOrder.client_order_id == client_order_id
                )
                .first()
            )

            if saved_entry:
                saved_entry.status = "UNKNOWN"

            if saved_order:
                saved_order.status = "UNKNOWN"
                saved_order.error_message = str(error)

            db.commit()

            logger.exception(
                "Automated entry could not be confirmed."
            )

            raise RuntimeError(
                "Automated order status is UNKNOWN. "
                "Do not retry before Binance Testnet reconciliation."
            ) from error


automated_entry_manager = AutomatedEntryManager()
