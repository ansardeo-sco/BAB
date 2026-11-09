import logging
import math

from models import CycleEntry, CycleOrder
from close_order_manager import close_order_manager


logger = logging.getLogger(__name__)

EPSILON = 0.00000001

OPEN_ORDER_STATUSES = [
    "PENDING",
    "PARTIALLY_FILLED",
    "UNKNOWN",
]

OPEN_ENTRY_STATUSES = [
    "FILLED",
    "PARTIALLY_FILLED",
]


class StopLossManager:

    def process_cycle(self, db, cycle, current_price: float):

        if cycle.status != "ACTIVE":
            return {
                "processed": False,
                "message": "Cycle is not active.",
                "triggered_entries": 0,
            }

        try:
            current_price = float(current_price)
        except (TypeError, ValueError):
            return {
                "processed": False,
                "message": "Invalid market price.",
                "triggered_entries": 0,
            }

        if not math.isfinite(current_price) or current_price <= 0:
            return {
                "processed": False,
                "message": "Invalid market price.",
                "triggered_entries": 0,
            }

        # غیر مصدقہ Entry Order کے دوران SL calculation روکیں۔
        pending_entry_order = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.reduce_position.is_(False),
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .first()
        )

        if pending_entry_order:
            return {
                "processed": False,
                "blocked": True,
                "triggered_entries": 0,
                "pending_order_id": pending_entry_order.id,
                "message": (
                    "Stop-loss is waiting for the opening order "
                    "to be reconciled."
                ),
            }

        entries = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.cycle_id == cycle.id,
                CycleEntry.status.in_(OPEN_ENTRY_STATUSES),
                CycleEntry.remaining_quantity > EPSILON,
            )
            .order_by(CycleEntry.id.asc())
            .all()
        )

        results = []
        close_attempts = 0

        for entry in entries:

            try:
                entry_price = float(entry.entry_price or 0)
                stop_percent = float(
                    entry.stop_loss_percent or 0
                )
                remaining = float(
                    entry.remaining_quantity or 0
                )

                if (
                    not math.isfinite(entry_price)
                    or entry_price <= 0
                    or not math.isfinite(stop_percent)
                    or stop_percent <= 0
                    or not math.isfinite(remaining)
                    or remaining <= EPSILON
                ):
                    continue

                direction = str(
                    entry.direction or cycle.direction
                ).upper()

                if direction == "LONG":

                    loss_percent = (
                        (entry_price - current_price) / entry_price
                    ) * 100

                    stop_price = entry_price * (
                        1 - stop_percent / 100
                    )

                elif direction == "SHORT":

                    loss_percent = (
                        (current_price - entry_price) / entry_price
                    ) * 100

                    stop_price = entry_price * (
                        1 + stop_percent / 100
                    )

                else:
                    logger.error(
                        "Invalid direction for entry %s: %s",
                        entry.id,
                        direction,
                    )
                    continue

                entry.stop_loss_price = stop_price

                if loss_percent < stop_percent:
                    continue

                # اسی Entry کا Close Order پہلے سے زیرِ تصدیق ہو تو
                # دوسرا Close Order نہیں بھیجیں۔
                pending_close = (
                    db.query(CycleOrder)
                    .filter(
                        CycleOrder.entry_id == entry.id,
                        CycleOrder.reduce_position.is_(True),
                        CycleOrder.status.in_(OPEN_ORDER_STATUSES),
                    )
                    .first()
                )

                if pending_close:
                    results.append({
                        "entry_id": entry.id,
                        "status": "CLOSE_PENDING",
                        "message": (
                            "A close order for this entry is "
                            "already pending or unconfirmed."
                        ),
                    })
                    continue

                close_attempts += 1

                try:
                    closing_result = close_order_manager.close_entry(
                        db=db,
                        entry_id=entry.id,
                        reason="STOP_LOSS",
                    )

                    results.append({
                        "entry_id": entry.id,
                        "direction": direction,
                        "entry_price": entry_price,
                        "current_price": current_price,
                        "loss_percent": loss_percent,
                        "stop_loss_percent": stop_percent,
                        "stop_loss_price": stop_price,
                        "remaining_quantity": remaining,
                        "closing_result": closing_result,
                    })

                except Exception as close_error:

                    logger.exception(
                        "Stop-loss close request failed for entry %s.",
                        entry.id,
                    )

                    results.append({
                        "entry_id": entry.id,
                        "status": "CLOSE_ERROR",
                        "message": str(close_error),
                    })

            except Exception:

                logger.exception(
                    "Stop-loss processing failed for entry %s.",
                    entry.id,
                )

                results.append({
                    "entry_id": entry.id,
                    "status": "PROCESSING_ERROR",
                    "message": "Entry stop-loss processing failed.",
                })

        db.commit()

        return {
            "processed": True,
            "cycle_id": cycle.id,
            "current_price": current_price,
            "triggered_entries": close_attempts,
            "results": results,
        }


stop_loss_manager = StopLossManager()
