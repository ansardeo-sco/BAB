import logging
import math

from models import CycleEntry, CycleOrder
from close_order_manager import close_order_manager


logger = logging.getLogger(__name__)

OPEN_ORDER_STATUSES = [
    "PENDING",
    "PARTIALLY_FILLED",
    "UNKNOWN",
]

OPEN_ENTRY_STATUSES = [
    "FILLED",
    "PARTIALLY_FILLED",
]

EPSILON = 0.00000001


class TakeProfitManager:

    def process_cycle(self, db, cycle, current_price: float):

        if cycle.status not in ("ACTIVE", "CLOSING_TP"):
            return {
                "triggered": False,
                "message": "Cycle is not active or closing.",
            }

        try:
            current_price = float(current_price)
            tp_percent = float(cycle.tp_percent)
        except (TypeError, ValueError):
            return {
                "triggered": False,
                "message": "Invalid price or take-profit setting.",
            }

        if (
            not math.isfinite(current_price)
            or current_price <= 0
            or not math.isfinite(tp_percent)
            or tp_percent <= 0
        ):
            return {
                "triggered": False,
                "message": "Invalid price or take-profit setting.",
            }

        pending_close = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.reduce_position.is_(True),
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .first()
        )

        if pending_close:
            return {
                "triggered": False,
                "message": (
                    "A closing order is pending or uncertain. "
                    "Waiting for exchange confirmation."
                ),
            }

        pending_entry = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.reduce_position.is_(False),
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .first()
        )

        if pending_entry:
            return {
                "triggered": False,
                "message": (
                    "An entry order is still pending confirmation."
                ),
            }

        entries = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.cycle_id == cycle.id,
                CycleEntry.status.in_(OPEN_ENTRY_STATUSES),
                CycleEntry.remaining_quantity > EPSILON,
                CycleEntry.entry_price.isnot(None),
            )
            .order_by(CycleEntry.id.asc())
            .all()
        )

        if not entries:
            return {
                "triggered": False,
                "message": "No open entry quantity.",
            }

        total_quantity = 0.0
        weighted_value = 0.0

        for entry in entries:
            try:
                quantity = float(entry.remaining_quantity or 0)
                entry_price = float(entry.entry_price or 0)
            except (TypeError, ValueError):
                continue

            if (
                not math.isfinite(quantity)
                or not math.isfinite(entry_price)
                or quantity <= EPSILON
                or entry_price <= 0
            ):
                continue

            total_quantity += quantity
            weighted_value += entry_price * quantity

        if total_quantity <= EPSILON:
            return {
                "triggered": False,
                "message": "No valid open quantity remains.",
            }

        average_price = weighted_value / total_quantity

        if not math.isfinite(average_price) or average_price <= 0:
            return {
                "triggered": False,
                "message": "Invalid weighted average entry price.",
            }

        if cycle.direction == "LONG":

            profit_percent = (
                (current_price - average_price) / average_price
            ) * 100

            target_price = average_price * (
                1 + tp_percent / 100
            )

        elif cycle.direction == "SHORT":

            profit_percent = (
                (average_price - current_price) / average_price
            ) * 100

            target_price = average_price * (
                1 - tp_percent / 100
            )

        else:
            return {
                "triggered": False,
                "message": "Invalid cycle direction.",
            }

        cycle.average_entry_price = average_price
        cycle.total_filled_quantity = total_quantity
        cycle.take_profit_price = target_price

        # TP پہلی مرتبہ حاصل ہونے پر closing mode مستقل طور پر شروع ہوگا۔
        if cycle.status == "ACTIVE":

            if profit_percent < tp_percent:
                db.commit()

                return {
                    "triggered": False,
                    "profit_percent": profit_percent,
                    "target_price": target_price,
                    "average_entry_price": average_price,
                }

            cycle.status = "CLOSING_TP"
            db.commit()

        # CLOSING_TP کے بعد قیمت واپس ہونے پر بھی باقی مقدار بند ہوگی۔
        # ایک وقت میں ایک Entry کا Close Order بھیجا جائے گا۔
        entry = entries[0]

        try:
            closing_result = close_order_manager.close_entry(
                db=db,
                entry_id=entry.id,
                reason="TAKE_PROFIT",
            )

        except Exception as error:
            db.rollback()

            logger.exception(
                "Take-profit close failed for entry %s.",
                entry.id,
            )

            return {
                "triggered": False,
                "cycle_id": cycle.id,
                "entry_id": entry.id,
                "status": "CLOSE_UNCONFIRMED",
                "message": str(error),
            }

        return {
            "triggered": True,
            "cycle_id": cycle.id,
            "entry_id": entry.id,
            "profit_percent": profit_percent,
            "target_price": target_price,
            "average_entry_price": average_price,
            "closing_result": closing_result,
            "message": (
                "One entry close request was processed. "
                "Remaining entries will be handled after confirmation."
            ),
        }


take_profit_manager = TakeProfitManager()
