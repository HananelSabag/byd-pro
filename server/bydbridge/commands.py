"""What each action does on the car: its steps, their effects, and whether resending is harmless.

One request can be several car commands (A/C plus seat heat, both seats). Each is a *step*:
it runs with its own deadline (inside the request's JOB_BUDGET_S), its confirmed effect is
shown and held at once, and when a later step fails the earlier ones stay done and visible
(the reply says `partial`). A step that would start with too little time left is not sent.

`idempotent` means sending it twice does no harm (lock, unlock, close windows, seat levels…).
Only those are offered again after a lost reply; horn, lights and A/C start never are.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Any

from pybyd import VALID_CLIMATE_DURATIONS, BydApiError, BydEndpointNotSupportedError, SeatLevel, minutes_to_time_span
from pybyd._api import smart_charging as _smart_api
from pybyd._constants import DEFAULT_TEMP_OFFSET_C
from pybyd.models import ClimateStartParams
from pybyd.models.command_gating import evaluate_command_gate
from pybyd.models.control import RemoteCommand, SeatClimateParams

from . import config, obs, remote
from .errors import CONFIRMED, NOT_SENT, UNKNOWN, CommandError, bad_request, explain
from .state import COMMAND_EFFECTS, _rounded

if TYPE_CHECKING:
    from pybyd import BydClient

    from .app import Bridge
    from .pipeline import Job

_CHARGE_TOGGLE = "/control/smartCharge/changeChargeStatue"
_CHARGE_SAVE = "/control/smartCharge/saveOrUpdate"


# =============================================================================
# Parameters
# =============================================================================


def int_param(params: dict[str, str], key: str, default: int = 0) -> int:
    value = params.get(key)
    if value is None or value == "":
        return default
    try:
        return int(float(value))
    except ValueError:
        raise bad_request(f"Bad parameter: {key}={value!r}") from None


def flag(params: dict[str, str], key: str, default: str = "1") -> bool:
    return params.get(key, default).strip().lower() in ("1", "true", "on", "yes")


def hhmm(value: str | None) -> str | None:
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
    try:
        wanted = float(params.get("temp", 21))
    except ValueError:
        raise bad_request(f"Bad parameter: temp={params.get('temp')!r}") from None
    temp = min(offset + 17, max(offset + 1, wanted))
    cycle = 1 if params.get("air") == "fresh" else 2
    return ClimateStartParams(
        temperature=temp, time_span=minutes_to_time_span(climate_minutes(params)), temp_offset=offset, cycle_mode=cycle
    )


def climate_minutes(params: dict[str, str]) -> int:
    """Requested run time snapped to what BYD accepts (10/15/20/25/30)."""
    minutes = int_param(params, "mins", 10)
    return min(VALID_CLIMATE_DURATIONS, key=lambda d: abs(d - minutes))


# =============================================================================
# One running request
# =============================================================================


class Run:
    def __init__(self, bridge: Bridge, job: Job, client: BydClient) -> None:
        self.bridge = bridge
        self.job = job
        self.client = client
        self.vin = bridge.session.vin
        self.params = job.params
        self.state = bridge.state
        self.deadline = time.monotonic() + config.JOB_BUDGET_S

    async def step(
        self,
        name: str,
        body: Callable[[remote.StepContext], Awaitable[Any]],
        *,
        effects: dict[str, Any],
        idempotent: bool,
        after: Callable[[], None] | None = None,
    ) -> None:
        """Run one car command with its own deadline; show and hold its effect when confirmed."""
        step = self.job.add_step(name, effects)
        remaining = self.deadline - time.monotonic()
        if remaining < config.MIN_STEP_S:
            step.finish(NOT_SENT, sent=False, error="no_time")
            raise CommandError("busy", f"Not enough time left for {name}; it was not sent", 409, outcome=NOT_SENT, sent=False)
        budget = min(config.COMMAND_TIMEOUT_S, remaining)
        ctx = remote.StepContext(
            name=name, deadline=time.monotonic() + budget, idempotent=idempotent, session=self.bridge.session,
            on_sent=self.job.mark_sent,
        )  # fmt: skip
        token = remote.STEP.set(ctx)
        started = time.monotonic()
        try:
            try:
                await asyncio.wait_for(body(ctx), budget + 5)  # safety net; the loop keeps its own deadline
            except BydEndpointNotSupportedError as err:
                # A failed capability fetch makes pybyd block every command (nothing was sent): refetch once.
                if err.code != "command_gate_blocked" or self.bridge.session.caps.get("lock") is not None:
                    raise
                await self.bridge.session.load_capabilities(self.client, force=True)
                await asyncio.wait_for(body(ctx), max(1.0, ctx.deadline - time.monotonic()) + 5)
        except Exception as raw:
            err = explain(raw, sent=ctx.sent)
            step.finish(err.outcome, sent=err.sent, error=err.code, serial=ctx.serial, ms=_ms(started), polls=ctx.polls,
                        triggered_at=ctx.triggered_at)  # fmt: skip
            level = logging.INFO if err.outcome == NOT_SENT else logging.WARNING
            obs.event(f"{name} {err.outcome}", level=level, error=err.code, sent=err.sent, serial=ctx.serial,
                      car_ms=_ms(started), polls=ctx.polls or None, blips=ctx.blips or None)  # fmt: skip
            if err is raw:
                raise
            raise err from raw
        finally:
            remote.STEP.reset(token)
        step.finish(CONFIRMED, sent=True, serial=ctx.serial, ms=_ms(started), polls=ctx.polls, triggered_at=ctx.triggered_at)
        self.state.apply_confirmed(effects, job=self.job.id)
        if after is not None:
            after()
        obs.event(f"{name} confirmed", serial=ctx.serial, car_ms=_ms(started), polls=ctx.polls or None, blips=ctx.blips or None)

    async def remote_call(self, name: str, call: Callable[[], Awaitable[Any]], *, effects: dict[str, Any],
                          idempotent: bool, after: Callable[[], None] | None = None) -> None:  # fmt: skip
        """A pybyd remote command (lock(), start_climate(), …): it runs through remote.py's loop."""

        async def body(_ctx: remote.StepContext) -> None:
            await call()

        await self.step(name, body, effects=effects, idempotent=idempotent, after=after)

    async def ack(self, name: str, call: Callable[[], Awaitable[Any]], *, effects: dict[str, Any]) -> None:
        """A single cloud call with no car confirmation (schedule switch): confirmed when BYD says ok."""

        async def body(ctx: remote.StepContext) -> None:
            ctx.sent = UNKNOWN
            try:
                await asyncio.wait_for(call(), config.HTTP_TIMEOUT_S + config.LOGIN_TIMEOUT_S)
            except BydApiError:
                ctx.sent = False  # BYD answered with an error code: not taken
                raise
            ctx.mark_sent()

        await self.step(name, body, effects=effects, idempotent=True)

    async def gate(self, command: RemoteCommand) -> None:
        """pybyd's capability check for commands that don't go through its remote-control path."""
        caps = await asyncio.wait_for(self.client.get_vehicle_capabilities(self.vin), config.CAPS_TIMEOUT_S)
        learn = await self.client._vehicle_fun_learn_info_for(self.vin)
        verdict = evaluate_command_gate(command, caps, learn_info=learn)
        if not verdict.supported:
            raise BydEndpointNotSupportedError(
                f"{command.value} blocked: gate={verdict.gate_id} reason={verdict.reason}",
                code="command_gate_blocked",
                endpoint=_CHARGE_TOGGLE,
            )


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


