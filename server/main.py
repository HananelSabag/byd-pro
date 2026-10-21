"""
BYD Cloud Bridge: the Render server behind the BYD app and its widgets.

    phone ──HTTPS──▶ this server ──▶ BYD cloud (pybyd) ──▶ car

Design rules (speed without draining the phone or the car):
  • One warm BYD session for the whole process. Login, vehicle lookup, PIN check
    and capability fetch happen once at boot, never on a button press.
  • The server never polls the car. The car is only woken when someone asks for
    something. Status answers come from a short cache, plus the MQTT pushes the
    car sends on its own (free).
  • Commands run one at a time and duplicate taps are merged, so a double tap
    can never send the same command twice.
  • /health is what cron-job.org pings to keep the free Render instance awake.
    It also keeps the BYD login fresh (cloud only, it never touches the car).

Diagnostics (add ?secret=… in a browser):
  /logs   the last few hundred log lines, ready to copy and share
  /info   session, push channel, timings and capabilities as JSON

Run locally:  python main.py        (listens on $PORT, default 10000)
"""

from __future__ import annotations

import asyncio
import collections
import functools
import hashlib
import hmac
import json
import logging
import os
import sys
import time
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from aiohttp import web
from pybyd import (
    VALID_CLIMATE_DURATIONS,
    BydApiError,
    BydAuthenticationError,
    BydClient,
    BydConfig,
    BydControlPasswordError,
    BydEndpointNotSupportedError,
    BydRateLimitError,
    BydRemoteControlError,
    BydSessionExpiredError,
    BydTransportError,
    SeatLevel,
    minutes_to_time_span,
)
from pybyd import __version__ as PYBYD_VERSION
from pybyd._api import control as _byd_control
from pybyd._constants import DEFAULT_TEMP_OFFSET_C
from pybyd.models import ClimateStartParams
from pybyd.models.control import SeatClimateParams
from pybyd.models.hvac import HvacOverallStatus
from pybyd.models.realtime import DoorOpenState, OnlineState, PowerGear, SeatHeatVentState, TirePressureUnit, WindowState

# =============================================================================
# SETTINGS: everything you may want to change lives here
# Secrets come from the environment (Render: Environment tab; locally: export them).
# =============================================================================

BYD_USERNAME = os.environ.get("BYD_USERNAME", "")  # BYD app account (email)
BYD_PASSWORD = os.environ.get("BYD_PASSWORD", "")
BYD_CONTROL_PIN = os.environ.get("BYD_CONTROL_PIN", "")  # 6-digit PIN from the BYD app
AUTH_SECRET = os.environ.get("AUTH_SECRET", "")  # must match the app
CAR_TIMEZONE = ZoneInfo(os.environ.get("CAR_TIMEZONE", "Asia/Jerusalem"))  # "charge now until …" uses car time

STATUS_CACHE_S = 60  # /status is answered from memory while the last real car read is newer than this
TRUNK_STATE_MAX_AGE_S = 15  # trunk-toggle re-reads the car if its last real read is older
COMMAND_TIMEOUT_S = 30  # hard cap for one remote command (sleeping car / no signal)
STATUS_TIMEOUT_S = 15  # hard cap for one live status read
QUEUE_WAIT_S = 10  # a command stuck this long behind another one answers "busy" instead of running late
PAUSE_CHECK_S = 8  # after "pause charging", look at the car again this much later
KEEPALIVE_CHECK_S = 20 * 60  # on /health: re-validate the BYD login after this long idle
COMMAND_HOLD_S = 60  # a confirmed command beats slower car reports for this long (e.g. A/C spin-up)

# Speed: BYD answers through a push channel (MQTT) or, failing that, polling. On this account
# (the official BYD app is signed in too) the push never arrives, so don't wait for it: poll
# straight away. Pushes that do arrive are still used (see BydLink._on_push).
MQTT_WAIT_S = 0
POLL_INTERVAL_S = 0.8
STATUS_POLL_ATTEMPTS = 12  # a status read gives up after ~10 s (car asleep); the last state is served
COMMAND_POLL_ATTEMPTS = 30  # a command waits up to ~24 s for the car's confirmation

# =============================================================================
# LOGGING: one readable line per event, kept in memory for /logs
# =============================================================================

LOG_LINES: collections.deque[str] = collections.deque(maxlen=400)


class _RingHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        LOG_LINES.append(self.format(record))


