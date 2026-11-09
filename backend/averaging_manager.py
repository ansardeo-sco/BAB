import logging

from models import CycleEntry, CycleOrder, CycleEvent
from automated_entry_manager import automated_entry_manager


logger = logging.getLogger(__name__)

OPEN_ORDER_STATUSES = [
    "PENDING",
    "PARTIALLY_FILLED",
    "UNKNOWN",
]


class AveragingManager:

    def process_cycle(self, db, cycle, adverse_percent):

        if cycle.status != "ACTIVE":
            return {
                "triggered": False,
                "message": "Cycle is not active.",
            }

        if cycle.averaging_triggered:
            return {
                "triggered": False,
                "message": "Averaging already triggered.",
            }

        try:
            adverse_percent = float(adverse_percent)
            threshold = float(cycle.averaging_percent)
            margin = float(cycle.averaging_margin)
        except (TypeError, ValueError):
            return {
                "triggered": False,
                "message": "Invalid averaging settings or percentage.",
            }

        if adverse_percent < threshold:
            return {"triggered": False}

        if margin <= 0:
            return {
                "triggered": False,
                "message": "Invalid averaging margin.",
            }

        threshold_key = f"cycle-{cycle.id}-averaging-threshold"
        order_key = f"cycle-{cycle.id}-averaging-order"

        threshold_event = (
            db.query(CycleEvent)
            .filter(
                CycleEvent.idempotency_key == threshold_key
            )
            .first()
        )

        if not threshold_event:
            threshold_event = CycleEvent(
                cycle_id=cycle.id,
                symbol=cycle.symbol,
                event_type="AVERAGING_THRESHOLD",
                price=cycle.average_entry_price,
                percentage=adverse_percent,
                status="WAITING",
                message=(
                    "Averaging threshold reached. "
                    "Waiting for pending orders to clear."
                ),
                idempotency_key=threshold_key,
            )

            db.add(threshold_event)
            db.commit()
            db.refresh(threshold_event)

        # اگر Averaging کی Entry پہلے سے موجود ہے تو دوبارہ آرڈر نہ بھیجیں۔
        existing_entry = (
            db.query(CycleEntry)
            .filter(
                CycleEntry.cycle_id == cycle.id,
                CycleEntry.entry_type == "AVERAGING",
            )
            .first()
        )

        if existing_entry:
            cycle.averaging_triggered = True
            threshold_event.status = "ORDER_EXISTS"
            threshold_event.message = (
                "An averaging entry already exists. "
                "Duplicate submission blocked."
            )
            db.commit()

            return {
                "triggered": False,
                "message": "Averaging entry already exists.",
            }

        # اگر آرڈر کا ریکارڈ موجود ہے تو دوبارہ آرڈر نہیں بھیجنا۔
        existing_order_event = (
            db.query(CycleEvent)
            .filter(
                CycleEvent.idempotency_key == order_key
            )
            .first()
        )

        if existing_order_event:
            cycle.averaging_triggered = True
            db.commit()

            return {
                "triggered": False,
                "message": (
                    "Averaging order is already recorded. "
                    "Exchange reconciliation is required."
                ),
            }

        # کسی بھی غیر مصدقہ آرڈر کے دوران نیا آرڈر نہیں لگے گا۔
        pending_order = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.status.in_(OPEN_ORDER_STATUSES),
            )
            .first()
        )

        if pending_order:
            threshold_event.status = "WAITING"
            threshold_event.message = (
                "Averaging threshold reached. "
                "Waiting for the pending order to be confirmed."
            )
            db.commit()

            return {
                "triggered": False,
                "message": "Averaging is waiting for order confirmation.",
            }

        try:
            result = automated_entry_manager.place_entry(
                db=db,
                cycle=cycle,
                entry_type="AVERAGING",
                margin=margin,
            )

            # آرڈر بھیجنے کے بعد اسی سائیکل میں دوبارہ Averaging نہیں ہوگی۔
            cycle.averaging_triggered = True

            threshold_event.status = "SUBMITTED"
            threshold_event.message = "Averaging order submitted."

            db.commit()

            self._record_order_event(
                db=db,
                cycle=cycle,
                adverse_percent=adverse_percent,
                status=(result or {}).get("status", "SUBMITTED"),
                message="Averaging order submitted.",
            )

            return {
                "triggered": True,
                "result": result,
            }

        except Exception as error:

            db.rollback()

            # تازہ DB ریکارڈ دوبارہ حاصل کریں۔
            saved_entry = (
                db.query(CycleEntry)
                .filter(
                    CycleEntry.cycle_id == cycle.id,
                    CycleEntry.entry_type == "AVERAGING",
                )
                .first()
            )

            saved_order = (
                db.query(CycleOrder)
                .filter(
                    CycleOrder.cycle_id == cycle.id,
                    CycleOrder.client_order_id == f"bot{cycle.id}A",
                )
                .first()
            )

            threshold_event = (
                db.query(CycleEvent)
                .filter(
                    CycleEvent.idempotency_key == threshold_key
                )
                .first()
            )

            # Entry یا Order موجود ہونے کا مطلب ہے کہ
            # Exchange تک درخواست پہنچنے کا امکان رد نہیں کیا جا سکتا۔
            request_may_exist = bool(saved_entry or saved_order)

            if request_may_exist:

                cycle.averaging_triggered = True

                if threshold_event:
                    threshold_event.status = "UNKNOWN"
                    threshold_event.message = (
                        "Averaging order status is uncertain. "
                        "Reconciliation is required. "
                        "Automatic retry is blocked."
                    )

                db.commit()

                self._record_order_event(
                    db=db,
                    cycle=cycle,
                    adverse_percent=adverse_percent,
                    status="UNKNOWN",
                    message=(
                        "Averaging order requires exchange reconciliation."
                    ),
                )

                logger.error(
                    "Averaging order status unknown for cycle %s: %s",
                    cycle.id,
                    error,
                )

                return {
                    "triggered": False,
                    "status": "UNKNOWN",
                    "message": (
                        "Averaging order status is uncertain. "
                        "Reconciliation is required."
                    ),
                }

            # اگر کوئی Order یا Entry ریکارڈ نہیں بنا تو
            # اسے ناکام سمجھیں؛ خودکار طور پر دوبارہ آرڈر نہیں بھیجیں۔
            if threshold_event:
                threshold_event.status = "ERROR"
                threshold_event.message = (
                    f"Averaging submission failed: {error}"
                )

            db.commit()

            logger.exception(
                "Averaging submission failed for cycle %s.",
                cycle.id,
            )

            return {
                "triggered": False,
                "status": "ERROR",
                "message": str(error),
            }

    @staticmethod
    def _record_order_event(
        db,
        cycle,
        adverse_percent,
        status,
        message,
    ):

        key = f"cycle-{cycle.id}-averaging-order"

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
            event_type="AVERAGING_ORDER",
            price=cycle.average_entry_price,
            percentage=adverse_percent,
            status=status,
            message=message,
            idempotency_key=key,
        )

        db.add(event)
        db.commit()


averaging_manager = AveragingManager()
