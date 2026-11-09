from unittest.mock import patch

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from database import Base
from models import CycleOrder, TradingCycle
from averaging_manager import averaging_manager


def make_session():
    engine = create_engine("sqlite:///:memory:", future=True)
    Base.metadata.create_all(bind=engine)
    Session = sessionmaker(bind=engine, future=True)
    return Session()


def test_averaging_exception_treats_persisted_client_order_as_unknown():
    db = make_session()
    try:
        cycle = TradingCycle(
            symbol="BTCUSDT",
            direction="LONG",
            status="ACTIVE",
            initial_margin=2.0,
            averaging_margin=2.0,
            opposite_margin=2.0,
            leverage=6,
            tp_percent=3.0,
            sl_percent=12.0,
            warning_percent=6.0,
            opposite_percent=7.0,
            averaging_percent=9.0,
            reentry_percent=1.0,
            averaging_triggered=False,
        )
        db.add(cycle)
        db.commit()
        db.refresh(cycle)

        def place_entry_persists_then_fails(db, cycle, entry_type, margin):
            # Simulate automated_entry_manager persisting the averaging order
            # (PENDING reality) before exchange uncertainty/exception occurs.
            # The pending-order guard has already passed at this point.
            side = "buy" if cycle.direction == "LONG" else "sell"
            db.add(
                CycleOrder(
                    cycle_id=cycle.id,
                    entry_id=None,
                    symbol=cycle.symbol,
                    order_type="MARKET",
                    side=side,
                    status="PENDING",
                    requested_quantity=0.01,
                    filled_quantity=0.0,
                    client_order_id=f"bot{cycle.id}A",
                    reduce_position=False,
                )
            )
            db.commit()
            raise RuntimeError("exchange uncertain")

        with patch(
            "averaging_manager.automated_entry_manager.place_entry",
            side_effect=place_entry_persists_then_fails,
        ):
            result = averaging_manager.process_cycle(db, cycle, adverse_percent=10.0)

        assert result["triggered"] is False
        assert result["status"] == "UNKNOWN"
        assert "Reconciliation" in result["message"] or "reconciliation" in result["message"].lower()

        db.refresh(cycle)
        assert cycle.averaging_triggered is True

        orders = (
            db.query(CycleOrder)
            .filter(
                CycleOrder.cycle_id == cycle.id,
                CycleOrder.client_order_id == f"bot{cycle.id}A",
            )
            .all()
        )
        assert len(orders) == 1
        assert orders[0].client_order_id == f"bot{cycle.id}A"
        assert orders[0].order_type == "MARKET"
        # Persisted order reality remains PENDING in DB; recovery treats the
        # submission outcome as UNKNOWN and blocks retry/reconciliation is required.
        assert orders[0].status == "PENDING"
    finally:
        db.close()