_formatter = logging.Formatter("%(asctime)s %(levelname)-5s %(message)s", datefmt="%m-%d %H:%M:%S")
_ring = _RingHandler()
_ring.setFormatter(_formatter)
logging.basicConfig(level=logging.INFO, stream=sys.stdout, force=True)
logging.getLogger().handlers[0].setFormatter(_formatter)  # stdout: flushed per line, so Render shows it live
logging.getLogger().addHandler(_ring)
logging.getLogger("pybyd").setLevel(logging.WARNING)
logging.getLogger("pybyd._api.control").setLevel(logging.INFO)  # command refusals show up in /logs
log = logging.getLogger("byd")


# =============================================================================
# BYD: vehicle state
# =============================================================================

SEAT_LEVEL = {SeatHeatVentState.OFF: 0, SeatHeatVentState.LOW: 1, SeatHeatVentState.HIGH: 2}

# pybyd re-sends a remote command up to 3 times when the car's confirmation is slow or it says
# no. That can sound the horn twice and turns one refusal into ~20 s of waiting. Send once,
# check often, and report what the car said. (The client calls this through the module, so
# replacing the module attribute is enough.)
_byd_poll_remote_control = _byd_control.poll_remote_control


async def _send_once(*args: Any, **kwargs: Any) -> Any:
    kwargs.update(command_retries=1, poll_interval=POLL_INTERVAL_S, poll_attempts=COMMAND_POLL_ATTEMPTS)
    return await _byd_poll_remote_control(*args, **kwargs)


_byd_control.poll_remote_control = _send_once


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


def snapshot_from_hvac(hvac: Any) -> dict[str, Any]:
    snap: dict[str, Any] = {}
    if _known(hvac.status):
        snap["climate_on"] = hvac.status == HvacOverallStatus.ON
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


def _has_data(raw: dict[str, Any]) -> bool:
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
        "charging" if car.get("charging") else None,
    ]
    return " · ".join(p for p in parts if p)


# What a successful command tells us about the car, applied to the cache at once
# so the app and widgets show the new state without an extra round-trip.
COMMAND_EFFECTS: dict[str, dict[str, Any]] = {
    "lock": {"locked": True},
    "unlock": {"locked": False},
    "climate_on": {"climate_on": True},
    "climate_off": {"climate_on": False},
    "trunk_open": {"trunk_open": True},
    "trunk_close": {"trunk_open": False},
    "windows_close": {"windows_open": False, "windows": dict.fromkeys(("lf", "rf", "lr", "rr"), False)},
    "windows_vent": {"windows_open": True, "windows": dict.fromkeys(("lf", "rf", "lr", "rr"), True)},
    "charge_now": {"charging": True},
}

CAPABILITY_KEYS = (
    "lock", "unlock", "climate", "find_car", "flash_lights",
    "open_trunk", "close_trunk", "close_windows", "open_windows",
    "driver_seat_heat", "passenger_seat_heat", "steering_wheel_heat", "start_charge",
)  # fmt: skip


class CommandError(Exception):
    """A command failed in a way the app can explain to the user."""

    def __init__(self, code: str, message: str, http_status: int) -> None:
        super().__init__(message)
        self.code = code
        self.http_status = http_status


# =============================================================================
# BYD: cloud link (session, status cache, command queue)
# =============================================================================


