import logging
import math
from datetime import datetime, timezone

from database import SessionLocal, lock_for_update
from models import (
    CycleOrder,
    CycleEntry,
    TradingCycle,
    ReentryRecord,
)
from binance_client import binance_client
from config import settings
from notification_manager import notification_manager


logger = logging.getLogger(__name__)

EPSILON = 0.00000001

OPEN_ORDER_STATUSES = [
    "PENDING",
    "PARTIALLY_FILLED",
    "UNKNOWN",
]


def _number(value, default=0.0):
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


class OrderStatusManager:

    TERMINAL_STATUSES = {
        "closed",
        "canceled",
        "cancelled",
        "expired",
        "rejected",
    }

    def sync_pending_orders(self):

        settings.validate_trading_mode()

        if not settings.BINANCE_USE_TESTNET:
            raise RuntimeError(
                "Order synchronization is restricted to Testnet."
            )

        db = SessionLocal()
        results = []

        try:
            order_ids = [
                row[0]
                for row in (
                    db.query(CycleOrder.id)
                    .filter(
                        CycleOrder.status.in_(OPEN_ORDER_STATUSES)
                    )
                    .order_by(CycleOrder.id.asc())
                    .all()
                )
            ]

            for local_order_id in order_ids:

                try:
                    order = lock_for_update(
                        db.query(CycleOrder).filter(
                            CycleOrder.id == local_order_id
                        )
                    ).first()

                    if not order:
                        continue

                    exchange_order = binance_fetch_order(order)

                    if not exchange_order:
                        continue

                    old_filled = _number(
                        order.filled_quantity
                    )

                    exchange_filled = _number(
                        exchange_order.get("filled"),
                        old_filled,
                    )

                    # Exchange fill quantity کبھی مقامی تصدیق شدہ
                    # مقدار سے کم ہو تو اسے کم نہیں کیا جائے گا۔
                    new_filled = max(
                        old_filled,
                        exchange_filled,
                    )

                    fill_delta = max(
                        0.0,
                        new_filled - old_filled,
                    )

                    exchange_status = str(
                        exchange_order.get("status") or "open"
                    ).lower()

                    average = exchange_order.get("average")

                    if average is None:
                        cost = _number(
                            exchange_order.get("cost")
                        )

                        if new_filled > EPSILON and cost > 0:
                            average = cost / new_filled

                    entry = None

                    if order.entry_id:
                        entry = lock_for_update(
                            db.query(CycleEntry).filter(
                                CycleEntry.id == order.entry_id
                            )
                        ).first()

                    exchange_id = exchange_order.get("id")

                    if exchange_id:
                        order.exchange_order_id = str(exchange_id)

                        if entry:
                            entry.exchange_order_id = str(exchange_id)

                    order.filled_quantity = new_filled

                    if average is not None:
                        average = _number(average)

                        if average > 0:
                            order.average_fill_price = average

                            if entry and not order.reduce_position:
                                entry.entry_price = average

                    if entry and fill_delta > EPSILON:

                        if order.reduce_position:

                            current_remaining = _number(
                                entry.remaining_quantity
                            )

                            entry.remaining_quantity = max(
                                0.0,
                                current_remaining - fill_delta,
                            )

                        else:

                            entry.filled_quantity = new_filled

                            entry.remaining_quantity = (
                                _number(entry.remaining_quantity)
                                + fill_delta
                            )

                            if not entry.filled_at:
                                entry.filled_at = datetime.now(
                                    timezone.utc
                                )

                    requested = _number(
                        order.requested_quantity
                    )

                    is_fully_filled = (
                        requested > 0
                        and new_filled + EPSILON >= requested
                    )

                    if exchange_status == "closed":

                        if is_fully_filled:
                            order.status = "FILLED"

                        elif new_filled > EPSILON:
                            order.status = "CANCELLED_PARTIAL"

                        else:
                            order.status = "CANCELLED"

                    elif exchange_status == "rejected":
                        order.status = "REJECTED"

                    elif exchange_status == "expired":
                        order.status = "EXPIRED"

                    elif exchange_status in (
                        "canceled",
                        "cancelled",
                    ):

                        order.status = (
                            "CANCELLED_PARTIAL"
                            if new_filled > EPSILON
                            else "CANCELLED"
                        )

                    elif new_filled > EPSILON:
                        order.status = "PARTIALLY_FILLED"

                    else:
                        order.status = "PENDING"

                    if entry:

                        if order.reduce_position:

                            if (
                                _number(entry.remaining_quantity)
                                <= EPSILON
                            ):

                                entry.remaining_quantity = 0.0
                                entry.status = "CLOSED"

                                if not entry.closed_at:
                                    entry.closed_at = datetime.now(
                                        timezone.utc
                                    )

                            elif fill_delta > EPSILON:
                                entry.status = "PARTIALLY_FILLED"

                        else:

                            if order.status == "FILLED":
                                entry.status = "FILLED"

                            elif order.status in (
                                "CANCELLED",
                                "REJECTED",
                                "EXPIRED",
                            ):

                                entry.status = (
                                    "PARTIALLY_FILLED"
                                    if new_filled > EPSILON
                                    else "CANCELLED"
                                )

                            elif new_filled > EPSILON:
                                entry.status = "PARTIALLY_FILLED"

                            else:
                                entry.status = "PENDING"

                    self._update_cycle_status(
                        db,
                        order.cycle_id,
                    )

                    result_item = {
                        "local_order_id": order.id,
                        "exchange_order_id": order.exchange_order_id,
                        "client_order_id": order.client_order_id,
                        "status": order.status,
                        "filled_quantity": new_filled,
                        "new_fill_quantity": fill_delta,
                        "remaining_quantity": (
                            _number(entry.remaining_quantity)
                            if entry
                            else None
                        ),
                    }

                    db.commit()
                    results.append(result_item)

                except Exception as error:

                    db.rollback()

                    logger.exception(
                        "Order %s synchronization failed: %s",
                        local_order_id,
                        error,
                    )

                    try:
                        notification_manager.create(
                            db,
                            title="Order synchronization issue",
                            message=(
                                f"Local order {local_order_id} could not be "
                                "synchronized with the exchange. "
                                "Do not retry before reconciliation."
                            ),
                            notification_type="ORDER_SYNC_WARNING",
                            severity="WARNING",
                            commit=False,
                        )
                    except Exception:
                        pass

                    # غیر یقینی آرڈر پر نیا آرڈر نہیں بھیجا جائے گا۔
                    continue

            return results

        except Exception:
            db.rollback()

            logger.exception(
                "Order synchronization failed."
            )
            raise

        finally:
            db.close()

    @staticmethod
    def _create_tp_reentry(db, cycle):

        existing = (
            db.query(ReentryRecord)
            .filter(
                ReentryRecord.cycle_id == cycle.id
            )
            .first()
        )

        if existing:
            return

        tp_orders = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.reduce_position.is_(True),
                CycleOrder.order_type == "TAKE_PROFIT_CLOSE",
                CycleOrder.filled_quantity > 0,
            )
            .all()
        )

        total_quantity = 0.0
        total_value = 0.0

        for order in tp_orders:

            quantity = _number(order.filled_quantity)
            price = _number(order.average_fill_price)

            if quantity <= 0 or price <= 0:
                continue

            total_quantity += quantity
            total_value += quantity * price

        if total_quantity <= EPSILON:
            logger.warning(
                "No confirmed TP close fills for cycle %s. "
                "Re-entry not created.",
                cycle.id,
            )
            return

        reference_price = total_value / total_quantity
        reentry_percent = _number(cycle.reentry_percent)

        if reentry_percent <= 0:
            logger.warning(
                "Invalid re-entry percentage for cycle %s.",
                cycle.id,
            )
            return

        if cycle.direction == "LONG":
            trigger_price = reference_price * (
                1 - reentry_percent / 100
            )

        elif cycle.direction == "SHORT":
            trigger_price = reference_price * (
                1 + reentry_percent / 100
            )

        else:
            logger.error(
                "Invalid cycle direction for re-entry: %s",
                cycle.direction,
            )
            return

        record = ReentryRecord(
            cycle_id=cycle.id,
            symbol=cycle.symbol,
            direction=cycle.direction,
            reference_close_price=reference_price,
            trigger_price=trigger_price,
            status="WAITING",
        )

        cycle.reentry_price = trigger_price
        db.add(record)

        logger.info(
            "Re-entry created for cycle %s: %s at %s",
            cycle.id,
            cycle.direction,
            trigger_price,
        )

    @classmethod
    def _update_cycle_status(cls, db, cycle_id):

        cycle = lock_for_update(
            db.query(TradingCycle).filter(TradingCycle.id == cycle_id)
        ).first()

        if not cycle:
            return

        entries = (
            db.query(CycleEntry)
            .filter(CycleEntry.cycle_id == cycle_id)
            .all()
        )

        if not entries:
            return

        unresolved_orders = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle_id,
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .count()
        )

        unresolved_entries = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.cycle_id == cycle_id,
                CycleEntry.status.in_([
                    "PENDING",
                    "UNKNOWN",
                ]),
            )
            .count()
        )

        all_entries_closed = all(
            _number(entry.remaining_quantity) <= EPSILON
            for entry in entries
        )

        if (
            all_entries_closed
            and unresolved_orders == 0
            and unresolved_entries == 0
        ):

            was_closing_tp = cycle.status == "CLOSING_TP"

            cycle.status = "CLOSED"

            if not cycle.closed_at:
                cycle.closed_at = datetime.now(timezone.utc)

            if was_closing_tp:
                cls._create_tp_reentry(db, cycle)

            return

        if cycle.status == "CLOSING_TP":
            return

        if (
            cycle.status == "ORDER_UNCONFIRMED"
            and unresolved_orders == 0
            and unresolved_entries == 0
        ):

            cycle.status = "ACTIVE"
            cycle.closed_at = None


def binance_fetch_order(order):

    settings.validate_trading_mode()

    if not settings.BINANCE_USE_TESTNET:
        raise RuntimeError(
            "Order lookup is restricted to Testnet."
        )

    exchange = binance_client.exchange

    exchange_symbol = binance_client.get_exchange_symbol(
        order.symbol
    )

    if order.exchange_order_id:

        return exchange.fetch_order(
            order.exchange_order_id,
            exchange_symbol,
        )

    if order.client_order_id:

        return exchange.fetch_order(
            None,
            exchange_symbol,
            {
                "origClientOrderId": order.client_order_id
            },
        )

    raise RuntimeError(
        "Order has neither exchange ID nor client order ID."
    )


order_status_manager = OrderStatusManager()
