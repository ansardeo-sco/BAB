from types import SimpleNamespace
from unittest.mock import patch

import pytest

from config import settings as app_settings
from automated_entry_manager import automated_entry_manager


class FakeQuery:
    def __init__(self, result=None):
        self._result = result

    def filter(self, *args, **kwargs):
        return self

    def first(self):
        return self._result


class FakeDB:
    def __init__(self, existing_entry=None, duplicate_order=None):
        self._existing_entry = existing_entry
        self._duplicate_order = duplicate_order

    def query(self, model):
        name = getattr(model, "__name__", "")
        if name == "CycleEntry":
            return FakeQuery(self._existing_entry)
        if name == "CycleOrder":
            return FakeQuery(self._duplicate_order)
        return FakeQuery(None)

    def add(self, obj):
        pass

    def flush(self):
        pass

    def commit(self):
        pass

    def rollback(self):
        pass


def _active_cycle(cycle_id, direction="LONG"):
    return SimpleNamespace(
        id=cycle_id,
        status="ACTIVE",
        symbol="BTCUSDT",
        direction=direction,
        leverage=6,
        sl_percent=12.0,
    )


def _enabled_settings():
    return {
        "enabled_long": True,
        "enabled_short": True,
    }


def test_opposite_entry_checks_cycle_direction_enabled():
    cycle = _active_cycle(1, direction="SHORT")
    db = FakeDB()
    symbol_settings = {
        "enabled_long": True,
        "enabled_short": False,
    }

    with patch(
        "automated_entry_manager.settings_manager.get_symbol_settings",
        return_value=symbol_settings,
    ):
        with pytest.raises(RuntimeError, match="SHORT trading is disabled"):
            automated_entry_manager.place_entry(
                db=db,
                cycle=cycle,
                entry_type="OPPOSITE",
                margin=2.0,
            )


def test_opposite_entry_allows_enabled_cycle_direction():
    cycle = _active_cycle(2, direction="LONG")
    db = FakeDB()

    with patch(
        "automated_entry_manager.settings_manager.get_symbol_settings",
        return_value=_enabled_settings(),
    ), patch.object(
        app_settings, "BINANCE_API_KEY", "test-api-key"
    ), patch.object(
        app_settings, "BINANCE_API_SECRET", "test-api-secret"
    ), patch(
        "automated_entry_manager.binance_client.prepare_symbol_for_order",
        return_value="BTC/USDT:USDT",
    ), patch(
        "automated_entry_manager.binance_client._fetch_mark_price_sync",
        return_value=50000.0,
    ), patch(
        "automated_entry_manager.binance_client.format_order_quantity",
        return_value=0.001,
    ), patch(
        "automated_entry_manager.binance_client.exchange.create_order",
        return_value={
            "id": "123",
            "status": "closed",
            "filled": 0.001,
            "average": 50000.0,
        },
    ), patch(
        "automated_entry_manager.binance_client.exchange.fetch_order",
        return_value={
            "id": "123",
            "status": "closed",
            "filled": 0.001,
            "average": 50000.0,
        },
    ):
        result = automated_entry_manager.place_entry(
            db=db,
            cycle=cycle,
            entry_type="OPPOSITE",
            margin=2.0,
        )

    assert result["success"] is True
    assert result["entry_type"] == "OPPOSITE"


def test_duplicate_existing_entry_prevented():
    cycle = _active_cycle(3)
    db = FakeDB(existing_entry=SimpleNamespace(id=99))

    with patch(
        "automated_entry_manager.settings_manager.get_symbol_settings",
        return_value=_enabled_settings(),
    ):
        with pytest.raises(RuntimeError, match="already exists"):
            automated_entry_manager.place_entry(
                db=db,
                cycle=cycle,
                entry_type="AVERAGING",
                margin=2.0,
            )


def test_duplicate_client_order_id_prevented():
    cycle = _active_cycle(4)
    db = FakeDB(
        existing_entry=None,
        duplicate_order=SimpleNamespace(id=77),
    )

    with patch(
        "automated_entry_manager.settings_manager.get_symbol_settings",
        return_value=_enabled_settings(),
    ):
        with pytest.raises(
            RuntimeError,
            match="automated order ID already exists",
        ):
            automated_entry_manager.place_entry(
                db=db,
                cycle=cycle,
                entry_type="AVERAGING",
                margin=2.0,
            )