class BydLink:
    """Owns the single warm BYD session and everything built on top of it."""

    def __init__(self) -> None:
        self.config = BydConfig(
            username=BYD_USERNAME, password=BYD_PASSWORD, control_pin=BYD_CONTROL_PIN, mqtt_timeout=MQTT_WAIT_S
        )
        self.client: BydClient | None = None
        self.vin: str = ""
        self.car_name: str = "BYD"
        self.commands_ready = False
        self.caps: dict[str, bool | None] = {}

        self.car: dict[str, Any] = {}  # merged snapshot: realtime + hvac + pushes + command effects
        self.car_updated = 0.0  # time.time() of the last change to self.car
        self.read_at = 0.0  # time.time() of the last real report from the car (read or push)
        self.climate_until = 0.0  # time.time() when the A/C we started runs out
        self.last_raw: dict[str, Any] = {}  # untouched realtime payload, for ?debug=1
        self.last_api_ok = 0.0  # time.monotonic() of the last successful BYD call
        self._held: dict[str, float] = {}  # key -> monotonic deadline set by a confirmed command
        self._realtime: Any = None  # last pybyd objects, needed to build seat commands
        self._hvac: Any = None

        # Diagnostics for /info
        self.started_at = time.time()
        self.session_since = 0.0
        self.mqtt_events = 0
        self.mqtt_last_event = 0.0
        self.recent: collections.deque[dict[str, Any]] = collections.deque(maxlen=20)

        self._connect_lock = asyncio.Lock()
        self._command_lock = asyncio.Lock()
        self._inflight: dict[str, asyncio.Task] = {}  # duplicate taps join the running task
        self._status_task: asyncio.Task | None = None
        self._background: set[asyncio.Task] = set()

    # ---------------------------------------------------------------- session

    async def connect(self) -> BydClient:
        """Return the warm client, creating it once if needed."""
        if self.client is not None:
            return self.client
        async with self._connect_lock:
            if self.client is not None:
                return self.client

            started = time.monotonic()
            client = BydClient(self.config, on_vehicle_info=self._on_push, on_mqtt_event=self._on_mqtt_event)
            await client.async_start()
            try:
                vehicles = await client.get_vehicles()  # logs in on first use
                if not vehicles:
                    raise CommandError("no_vehicle", "No vehicle on this BYD account", 502)
                vehicle = vehicles[0]
                self.vin = vehicle.vin
                self.car_name = vehicle.auto_alias or vehicle.model_name or "BYD"
                try:
                    await client.verify_command_access(self.vin)
                    self.commands_ready = True
                except BydControlPasswordError as err:
                    log.error("Control PIN rejected (%s). Commands are disabled; status still works.", err.code)
                await self._load_capabilities(client)
            except BaseException:
                await client.async_close()
                raise

            self.client = client
            self.last_api_ok = time.monotonic()
            self.session_since = time.time()
            supported = [k for k, v in self.caps.items() if v]
            log.info(
                "BYD session ready for %s…%s in %.1fs · supports: %s",
                self.vin[:3], self.vin[-4:], time.monotonic() - started, ", ".join(supported) or "unknown",
            )  # fmt: skip
            return client

    async def _load_capabilities(self, client: BydClient, *, force: bool = False) -> None:
        caps = await client.get_vehicle_capabilities(self.vin, force_refresh=force)
        if caps.source.startswith("unknown"):
            log.warning("Capability list unavailable (%s); will retry on the next command", caps.source)
        self.caps = {key: getattr(caps, key, None) for key in CAPABILITY_KEYS}

    async def reset(self) -> None:
        """Drop the session; the next request builds a fresh one."""
        client, self.client = self.client, None
        self.commands_ready = False
        if client is not None:
            try:
                await client.async_close()
            except Exception:
                pass
        log.info("BYD session cleared")

    def on_health_ping(self) -> None:
        """Called by /health (cron-job.org). Never touches the car."""
        if any(not t.done() for t in self._background):
            return
        if self.client is None:
            self._spawn(self.connect())  # boot warm-up failed earlier: try again quietly
        elif time.monotonic() - self.last_api_ok > KEEPALIVE_CHECK_S:
            self._spawn(self._touch())

    async def _touch(self) -> None:
        """Cheap cloud call that renews the login if it expired."""
        client = await self.connect()
        await client.get_vehicles()
        self.last_api_ok = time.monotonic()
        log.info("Keep-alive: BYD session verified")

    def _spawn(self, coro: Any) -> None:
        task = asyncio.create_task(coro)
        self._background.add(task)
        task.add_done_callback(self._background_done)

    def _background_done(self, task: asyncio.Task) -> None:
        self._background.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.warning("Background task failed: %r", task.exception())

    # ----------------------------------------------------------------- state

    def _merge(self, changes: dict[str, Any], *, confirmed: bool = False) -> None:
        """Fold new values into the snapshot.

        A confirmed command's effect is held for COMMAND_HOLD_S: the car often reports the old
        value for a few seconds (A/C still spinning up, lock state not yet pushed), and that
        stale report must not flip the app's button back.
        """
        now = time.monotonic()
        if confirmed:
            self._held.update({key: now + COMMAND_HOLD_S for key in changes})
        else:
            changes = {k: v for k, v in changes.items() if self._held.get(k, 0.0) < now}
        if changes:
            self.car.update(changes)
            self.car_updated = time.time()

    def _on_push(self, vin: str, realtime: Any) -> None:
        """MQTT 'vehicleInfo' push: the car reported on its own, free update."""
        if vin == self.vin:
            self._realtime = realtime
            self._merge(snapshot_from_realtime(realtime))
            self.read_at = time.time()

    def _on_mqtt_event(self, event: str, vin: str, data: dict[str, Any]) -> None:
        self.mqtt_events += 1
        self.mqtt_last_event = time.time()

    @property
    def car_age(self) -> float:
        return time.time() - self.car_updated if self.car_updated else float("inf")

    @property
    def read_age(self) -> float:
        """Seconds since the car itself last reported (command effects don't count)."""
        return time.time() - self.read_at if self.read_at else float("inf")

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

    async def status(self, *, fresh: bool = False) -> dict[str, Any]:
        """Cached snapshot if recent, otherwise one live read shared by all callers."""
        if not fresh and self.car and self.read_age < STATUS_CACHE_S:
            return self.car
        if self._status_task is None or self._status_task.done():
            self._status_task = asyncio.create_task(self._read_car())
            # Callers may give up early (timeouts); mark the outcome as seen either way.
            self._status_task.add_done_callback(lambda t: t.cancelled() or t.exception())
        try:
            await asyncio.shield(self._status_task)
        except Exception:
            if not self.car:
                raise
            log.warning("Live status failed, serving last known state (%.0fs old)", self.read_age)
        return self.car

    async def _read_car(self) -> None:
        client = await self.connect()
        started = time.monotonic()
        reads = [
            client.get_vehicle_realtime(
                self.vin, mqtt_timeout=MQTT_WAIT_S, poll_interval=POLL_INTERVAL_S, poll_attempts=STATUS_POLL_ATTEMPTS
            ),
            client.get_hvac_status(self.vin),
        ]
        if self.caps.get("start_charge"):
            reads.append(client.get_charging_schedule(self.vin))
        # Run the three reads side by side; whatever finished in time is kept even if one is slow.
        tasks = [asyncio.ensure_future(read) for read in reads]
        try:
            done, pending = await asyncio.wait(tasks, timeout=STATUS_TIMEOUT_S)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
        realtime, hvac, *rest = [
            task.exception() or task.result() if task in done else asyncio.TimeoutError() for task in tasks
        ]
        if not isinstance(hvac, BaseException):
            self._hvac = hvac
            self._merge(snapshot_from_hvac(hvac))
        if rest and not isinstance(rest[0], BaseException):
            self._merge(snapshot_from_schedule(rest[0]))
        if isinstance(realtime, BaseException):
            raise realtime
        self.last_raw = dict(realtime.raw or {})
        if not _has_data(self.last_raw):
            # An asleep car can answer with an empty report; merging it would show 0 % and 0 km.
            raise CommandError("timeout", "Car is asleep: no fresh data", 504)
        self._realtime = realtime
        self._merge(snapshot_from_realtime(realtime))
        self.read_at = time.time()
        self.last_api_ok = time.monotonic()
        log.info("  car read in %.1fs: %s", time.monotonic() - started, describe(self.view()))

    # -------------------------------------------------------------- commands

    async def command(self, action: str, params: dict[str, str]) -> str:
        """Run a command; identical taps arriving while it runs share its result."""
        key = action + "|" + "&".join(f"{k}={v}" for k, v in sorted(params.items()))
        task = self._inflight.get(key)
        if task is None:
            task = asyncio.create_task(self._run_command(action, params))
            self._inflight[key] = task
            task.add_done_callback(lambda _t: self._inflight.pop(key, None))
        return await asyncio.shield(task)

    async def _run_command(self, action: str, params: dict[str, str]) -> str:
        client = await self.connect()
        # BYD rejects overlapping commands (code 6024), so they queue. A command that would wait
        # too long says "busy" instead of reaching the car long after the tap.
        queued = time.monotonic()
        acquire = asyncio.ensure_future(self._command_lock.acquire())
        done, _ = await asyncio.wait({acquire}, timeout=QUEUE_WAIT_S)
        if not done:
            acquire.cancel()
            try:
                await acquire  # it may have got the lock just as it timed out
            except asyncio.CancelledError:
                pass
            else:
                self._command_lock.release()
            raise CommandError("busy", "Another command is still running; try again in a moment", 409)
        waited = time.monotonic() - queued
        if waited > 0.5:
            log.info("  %s waited %.1fs for the previous command", action, waited)
        try:
            action = await self._run_locked(client, action, params)
        finally:
            self._command_lock.release()
        if action == "charge_pause":
            await self._check_paused()  # outside the lock: a Lock right after Pause isn't held up
        return action

    async def _check_paused(self) -> None:
        """The cloud only says "received" to a pause. Look at the car again shortly after: inside
        its charging window it simply carries on, and the owner should hear that."""
        self._held.pop("charging", None)  # let the car's own answer through
        await asyncio.sleep(PAUSE_CHECK_S)
        try:
            await asyncio.wait_for(self.status(fresh=True), STATUS_TIMEOUT_S + 2)
        except Exception as err:
            log.info("  pause check skipped (%r)", err)
            return
        if self.car.get("charging"):
            raise CommandError("still_charging", "Schedule is on, but the car is inside its window and keeps charging", 409)

    async def _run_locked(self, client: BydClient, action: str, params: dict[str, str]) -> str:
        """Run one command; the caller holds the command lock."""
        if action == "trunk_toggle":
            action = await self._resolve_trunk_toggle()
        if not self.commands_ready:
            await client.verify_command_access(self.vin)  # raises if the PIN is wrong
            self.commands_ready = True

        started = time.monotonic()
        try:
            effects = await self._send(client, action, params)
        except BydEndpointNotSupportedError as err:
            # A failed capability fetch makes pybyd block every command; refetch once.
            if err.code != "command_gate_blocked" or self.caps.get("lock") is not None:
                raise
            await self._load_capabilities(client, force=True)
            effects = await self._send(client, action, params)

        self.last_api_ok = time.monotonic()
        self._merge(effects, confirmed=True)
        self.recent.append({"action": action, "ok": True, "s": round(time.monotonic() - started, 1)})
        return action

    async def _send(self, client: BydClient, action: str, params: dict[str, str]) -> dict[str, Any]:
        """Send one command to the car and return what it changed in the snapshot."""
        vin = self.vin

        async def run(call: Any) -> None:
            await asyncio.wait_for(call, COMMAND_TIMEOUT_S)

        simple = {
            "lock": lambda: client.lock(vin),
            "unlock": lambda: client.unlock(vin),
            "climate_off": lambda: client.stop_climate(vin),
            "locate": lambda: client.find_car(vin),
            "flash": lambda: client.flash_lights(vin),
            "trunk_open": lambda: client.open_trunk(vin),
            "trunk_close": lambda: client.close_trunk(vin),
            "windows_close": lambda: client.close_windows(vin),
            "windows_vent": lambda: client.open_windows(vin),
            "charge_now": lambda: client.start_charging(
                vin, mqtt_timeout=MQTT_WAIT_S, poll_interval=POLL_INTERVAL_S, poll_attempts=COMMAND_POLL_ATTEMPTS
            ),
        }
        if action in simple:
            await run(simple[action]())
            if action == "climate_off":
                self.climate_until = 0.0
            return COMMAND_EFFECTS.get(action, {})

        if action == "climate_on":
            start = climate_params(params, getattr(self._hvac, "temp_offset", None))
            await run(client.start_climate(vin, params=start))
            self.climate_until = time.time() + climate_minutes(params) * 60
            effects: dict[str, Any] = {"climate_on": True}
            # The old app sent seat levels with the A/C; keep that working.
            for seat, key in (("driver", "driver_seat"), ("passenger", "passenger_seat")):
                if int(params.get(key, 0) or 0) > 0:
                    effects.update(await self._set_seat(client, seat, int(params[key])))
            return effects

        if action == "seat":
            if "seat" in params:  # one seat: ?seat=driver&level=2
                return await self._set_seat(client, params["seat"], int(params.get("level", 0)))
            effects = {}  # both: ?driver=2&passenger=0 (only seats that actually change are sent)
            for seat in ("driver", "passenger"):
                if seat in params and int(params[seat]) != self.car.get("seat_heat", {}).get(seat):
                    effects.update(await self._set_seat(client, seat, int(params[seat])))
            return effects or {"seat_heat": self.car.get("seat_heat", {})}

        if action == "steering":
            on = params.get("on", "1") in ("1", "true")
            base = await self._seat_base()
            await run(client.set_seat_climate(vin, params=base.with_change("steering_wheel_heat_state", 1 if on else 3)))
            return {"steering_heat": on}

        if action == "charge_schedule":
            return await self._charge_schedule(client, params)

        if action == "charge_pause":
            # The BYD cloud cannot stop a charge directly; re-enabling the schedule makes the car
            # wait for its charging window again (checked afterwards in _check_paused).
            await run(client.toggle_smart_charging(vin, enable=True))
            return {"schedule": {**self.car.get("schedule", {}), "enabled": True}}

        raise CommandError("unknown_action", f"Unknown action: {action}", 400)

    async def _seat_base(self) -> SeatClimateParams:
        """Every seat command must resend all seat levels. Start from the car's last report, then
        apply what we know is newer (levels confirmed by earlier commands), so changing one seat
        never resets another to a stale level."""
        if self._realtime is None and self._hvac is None:
            await self._read_car()
        base = SeatClimateParams.from_current_state(hvac=self._hvac, realtime=self._realtime)
        seats = self.car.get("seat_heat", {})
        known = {
            param: SeatLevel(seats[seat]).to_command_value()
            for seat, param in (("driver", "main_heat"), ("passenger", "copilot_heat"))
            if seat in seats
        }
        return base.model_copy(update=known)

    async def _set_seat(self, client: BydClient, seat: str, level: int) -> dict[str, Any]:
        level = max(0, min(2, level))
        key = "copilot_heat" if seat == "passenger" else "main_heat"
        base = await self._seat_base()
        command = base.with_change(key, SeatLevel(level).to_command_value())
        await asyncio.wait_for(client.set_seat_climate(self.vin, params=command), COMMAND_TIMEOUT_S)
        seats = {**self.car.get("seat_heat", {}), "passenger" if seat == "passenger" else "driver": level}
        effects: dict[str, Any] = {"seat_heat": seats}
        if level > 0:
            effects["climate_on"] = True  # a heated seat switches the car's climate on
        self._merge(effects, confirmed=True)  # visible to the next seat command right away
        return effects

    async def _charge_schedule(self, client: BydClient, params: dict[str, str]) -> dict[str, Any]:
        """mode: window (start→end) · start_only (start→full) · stop_only (now→end). enabled=false turns it off.

        BYD schedules repeat daily ("e"), so stop_only also starts again each day at the time it was
        saved; the app says so. (Single-run "s" exists in pybyd but is untested on the ATTO 3.)
        """
        if "mode" not in params and "enabled" not in params:
            # A bare call used to save "now → full, every day". Ask for intent instead.
            raise CommandError("bad_request", "charge-schedule needs mode=window|start_only|stop_only or enabled=false", 400)
        if params.get("enabled", "true").lower() in ("0", "false", "no"):
            await asyncio.wait_for(client.toggle_smart_charging(self.vin, enable=False), COMMAND_TIMEOUT_S)
            return {"schedule": {**self.car.get("schedule", {}), "enabled": False}}

        mode = params.get("mode", "")
        if mode not in ("window", "start_only", "stop_only"):
            raise CommandError("bad_request", f"Unknown schedule mode {mode!r}", 400)
        now = _hhmm(params.get("now")) or datetime.now(CAR_TIMEZONE).strftime("%H:%M")  # the app sends phone time
        start = now if mode == "stop_only" else (_hhmm(params.get("start")) or now)
        end = "full" if mode == "start_only" else (_hhmm(params.get("end")) or "full")
        await asyncio.wait_for(
            client.save_charging_schedule(
                self.vin, start_charge_time=start, end_charge_time=end, charge_way="e", enabled=True,
                mqtt_timeout=MQTT_WAIT_S, poll_interval=POLL_INTERVAL_S, poll_attempts=COMMAND_POLL_ATTEMPTS,
            ),
            COMMAND_TIMEOUT_S,
        )  # fmt: skip
        return {"schedule": {"enabled": True, "start": start, "end": end, "mode": mode}}

    async def _resolve_trunk_toggle(self) -> str:
        """Open if closed, close if open. Re-reads the car only when the state is stale."""
        if self.car.get("trunk_open") is None or self.read_age > TRUNK_STATE_MAX_AGE_S:
            try:
                await asyncio.wait_for(self.status(fresh=True), 8)
            except Exception as err:
                log.info("Trunk state check skipped (%r), using last known state", err)
        return "trunk_close" if self.car.get("trunk_open") else "trunk_open"

    def info(self) -> dict[str, Any]:
        runtime = getattr(self.client, "_mqtt_runtime", None) if self.client else None
        return {
            "uptime_min": round((time.time() - self.started_at) / 60, 1),
            "pybyd": PYBYD_VERSION,
            "python": sys.version.split()[0],
            "session": {
                "connected": self.client is not None,
                "age_min": round((time.time() - self.session_since) / 60, 1) if self.session_since else None,
                "commands_ready": self.commands_ready,
                "vin": f"{self.vin[:3]}…{self.vin[-4:]}" if self.vin else None,
            },
            "push_channel": {
                "running": bool(runtime is not None and getattr(runtime, "is_running", False)),
                "events_received": self.mqtt_events,
                "last_event_min_ago": round((time.time() - self.mqtt_last_event) / 60, 1) if self.mqtt_last_event else None,
                "wait_s": MQTT_WAIT_S,
            },
            "last_car_read_s_ago": round(self.read_age) if self.read_at else None,
            "climate_until_min": round((self.climate_until - time.time()) / 60, 1) if self.climate_until else None,
            "recent_commands": list(self.recent),
            "caps": self.caps,
        }


