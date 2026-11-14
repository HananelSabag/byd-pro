"""Live status reads: one at a time, shared by every caller, always bounded.

The server never polls the car. A read happens only when someone asks for fresh data (or a
command needs it). The order matters: the realtime read wakes the car first, and only then is
the A/C status read, so it reflects the woken car instead of the cloud's older copy. The charging
schedule is a cloud setting and is read alongside the wake.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import TYPE_CHECKING, Any

from . import config, obs
from .errors import CommandError
from .usage import USAGE
from .state import describe, describe_hvac, has_data, snapshot_from_hvac, snapshot_from_realtime, snapshot_from_schedule

if TYPE_CHECKING:
    from .app import Bridge


class Reader:
    def __init__(self, bridge: Bridge) -> None:
        self.bridge = bridge
        self._task: asyncio.Task | None = None
        self.reads = 0

    @property
    def busy(self) -> bool:
        return self._task is not None and not self._task.done()

    async def status(self, *, fresh: bool = False, timeout: float | None = None) -> dict[str, Any]:
        """Cached snapshot if recent, otherwise one live read shared by all callers.

        A failed live read serves the last known state; with nothing known yet, it raises."""
        state = self.bridge.state
        if not fresh and state.car and state.read_age < config.STATUS_CACHE_S:
            return state.car
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._read_car())
            # Callers may give up early (timeouts); mark the outcome as seen either way.
            self._task.add_done_callback(lambda t: t.cancelled() or t.exception())
        try:
            await asyncio.wait_for(asyncio.shield(self._task), timeout or config.STATUS_TIMEOUT_S + config.CONNECT_TIMEOUT_S)
        except Exception as err:
            if not state.car:
                raise
            obs.event("live status failed, serving last known state", level=logging.WARNING,
                      error=getattr(err, "code", None) or type(err).__name__, age_s=round(state.read_age))  # fmt: skip
        return state.car

    async def _read_car(self) -> None:
        session = self.bridge.session
        client = await session.ensure(config.STATUS_TIMEOUT_S)
        vin = session.vin
        state = self.bridge.state
        started = time.monotonic()
        deadline = started + config.STATUS_TIMEOUT_S
        self.reads += 1
        USAGE.car_read()

        schedule_task = None
        if session.caps.get("start_charge"):
            schedule_task = asyncio.ensure_future(asyncio.wait_for(client.get_charging_schedule(vin), config.HTTP_TIMEOUT_S))

        realtime: Any
        try:
            realtime = await asyncio.wait_for(
                client.get_vehicle_realtime(
                    vin, mqtt_timeout=config.MQTT_WAIT_S, poll_interval=config.POLL_INTERVAL_S, poll_attempts=config.STATUS_POLL_ATTEMPTS
                ),
                max(1.0, config.STATUS_TIMEOUT_S - config.HVAC_TIMEOUT_S),
            )
        except Exception as err:
            realtime = err
        woke_ms = int((time.monotonic() - started) * 1000)

        hvac: Any
        try:  # after the wake, not alongside it
            hvac = await asyncio.wait_for(client.get_hvac_status(vin), max(1.0, min(config.HVAC_TIMEOUT_S, deadline - time.monotonic())))
        except Exception as err:
            hvac = err

        schedule: Any = None
        if schedule_task is not None:
            try:
                schedule = await asyncio.wait_for(schedule_task, max(0.1, deadline - time.monotonic()))
            except Exception as err:
                schedule = err

        if isinstance(hvac, BaseException):
            obs.event("A/C read failed; its last known state is kept", error=repr(hvac))
        else:
            state.hvac = hvac
            state.last_hvac = describe_hvac(hvac)
            state.apply_report(snapshot_from_hvac(hvac), source="hvac")
        if schedule is not None and not isinstance(schedule, BaseException):
            state.apply_report(snapshot_from_schedule(schedule), source="schedule")
        if isinstance(realtime, BaseException):
            raise realtime
        state.last_raw = dict(realtime.raw or {})
        if not has_data(state.last_raw):
            # An asleep car can answer with an empty report; merging it would show 0 % and 0 km.
            raise CommandError("timeout", "Car is asleep: no fresh data", 504)
        self._apply_realtime(realtime, source="realtime")
        session.last_api_ok = time.monotonic()
        obs.log.info("  car read in %.1fs (wake %.1fs): %s", time.monotonic() - started, woke_ms / 1000, describe(state.view()))
        if state.last_hvac:
            obs.log.info("  A/C: %s", state.last_hvac)

    def _apply_realtime(self, realtime: Any, *, source: str) -> None:
        state = self.bridge.state
        snap = snapshot_from_realtime(realtime)
        data_ts, known_ts = snap.get("data_ts"), state.data_ts
        if data_ts and known_ts and data_ts < known_ts:
            # Pushes and reads can arrive out of order: never let an older report overwrite a newer one.
            obs.event("older car report ignored", source=source, behind_s=known_ts - data_ts)
            return
        state.realtime = realtime
        state.apply_report(snap, source=source, data_ts=data_ts)
        state.read_at = time.time()

    def on_push(self, realtime: Any) -> None:
        """MQTT 'vehicleInfo' push: the car reported on its own, free update."""
        try:
            if has_data(dict(getattr(realtime, "raw", None) or {})) or getattr(realtime, "raw", None) is None:
                self._apply_realtime(realtime, source="push")
        except Exception as err:  # a push must never break anything
            obs.event("push ignored", level=logging.WARNING, error=repr(err))