# =============================================================================
# Actions
# =============================================================================

IDEMPOTENT = {
    "lock": True, "unlock": True, "climate_off": True, "locate": False, "flash": False,
    "trunk_open": True, "trunk_close": True, "windows_close": True, "windows_vent": True,
}  # fmt: skip


def _simple_call(client: BydClient, vin: str, action: str) -> Callable[[], Awaitable[Any]]:
    calls = {
        "lock": client.lock,
        "unlock": client.unlock,
        "climate_off": client.stop_climate,
        "locate": client.find_car,
        "flash": client.flash_lights,
        "trunk_open": client.open_trunk,
        "trunk_close": client.close_trunk,
        "windows_close": client.close_windows,
        "windows_vent": client.open_windows,
    }
    return lambda: calls[action](vin)


async def run_simple(run: Run, action: str) -> None:
    run.job.final_action = action

    def after() -> None:
        if action == "climate_off":
            run.state.set_climate(0.0)

    await run.remote_call(
        action, _simple_call(run.client, run.vin, action), effects=COMMAND_EFFECTS.get(action, {}),
        idempotent=IDEMPOTENT[action], after=after,
    )  # fmt: skip


async def run_trunk_toggle(run: Run) -> None:
    """Open if closed, close if open. Re-reads the car only when the state is stale."""
    state = run.state
    if state.car.get("trunk_open") is None or state.read_age > config.TRUNK_STATE_MAX_AGE_S:
        try:
            await run.bridge.reads.status(fresh=True, timeout=config.TRUNK_READ_TIMEOUT_S)
        except Exception as err:
            obs.event("trunk state check skipped, using last known state", error=getattr(err, "code", None) or repr(err))
    action = "trunk_close" if state.car.get("trunk_open") else "trunk_open"
    obs.event("trunk toggle resolved", to=action, age_s=round(state.read_age) if state.read_at else None)
    await run_simple(run, action)