def _hhmm(value: str | None) -> str | None:
    """'7:5' / '07:05' → '07:05'; anything else → None."""
    try:
        hours, minutes = (int(x) for x in (value or "").split(":"))
        return f"{hours:02d}:{minutes:02d}" if 0 <= hours < 24 and 0 <= minutes < 60 else None
    except ValueError:
        return None


def climate_params(params: dict[str, str], offset: float | None = None) -> ClimateStartParams:
    """Temperature within the car's range (15-31 °C on the ATTO 3), a duration snapped to what
    BYD accepts (10-30 min) and the air source: air=fresh (outside air) or air=recirc
    (recirculate, the default: cools and heats faster). `offset` is the car's own temperature
    scale, when known.

    Note: BYD's remote start has no defrost option, so 'defrost' is ignored.
    """
    offset = DEFAULT_TEMP_OFFSET_C if offset is None else offset
    temp = min(offset + 17, max(offset + 1, float(params.get("temp", 21))))
    cycle = 1 if params.get("air") == "fresh" else 2
    return ClimateStartParams(
        temperature=temp, time_span=minutes_to_time_span(climate_minutes(params)), temp_offset=offset, cycle_mode=cycle
    )


def climate_minutes(params: dict[str, str]) -> int:
    """Requested run time snapped to what BYD accepts (10/15/20/25/30)."""
    minutes = int(float(params.get("mins", 10)))
    return min(VALID_CLIMATE_DURATIONS, key=lambda d: abs(d - minutes))


