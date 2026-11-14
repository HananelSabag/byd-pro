"""The car snapshot the app reads, and the holds that keep a confirmed command visible.

Holds: right after a confirmed command the BYD cloud often still reports the old value (the lock
state not yet pushed, the A/C still spinning up). A confirmed effect is therefore held against
the car's reports until one of these happens:

  • a report shows the held value (the car caught up): the hold ends
  • a realtime report made by the car after the confirmation (its own timestamp, HOLD_MARGIN_S
    later) says otherwise: the car's newer state wins (e.g. locked with the key fob)
  • a report without a car timestamp (A/C, schedule) says otherwise after the grace window
    (CLIMATE_HOLD_S / HOLD_GRACE_S): it wins
  • COMMAND_HOLD_S has passed: the hold ends whatever happens
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

from pybyd.models.hvac import HvacOverallStatus, HvacWindPosition
from pybyd.models.realtime import (
    AirCirculationMode,
    DoorOpenState,
    OnlineState,
    PowerGear,
    SeatHeatVentState,
    TirePressureUnit,
    WindowState,
)

from . import config, obs

SEAT_LEVEL = {SeatHeatVentState.OFF: 0, SeatHeatVentState.LOW: 1, SeatHeatVentState.HIGH: 2}

# What a successful command tells us about the car, applied to the snapshot at once
# so the app and widgets show the new state without an extra round-trip.
COMMAND_EFFECTS: dict[str, dict[str, Any]] = {
    "lock": {"locked": True},
    "unlock": {"locked": False},
    "climate_off": {"climate_on": False},
    "trunk_open": {"trunk_open": True},
    "trunk_close": {"trunk_open": False},
    "windows_close": {"windows_open": False, "windows": dict.fromkeys(("lf", "rf", "lr", "rr"), False)},
    "windows_vent": {"windows_open": True, "windows": dict.fromkeys(("lf", "rf", "lr", "rr"), True)},
    "charge_now": {"charging": True},
}

CLIMATE_KEYS = {"climate_on", "recirc", "climate_set_c"}  # held with the A/C grace window

CAPABILITY_KEYS = (
    "lock", "unlock", "climate", "find_car", "flash_lights",
    "open_trunk", "close_trunk", "close_windows", "open_windows",
    "driver_seat_heat", "passenger_seat_heat", "steering_wheel_heat", "start_charge",
)  # fmt: skip


# =============================================================================
# pybyd objects → the small JSON the app reads
# =============================================================================


def _known(value: Any) -> bool:
    """pybyd enums use UNKNOWN = -1 for 'no data'."""
    return value is not None and int(value) >= 0


def _rounded(value: float | None, digits: int = 0) -> float | int | None:
    if value is None:
        return None
    return round(value) if digits == 0 else round(value, digits)


def _seats(source: Any) -> dict[str, int]:
    levels = {
        "driver": SEAT_LEVEL.get(getattr(source, "main_seat_heat_state", None)),
        "passenger": SEAT_LEVEL.get(getattr(source, "copilot_seat_heat_state", None)),
    }
    return {seat: level for seat, level in levels.items() if level is not None}


def snapshot_from_realtime(rt: Any) -> dict[str, Any]:
    """Flatten a pybyd VehicleRealtimeData into the small JSON the app reads.

    Only keys with real values are returned, so a partial MQTT push can be
    merged into the cached snapshot without wiping fields it did not carry.
    """
    doors = {"lf": rt.left_front_door, "rf": rt.right_front_door, "lr": rt.left_rear_door, "rr": rt.right_rear_door}
    windows = {"lf": rt.left_front_window, "rf": rt.right_front_window, "lr": rt.left_rear_window, "rr": rt.right_rear_window}
    tires = {
        "fl": rt.left_front_tire_pressure,
        "fr": rt.right_front_tire_pressure,
        "rl": rt.left_rear_tire_pressure,
        "rr": rt.right_rear_tire_pressure,
    }
    tire_unit = {TirePressureUnit.BAR: "bar", TirePressureUnit.PSI: "psi", TirePressureUnit.KPA: "kPa"}

    charge_eta = rt.time_to_full_minutes
    if charge_eta is None and rt.remaining_hours is not None and rt.remaining_minutes is not None:
        charge_eta = rt.remaining_hours * 60 + rt.remaining_minutes

    efficiency = rt.nearest_energy_consumption_ev or rt.energy_consumption_ev
    known_doors = {k: v == DoorOpenState.OPEN for k, v in doors.items() if _known(v)}
    known_windows = {k: v == WindowState.OPEN for k, v in windows.items() if _known(v)}
    snap: dict[str, Any] = {
        "soc": _rounded(rt.elec_percent),
        "range_km": _rounded(rt.endurance_mileage if rt.endurance_mileage is not None else rt.ev_endurance),
        "odometer_km": _rounded(rt.total_mileage_v2 if rt.total_mileage_v2 is not None else rt.total_mileage),
        "cabin_c": _rounded(rt.temp_in_car),
        "locked": rt.is_locked,
        "doors_open": any(known_doors.values()) if known_doors else None,
        "doors": known_doors or None,
        "trunk_open": (rt.trunk_lid == DoorOpenState.OPEN) if _known(rt.trunk_lid) else None,
        "windows_open": rt.is_any_window_open if known_windows else None,
        "windows": known_windows or None,
        "charging": rt.is_charging if _known(rt.charge_state) else None,
        "plugged": rt.is_charger_connected if _known(rt.charge_state) else None,
        "charge_eta_min": charge_eta if rt.is_charging else None,
        # 0 when not charging, so an old reading never outlives the charge.
        "charge_kw": _charge_kw(rt.gl) if rt.is_charging else 0 if _known(rt.charge_state) else None,
        "car_on": (rt.power_gear == PowerGear.ON) if _known(rt.power_gear) else None,
        "online": (rt.online_state == OnlineState.ONLINE) if _known(rt.online_state) else None,
        "efficiency_kwh100": _rounded(efficiency, 1) if efficiency and efficiency > 0 else None,
        "seat_heat": _seats(rt) or None,
        "data_ts": int(rt.timestamp.timestamp()) if rt.timestamp else None,
    }
    if any(v and v > 0 for v in tires.values()):
        sample = max(v or 0 for v in tires.values())
        guessed_unit = "kPa" if sample > 100 else "psi" if sample > 10 else "bar"
        snap["tires"] = {k: _rounded(v, 1) if v and v > 0 else None for k, v in tires.items()}
        snap["tires"]["unit"] = tire_unit.get(rt.tire_press_unit, guessed_unit)
    return {k: v for k, v in snap.items() if v is not None}


def _charge_kw(watts: float | None) -> float:
    """Charging power from the battery's instantaneous power (`gl`, W; its sign is not confirmed).
    A value this small can only already be kW: the ATTO 3 charges at up to ~90 kW."""
    value = abs(watts or 0)
    return round(value / 1000 if value > 200 else value, 1)


def snapshot_from_hvac(hvac: Any) -> dict[str, Any]:
    snap: dict[str, Any] = {}
    if _known(hvac.status):
        # The overall status alone has shown "on" while the car's own app said off. A fan at
        # position 0 means nothing is blowing, so that counts as off.
        fan_off = _known(hvac.wind_position) and hvac.wind_position == HvacWindPosition.OFF
        snap["climate_on"] = hvac.status == HvacOverallStatus.ON and not fan_off
    if hvac.cycle_choice in (AirCirculationMode.EXTERNAL, AirCirculationMode.INTERNAL):
        snap["recirc"] = hvac.cycle_choice == AirCirculationMode.INTERNAL
    if hvac.main_setting_temp_new and 10 <= hvac.main_setting_temp_new <= 35:
        snap["climate_set_c"] = _rounded(hvac.main_setting_temp_new)  # tells heating from cooling
    if hvac.temp_out_car is not None:
        snap["outside_c"] = _rounded(hvac.temp_out_car)
    if hvac.temp_in_car is not None:
        snap["cabin_c"] = _rounded(hvac.temp_in_car)
    if seats := _seats(hvac):
        snap["seat_heat"] = seats
    return snap


def snapshot_from_schedule(schedule: Any) -> dict[str, Any]:
    charge = getattr(schedule, "charge", None)
    if charge is None:
        return {}
    hhmm = lambda t: t.strftime("%H:%M") if t else None  # noqa: E731
    return {
        "schedule": {
            "enabled": charge.status is True,
            "start": hhmm(charge.start_time),
            "end": "full" if charge.charge_until_full else hhmm(charge.end_time),
        }
    }


def has_data(raw: dict[str, Any]) -> bool:
    """True if a realtime report carries real values (an asleep car may send an empty one)."""
    keys = ("time", "enduranceMileage", "elecPercent", "leftFrontTirepressure", "rightFrontTirepressure")
    return any(float(raw.get(key) or 0) > 0 for key in keys)


def describe(car: dict[str, Any]) -> str:
    """One human line for the logs: what the car looks like right now."""
    parts = [
        f"{car['soc']}%" if "soc" in car else None,
        f"{car['range_km']} km" if "range_km" in car else None,
        {True: "locked", False: "UNLOCKED"}.get(car.get("locked")),
        "door OPEN" if car.get("doors_open") else None,
        "trunk OPEN" if car.get("trunk_open") else None,
        "A/C on" if car.get("climate_on") else None,
        (f"charging {car['charge_kw']} kW" if car.get("charge_kw") else "charging") if car.get("charging") else None,
    ]
    return " · ".join(p for p in parts if p)


def describe_hvac(hvac: Any) -> str:
    """The raw A/C fields, for the logs: what to compare when the app and the car disagree."""
    names = ("status", "ac_switch", "air_conditioning_mode", "wind_position", "wind_mode", "cycle_choice")
    return " ".join(f"{name}={_raw(getattr(hvac, name, None))}" for name in names)


def _raw(value: Any) -> Any:
    return int(value) if isinstance(value, int) else value


# =============================================================================
# The snapshot and its holds
# =============================================================================


@dataclass
class Hold:
    value: Any
    confirmed_at: float  # time.time() when the car confirmed the command
    grace_until: float  # reports without a car timestamp can't overrule it before this
    expires: float  # never held past this
    job: str | None = None


class CarState:
    """The merged snapshot: realtime + A/C + schedule + pushes + confirmed command effects."""

    def __init__(self) -> None:
        self.car: dict[str, Any] = {}
        self.rev = 0  # bumped on every visible change; replies carry it so clients can order them
        self.car_updated = 0.0  # time.time() of the last change to self.car
        self.read_at = 0.0  # time.time() of the last real report from the car (read or push)
        self.climate_until = 0.0  # time.time() when the A/C we started runs out
        self.climate_temp: float | None = None  # what the A/C we started is set to
        self.last_hvac = ""  # raw A/C fields of the last read, for /info
        self.last_raw: dict[str, Any] = {}  # untouched realtime payload, for ?debug=1
        self.realtime: Any = None  # last pybyd objects, needed to build seat commands
        self.hvac: Any = None
        self.holds: dict[str, Hold] = {}

    # ------------------------------------------------------------------ writes

    def _set(self, changes: dict[str, Any]) -> None:
        changed = {k: v for k, v in changes.items() if self.car.get(k, _MISSING) != v}
        if changed:
            self.car.update(changed)
            self.car_updated = time.time()
            self.rev += 1

    def apply_confirmed(self, effects: dict[str, Any], *, job: str | None = None) -> None:
        """A command the car confirmed: show its effect now and hold it against stale reports."""
        now = time.time()
        for key, value in effects.items():
            grace = config.CLIMATE_HOLD_S if key in CLIMATE_KEYS else config.HOLD_GRACE_S
            self.holds[key] = Hold(value, now, now + grace, now + config.COMMAND_HOLD_S, job)
        self._set(effects)

    def release(self, key: str) -> None:
        self.holds.pop(key, None)

    def apply_report(self, changes: dict[str, Any], *, source: str, data_ts: int | None = None) -> None:
        """A report from the car (read or push). `data_ts` is the car's own timestamp, if any."""
        now = time.time()
        accepted: dict[str, Any] = {}
        for key, value in changes.items():
            hold = self.holds.get(key)
            if hold is None:
                accepted[key] = value
                continue
            if now >= hold.expires:
                self.holds.pop(key, None)
                accepted[key] = value
                obs.event("hold ended", key=key, reason="max_age", held_by=hold.job)
            elif value == hold.value:
                self.holds.pop(key, None)  # the car caught up
                accepted[key] = value
            elif data_ts is not None and data_ts >= hold.confirmed_at + config.HOLD_MARGIN_S:
                self.holds.pop(key, None)
                accepted[key] = value
                obs.event("hold released", key=key, reason="newer_car_report", source=source, held_by=hold.job,
                          report_age_s=round(data_ts - hold.confirmed_at, 1))  # fmt: skip
            elif data_ts is None and now >= hold.grace_until:
                self.holds.pop(key, None)
                accepted[key] = value
                obs.event("hold released", key=key, reason="report_after_grace", source=source, held_by=hold.job)
            # otherwise: an older report (or one inside the grace window) — the confirmed effect stays
        self._set(accepted)

    def set_climate(self, until: float, temp: float | None = None) -> None:
        if until != self.climate_until or (temp is not None and temp != self.climate_temp):
            self.rev += 1
        self.climate_until = until
        if temp is not None:
            self.climate_temp = temp

    # ------------------------------------------------------------------ reads

    @property
    def read_age(self) -> float:
        """Seconds since the car itself last reported (command effects don't count)."""
        return time.time() - self.read_at if self.read_at else float("inf")

    @property
    def data_ts(self) -> int | None:
        return self.car.get("data_ts")

    def view(self) -> dict[str, Any]:
        """The snapshot as clients should see it. An A/C we started is reported off once its run
        time is over, unless the car said otherwise since (no need to wake the car to know)."""
        car = self.car
        if not (car.get("climate_on") and self.climate_until):
            return car
        if time.time() < self.climate_until:
            return {**car, "climate_until": int(self.climate_until)}
        if self.read_at < self.climate_until:
            return {**car, "climate_on": False}
        return car  # the car itself said "on" after the run should have ended (restarted elsewhere)

    def holds_info(self) -> dict[str, Any]:
        now = time.time()
        return {
            key: {"value": hold.value, "age_s": round(now - hold.confirmed_at, 1), "job": hold.job}
            for key, hold in self.holds.items()
            if now < hold.expires
        }


_MISSING = object()