async def start_climate(run: Run, params: dict[str, str], *, name: str = "climate_on") -> None:
    state = run.state
    start = climate_params(params, getattr(state.hvac, "temp_offset", None))
    minutes = climate_minutes(params)

    def after() -> None:
        state.set_climate(time.time() + minutes * 60, start.temperature)

    await run.remote_call(
        name, lambda: run.client.start_climate(run.vin, params=start),
        effects={"climate_on": True, "recirc": start.cycle_mode == 2, "climate_set_c": _rounded(start.temperature)},
        idempotent=False, after=after,
    )  # fmt: skip


async def run_climate_on(run: Run) -> None:
    run.job.final_action = "climate_on"
    params = run.params
    await start_climate(run, params)
    # The old app sent seat levels with the A/C; keep that working (each is its own step).
    for seat, key in (("driver", "driver_seat"), ("passenger", "passenger_seat")):
        level = int_param(params, key, 0)
        if level > 0:
            await set_seat(run, seat, level)


async def run_recirc(run: Run) -> None:
    """The air source is part of the start command, so changing it while the A/C runs starts it
    again with the same temperature for the time that is left (at least 10 min)."""
    run.job.final_action = "recirc"
    state = run.state
    on = flag(run.params, "on")
    if not state.view().get("climate_on"):
        raise CommandError("climate_off", "The A/C is off; the air setting applies to the next start", 409, outcome=NOT_SENT)
    temp = state.climate_temp or getattr(state.hvac, "main_setting_temp_new", None) or 21
    left = (state.climate_until - time.time()) / 60 if state.climate_until > time.time() else 20
    await start_climate(run, {"temp": str(temp), "mins": str(left), "air": "recirc" if on else "fresh"}, name="recirc")


async def seat_base(run: Run) -> SeatClimateParams:
    """Every seat command must resend all seat levels. Start from the car's last report, then
    apply what we know is newer (levels confirmed by earlier commands), so changing one seat
    never resets another to a stale level."""
    state = run.state
    if state.realtime is None and state.hvac is None:
        await run.bridge.reads.status(fresh=True, timeout=config.STATUS_TIMEOUT_S + 2)
        if state.realtime is None and state.hvac is None:
            raise CommandError("timeout", "Couldn't read the current seat levels; nothing was sent", 504, outcome=NOT_SENT)
    base = SeatClimateParams.from_current_state(hvac=state.hvac, realtime=state.realtime)
    seats = state.car.get("seat_heat", {})
    known = {
        param: SeatLevel(seats[seat]).to_command_value()
        for seat, param in (("driver", "main_heat"), ("passenger", "copilot_heat"))
        if seat in seats
    }
    return base.model_copy(update=known)


async def set_seat(run: Run, seat: str, level: int) -> None:
    level = max(0, min(2, level))
    seat = "passenger" if seat == "passenger" else "driver"
    key = "copilot_heat" if seat == "passenger" else "main_heat"
    base = await seat_base(run)
    command = base.with_change(key, SeatLevel(level).to_command_value())
    seats = {**run.state.car.get("seat_heat", {}), seat: level}
    # Seat heat only: it used to mark the A/C as on too, which showed a running A/C that wasn't.
    await run.remote_call(
        f"seat_{seat}", lambda: run.client.set_seat_climate(run.vin, params=command),
        effects={"seat_heat": seats}, idempotent=True,
    )  # fmt: skip


async def run_seat(run: Run) -> None:
    run.job.final_action = "seat"
    params = run.params
    if "seat" in params:  # one seat: ?seat=driver&level=2
        await set_seat(run, params["seat"], int_param(params, "level", 0))
        return
    # both: ?driver=2&passenger=0 (only seats that actually change are sent)
    for seat in ("driver", "passenger"):
        if seat in params and int_param(params, seat) != run.state.car.get("seat_heat", {}).get(seat):
            await set_seat(run, seat, int_param(params, seat))