def explain(err: BaseException) -> CommandError:
    """Map pybyd / network failures to a stable code the app can show nicely."""
    if isinstance(err, CommandError):
        return err
    if isinstance(err, asyncio.TimeoutError):
        return CommandError("timeout", "Car did not answer in time (asleep or no signal)", 504)
    if isinstance(err, BydControlPasswordError):
        return CommandError(f"pin_{err.code or 'error'}", f"Control PIN problem: {err}", 403)
    if isinstance(err, BydRateLimitError):
        return CommandError("busy", "Car is still busy with a previous command", 409)
    if isinstance(err, BydEndpointNotSupportedError):
        return CommandError("not_supported", "This car does not support that command", 501)
    if isinstance(err, BydRemoteControlError):
        code = "timeout" if err.code == "timeout" else "rejected"
        return CommandError(code, str(err), 504 if code == "timeout" else 502)
    if isinstance(err, BydAuthenticationError):
        return CommandError("login_failed", f"BYD login failed: {err}", 502)
    if isinstance(err, BydApiError):
        return CommandError(f"byd_{err.code or 'error'}", str(err), 502)
    if isinstance(err, BydTransportError):
        return CommandError("byd_unreachable", "BYD cloud unreachable, try again", 502)
    if isinstance(err, ValueError):
        return CommandError("bad_request", f"Bad parameter: {err}", 400)
    return CommandError("server_error", f"{type(err).__name__}: {err}", 500)


