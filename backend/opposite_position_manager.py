import logging

from config import settings
from models import TradingCycle, CycleEvent, CycleOrder, CycleEntry
from automated_entry_manager import automated_entry_manager
from settings_manager import settings_manager


logger = logging.getLogger(__name__)

OPEN_ORDER_STATUSES = [
    "PENDING",
    "PARTIALLY_FILLED",
    "UNKNOWN",
]

ACTIVE_CYCLE_STATUSES = [
    "ACTIVE",
    "CLOSING_TP",
    "ORDER_UNCONFIRMED",
]


class OppositePositionManager:

    @staticmethod
    def _direction_enabled(symbol_settings, direction):
        if direction == "LONG":
            return bool(symbol_settings["enabled_long"])

        if direction == "SHORT":
            return bool(symbol_settings["enabled_short"])

        return False

    def process_cycle(self, db, cycle, adverse_percent):

        if cycle.status != "ACTIVE":
            return {
                "triggered": False,
                "message": "Cycle is not active.",
            }

        if cycle.opposite_triggered:
            return {
                "triggered": False,
                "message": "Opposite position already triggered.",
            }

        try:
            adverse_percent = float(adverse_percent)
            threshold = float(cycle.opposite_percent)
        except (TypeError, ValueError):
            return {
                "triggered": False,
                "message": "Invalid opposite-position threshold.",
            }

        if adverse_percent < threshold:
            return {"triggered": False}

        pending = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .first()
        )

        if pending:
            return {
                "triggered": False,
                "message": "An order is pending or unconfirmed.",
            }

        opposite_direction = (
            "SHORT" if cycle.direction == "LONG" else "LONG"
        )

        symbol_settings = settings_manager.get_symbol_settings(
            db,
            cycle.symbol,
        )

        if not self._direction_enabled(
            symbol_settings,
            opposite_direction,
        ):
            cycle.opposite_triggered = True
            db.commit()

            self._record_event(
                db,
                cycle,
                adverse_percent,
                "OPPOSITE_SKIPPED",
                "SKIPPED",
                f"{opposite_direction} trading is disabled.",
            )

            return {
                "triggered": False,
                "message": f"{opposite_direction} trading is disabled.",
            }

        existing = (
            db.query(TradingCycle)
            .filter(
                TradingCycle.symbol == cycle.symbol,
                TradingCycle.direction == opposite_direction,
                TradingCycle.status.in_(ACTIVE_CYCLE_STATUSES),
            )
            .first()
        )

        if existing:
            cycle.opposite_triggered = True
            cycle.opposite_cycle_id = existing.id
            db.commit()

            self._record_event(
                db,
                cycle,
                adverse_percent,
                "OPPOSITE_SKIPPED",
                "SKIPPED",
                "An opposite-direction cycle already exists.",
            )

            return {
                "triggered": False,
                "message": "Opposite cycle already active.",
                "opposite_cycle_id": existing.id,
            }

        active_count = (
            db.query(TradingCycle)
            .filter(
                TradingCycle.symbol == cycle.symbol,
                TradingCycle.status.in_(ACTIVE_CYCLE_STATUSES),
            )
            .count()
        )

        if active_count >= settings.MAX_ACTIVE_CYCLES_PER_SYMBOL:
            cycle.opposite_triggered = True
            db.commit()

            self._record_event(
                db,
                cycle,
                adverse_percent,
                "OPPOSITE_SKIPPED",
                "SKIPPED",
                "Maximum active cycles limit reached.",
            )

            return {
                "triggered": False,
                "message": "Maximum active cycles limit reached.",
            }

        opposite_cycle = TradingCycle(
            symbol=cycle.symbol,
            direction=opposite_direction,
            status="ACTIVE",
            initial_margin=symbol_settings["opposite_margin"],
            averaging_margin=symbol_settings["averaging_margin"],
            opposite_margin=symbol_settings["opposite_margin"],
            leverage=symbol_settings["leverage"],
            tp_percent=symbol_settings["tp_percent"],
            sl_percent=symbol_settings["sl_percent"],
            warning_percent=symbol_settings["warning_percent"],
            opposite_percent=symbol_settings["opposite_percent"],
            averaging_percent=symbol_settings["averaging_percent"],
            reentry_percent=symbol_settings["reentry_percent"],
        )

        try:
            db.add(opposite_cycle)
            db.flush()

            # پہلے cycle محفوظ کیا جاتا ہے تاکہ اس کا ID دستیاب ہو۔
            db.commit()
            db.refresh(opposite_cycle)

            cycle.opposite_triggered = True
            cycle.opposite_cycle_id = opposite_cycle.id
            db.commit()

            result = automated_entry_manager.place_entry(
                db=db,
                cycle=opposite_cycle,
                entry_type="OPPOSITE",
                margin=opposite_cycle.initial_margin,
            )

            self._record_event(
                db,
                cycle,
                adverse_percent,
                "OPPOSITE_ORDER",
                (result or {}).get("status", "SUBMITTED"),
                f"Opposite {opposite_direction} cycle created.",
            )

            return {
                "triggered": True,
                "opposite_cycle_id": opposite_cycle.id,
                "result": result,
            }

        except Exception as error:
            db.rollback()

            saved_cycle = (
                db.query(TradingCycle)
                .filter(
                    TradingCycle.id == opposite_cycle.id
                )
                .first()
            )

            saved_order = (
                db.query(CycleOrder)
                .filter(
                    CycleOrder.cycle_id == opposite_cycle.id
                )
                .first()
            )

            saved_entry = (
                db.query(CycleEntry)
                .filter(
                    CycleEntry.cycle_id == opposite_cycle.id
                )
                .first()
            )

            request_may_exist = bool(saved_order or saved_entry)

            if saved_cycle:
                saved_cycle.status = (
                    "ORDER_UNCONFIRMED"
                    if request_may_exist
                    else "ENTRY_FAILED"
                )
                db.commit()

            # اس threshold پر خودکار دوسری کوشش نہیں ہوگی۔
            saved_original = (
                db.query(TradingCycle)
                .filter(TradingCycle.id == cycle.id)
                .first()
            )

            if saved_original:
                saved_original.opposite_triggered = True
                saved_original.opposite_cycle_id = opposite_cycle.id
                db.commit()

            event_status = (
                "UNKNOWN"
                if request_may_exist
                else "FAILED_PRECHECK"
            )

            event_message = (
                "Opposite order could not be confirmed. "
                "Exchange reconciliation is required."
                if request_may_exist
                else (
                    "Opposite entry failed before an exchange order "
                    "was recorded. Automatic retry is blocked."
                )
            )

            self._record_event(
                db,
                cycle,
                adverse_percent,
                "OPPOSITE_ORDER",
                event_status,
                event_message,
            )

            logger.exception(
                "Opposite-position processing failed for cycle %s.",
                cycle.id,
            )

            return {
                "triggered": False,
                "opposite_cycle_id": opposite_cycle.id,
                "status": event_status,
                "message": event_message,
                "error": str(error),
            }

    @staticmethod
    def _record_event(
        db,
        cycle,
        adverse_percent,
        event_type,
        status,
        message,
    ):

        if event_type == "OPPOSITE_SKIPPED":
            key = f"cycle-{cycle.id}-opposite-skipped"
        else:
            key = f"cycle-{cycle.id}-opposite-order"

        existing = (
            db.query(CycleEvent)
            .filter(
                CycleEvent.idempotency_key == key
            )
            .first()
        )

        if existing:
            return

        event = CycleEvent(
            cycle_id=cycle.id,
            symbol=cycle.symbol,
            event_type=event_type,
            price=cycle.average_entry_price,
            percentage=adverse_percent,
            status=status,
            message=message,
            idempotency_key=key,
        )

        db.add(event)
        db.commit()


opposite_position_manager = OppositePositionManager()