async def run_steering(run: Run) -> None:
    run.job.final_action = "steering"
    on = flag(run.params, "on")
    base = await seat_base(run)
    command = base.with_change("steering_wheel_heat_state", 1 if on else 3)
    await run.remote_call(
        "steering", lambda: run.client.set_seat_climate(run.vin, params=command), effects={"steering_heat": on}, idempotent=True
    )


async def run_charge_now(run: Run) -> None:
    run.job.final_action = "charge_now"
    fetch = _smart_api.make_change_charge_fetch_fn(start=True)

    async def body(ctx: remote.StepContext) -> None:
        await run.gate(RemoteCommand.START_CHARGE)
        await remote.smart_change(ctx, run.client, run.vin, fetch, _CHARGE_TOGGLE)

    await run.step("charge_now", body, effects=COMMAND_EFFECTS["charge_now"], idempotent=True)


async def run_charge_schedule(run: Run) -> None:
    """mode: window (start→end) · start_only (start→full) · stop_only (now→end). enabled=false turns it off.

    BYD schedules repeat daily ("e"), so stop_only also starts again each day at the time it was
    saved; the app says so. (Single-run "s" exists in pybyd but is untested on the ATTO 3.)
    """
    run.job.final_action = "charge_schedule"
    params, state = run.params, run.state
    if "mode" not in params and "enabled" not in params:
        # A bare call used to save "now → full, every day". Ask for intent instead.
        raise bad_request("charge-schedule needs mode=window|start_only|stop_only or enabled=false")
    if params.get("enabled", "true").lower() in ("0", "false", "no"):
        await run.ack(
            "charge_schedule", lambda: run.client.toggle_smart_charging(run.vin, enable=False),
            effects={"schedule": {**state.car.get("schedule", {}), "enabled": False}},
        )  # fmt: skip
        return
    mode = params.get("mode", "")
    if mode not in ("window", "start_only", "stop_only"):
        raise bad_request(f"Unknown schedule mode {mode!r}")
    now = hhmm(params.get("now")) or datetime.now(config.CAR_TIMEZONE).strftime("%H:%M")  # the app sends phone time
    start = now if mode == "stop_only" else (hhmm(params.get("start")) or now)
    end = "full" if mode == "start_only" else (hhmm(params.get("end")) or "full")
    fetch = _smart_api.make_save_charging_schedule_fetch_fn(start_charge_time=start, end_charge_time=end, charge_way="e", enabled=True)

    async def body(ctx: remote.StepContext) -> None:
        await run.gate(RemoteCommand.START_CHARGE)
        await remote.smart_change(ctx, run.client, run.vin, fetch, _CHARGE_SAVE)

    await run.step(
        "charge_schedule", body, effects={"schedule": {"enabled": True, "start": start, "end": end, "mode": mode}}, idempotent=True
    )


async def run_charge_pause(run: Run) -> None:
    """The BYD cloud cannot stop a charge directly; re-enabling the schedule makes the car wait
    for its charging window again (checked afterwards: the pipeline's pause check)."""
    run.job.final_action = "charge_pause"
    await run.ack(
        "charge_pause", lambda: run.client.toggle_smart_charging(run.vin, enable=True),
        effects={"schedule": {**run.state.car.get("schedule", {}), "enabled": True}},
    )  # fmt: skip
    run.job.post = "pause_check"


@dataclass(frozen=True)
class Spec:
    run: Callable[[Run], Awaitable[None]]


ACTIONS: dict[str, Spec] = {
    **{name: Spec(lambda r, a=name: run_simple(r, a)) for name in IDEMPOTENT},
    "trunk_toggle": Spec(run_trunk_toggle),
    "climate_on": Spec(run_climate_on),
    "recirc": Spec(run_recirc),
    "seat": Spec(run_seat),
    "steering": Spec(run_steering),
    "charge_now": Spec(run_charge_now),
    "charge_schedule": Spec(run_charge_schedule),
    "charge_pause": Spec(run_charge_pause),
}


async def execute(bridge: Bridge, job: Job, client: BydClient) -> None:
    spec = ACTIONS.get(job.action)
    if spec is None:
        raise CommandError("unknown_action", f"Unknown action: {job.action}", 400)
    await bridge.session.ensure_commands(client)  # PIN checked once; raises pin_* if wrong
    await spec.run(Run(bridge, job, client))