# =============================================================================
# HTTP: routes, auth and responses
# =============================================================================

link = BydLink()

# Every path the old server accepted keeps working (app, MacroDroid, shortcuts).
ROUTES = {
    "status": "status",
    "lock": "lock",
    "unlock": "unlock",
    "ac": "climate_on", "ac-on": "climate_on", "climate": "climate_on", "climate-on": "climate_on",
    "ac-off": "climate_off", "climate-off": "climate_off", "stop-ac": "climate_off",
    "locate": "locate", "find": "locate", "horn": "locate",
    "flash": "flash", "lights": "flash",
    "trunk": "trunk_open", "tailgate": "trunk_open", "open-trunk": "trunk_open", "trunk-open": "trunk_open",
    "trunk-close": "trunk_close", "close-trunk": "trunk_close", "tailgate-close": "trunk_close",
    "trunk-toggle": "trunk_toggle", "toggle-trunk": "trunk_toggle",
    "windows-close": "windows_close", "windows-vent": "windows_vent",
    "seat": "seat", "seats": "seat", "seat-heat": "seat",
    "steering": "steering", "steering-heat": "steering",
    "charge-now": "charge_now", "charge-start": "charge_now",
    "charge-pause": "charge_pause",
    "charge-schedule": "charge_schedule",
}  # fmt: skip

HEALTH_PATHS = {"", "health", "ping"}


# A key that opens /logs and /info only, so a logs link can be shared without the password
# (which can also unlock the car). The app derives the same key: HMAC-SHA256(password, "logs").
LOGS_KEY = hmac.new(AUTH_SECRET.encode(), b"logs", hashlib.sha256).hexdigest()[:24]


def can_read_logs(request: web.Request) -> bool:
    return authorized(request) or hmac.compare_digest(request.query.get("key", "").encode(), LOGS_KEY.encode())


def authorized(request: web.Request) -> bool:
    if not AUTH_SECRET:
        return False  # no secret configured: refuse everything rather than run open
    supplied = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    supplied = supplied or request.query.get("secret", "")
    return hmac.compare_digest(supplied.encode(), AUTH_SECRET.encode())


def caller(request: web.Request) -> str:
    agent = request.headers.get("User-Agent", "").lower()
    for name, marker in (("app", "okhttp"), ("macrodroid", "macrodroid"), ("browser", "mozilla"), ("curl", "curl")):
        if marker in agent:
            return name
    return agent[:20] or "?"


def state_payload(debug: bool = False) -> dict[str, Any]:
    car = link.view()
    payload: dict[str, Any] = {
        "car": car,
        "name": link.car_name,
        "updated": int(link.read_at) or None,  # when the car itself last reported
        "caps": link.caps,
        # Flat legacy fields, kept for MacroDroid macros built on the old server.
        "soc": str(car.get("soc", "N/A")),
        "range": str(car.get("range_km", "N/A")),
    }
    if debug:
        payload["debug_dump"] = link.last_raw
    return payload


async def handle(request: web.Request) -> web.StreamResponse:
    path = request.path.strip("/").lower()
    if path in HEALTH_PATHS:
        link.on_health_ping()
        return web.Response(text="OK")

    if path in ("logs", "info") and can_read_logs(request):
        if path == "logs":
            return web.Response(text="\n".join(LOG_LINES) + "\n", content_type="text/plain", charset="utf-8")
        return web.json_response(link.info(), dumps=functools.partial(json.dumps, ensure_ascii=False, indent=2))

    if not authorized(request):
        log.warning("✗ /%s rejected: wrong or missing secret (from %s)", path, caller(request))
        return web.json_response({"ok": False, "error": "unauthorized"}, status=401)

    action = ROUTES.get(path) or ROUTES.get(path.rsplit("/", 1)[-1])  # tolerate /api/lock style
    if action is None:
        log.warning("✗ unknown route /%s (from %s)", path, caller(request))
        return web.json_response({"ok": False, "error": "unknown_route", "message": f"/{path}"}, status=404)

    started = time.monotonic()
    params = {k: v for k, v in request.query.items() if k != "secret"}
    shown = " ".join(f"{k}={v}" for k, v in params.items())
    log.info("→ %s%s  (from %s)", action, f"  {shown}" if shown else "", caller(request))
    try:
        if action == "status":
            fresh = params.get("fresh") in ("1", "true")
            await link.status(fresh=fresh)
            done = "status"
        else:
            done = await link.command(action, params)
    except Exception as raw:
        err = explain(raw)
        ms = int((time.monotonic() - started) * 1000)
        log.warning("← %s ✗ %s after %.1fs: %s", action, err.code, ms / 1000, err, exc_info=raw if err.code == "server_error" else None)
        link.recent.append({"action": action, "ok": False, "error": err.code, "s": round(ms / 1000, 1)})
        if isinstance(raw, BydAuthenticationError) and not isinstance(raw, BydSessionExpiredError):
            await link.reset()
        body = {"ok": False, "action": action, "error": err.code, "message": str(err), "ms": ms}
        if link.car:
            body.update(state_payload())  # the app can still refresh its screen
        return web.json_response(body, status=err.http_status, headers={"Server-Timing": f"byd;dur={ms}"})

    ms = int((time.monotonic() - started) * 1000)
    cached = done == "status" and ms < 50
    log.info("← %s ✓ %.1fs%s · %s", done, ms / 1000, " (cache)" if cached else "", describe(link.view()))
    body = {"ok": True, "action": done, "ms": ms, **state_payload(debug=params.get("debug") == "1")}
    return web.json_response(body, headers={"Server-Timing": f"byd;dur={ms}"})


async def on_startup(app: web.Application) -> None:
    if not AUTH_SECRET:
        log.error("AUTH_SECRET is not set: every request will be refused until it is")
    if not (BYD_USERNAME and BYD_PASSWORD and BYD_CONTROL_PIN):
        log.error("BYD_USERNAME / BYD_PASSWORD / BYD_CONTROL_PIN are not set: the BYD login will fail")
    log.info("BYD bridge starting · pybyd %s · commands sent once, checked every %.1fs", PYBYD_VERSION, POLL_INTERVAL_S)

    async def warm() -> None:
        try:
            await link.connect()  # login only; the car is not woken at boot
        except Exception as err:
            log.warning("Warm-up failed (%r); will retry on the next request or health ping", err)

    app["warmup"] = asyncio.create_task(warm())


async def on_cleanup(app: web.Application) -> None:
    app["warmup"].cancel()
    await link.reset()


def create_app() -> web.Application:
    app = web.Application()
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    app.router.add_route("*", "/{tail:.*}", handle)
    return app


if __name__ == "__main__":
    web.run_app(create_app(), host="0.0.0.0", port=int(os.environ.get("PORT", 10000)), access_log=None, print=None)

