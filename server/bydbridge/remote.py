"""Send one car command once, then poll its confirmation until a deadline.

pybyd's own loop can send a command twice: when the BYD session expires while it waits for the
car, its re-login wrapper runs the whole command again (trigger included), and one network blip
while polling aborts a command the car is already executing. This module replaces that loop
(`pybyd._api.control.poll_remote_control`, which pybyd's lock()/unlock()/… call through the
module) and also runs the smart-charging commands, with these rules:

  • The command is offered to BYD again only when BYD clearly did not take it: code 6024
    ("a previous command is still running"), 1008 (busy), an expired-session code, or a
    connection that never opened. A lost reply after sending (timeout, HTTP 5xx) is never
    re-sent for commands that are not idempotent (horn, lights, A/C start).
  • After it is sent, the same requestSerial is polled until the deadline. Network blips and
    "busy" answers are skipped (with backoff); an expired session is renewed and polling goes on.
  • The result is honest: confirmed, rejected (car said no), or unconfirmed (sent, no answer).
"""

from __future__ import annotations

import asyncio
import contextvars
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

import aiohttp
from pybyd import BydApiError, BydSessionExpiredError, BydTransportError
from pybyd._api import control as _control_api
from pybyd._api import smart_charging as _smart_api
from pybyd.exceptions import BydServiceBusyError
from pybyd.models.control import RemoteControlResult

from . import config, obs
from .usage import USAGE
from .errors import NOT_SENT, REJECTED, UNCONFIRMED, UNKNOWN, CommandError, explain, not_delivered

if TYPE_CHECKING:
    from pybyd import BydClient
    from pybyd.session import Session

    from .session import SessionManager

_TRIGGER = "/control/remoteControl"
_RESULT = "/control/remoteControlResult"
_SMART_RESULT = "/control/smartCharge/changeResult"


@dataclass
class StepContext:
    """What the poll loop knows about the step it is running (set by commands.py)."""

    name: str
    deadline: float  # time.monotonic()
    idempotent: bool
    session: SessionManager | None = None
    sent: bool | str = False
    serial: str | None = None
    triggers: int = 0  # how many times the command was offered to BYD
    polls: int = 0
    blips: int = 0
    trigger_ms: int | None = None
    triggered_at: float = 0.0  # time.time() of the accepted trigger
    on_sent: Callable[[], None] | None = field(default=None, repr=False)

    def mark_sent(self) -> None:
        self.sent = True
        if self.on_sent is not None:
            self.on_sent()


STEP: contextvars.ContextVar[StepContext | None] = contextvars.ContextVar("step", default=None)


def install() -> None:
    """Route pybyd's remote commands through our loop (idempotent)."""
    if _control_api.poll_remote_control is not poll_remote_control:
        _control_api.poll_remote_control = poll_remote_control


def _remaining(ctx: StepContext) -> float:
    return ctx.deadline - time.monotonic()


def _time_for(ctx: StepContext, wait: float) -> bool:
    """Is there time to wait `wait` seconds, offer the command again and still hear back?"""
    return _remaining(ctx) > wait + max(1.5, 3 * config.POLL_INTERVAL_S)


async def _bounded(coro: Awaitable[Any], ctx: StepContext, limit: float | None = None) -> Any:
    limit = config.HTTP_TIMEOUT_S + 1 if limit is None else limit
    return await asyncio.wait_for(coro, max(0.5, min(limit, _remaining(ctx))))


async def trigger_and_poll(
    ctx: StepContext,
    session: Session,
    *,
    trigger: Callable[[Session], Awaitable[tuple[dict[str, Any], str | None]]],
    poll: Callable[[Session, str], Awaitable[tuple[dict[str, Any], str | None]]],
    is_terminal: Callable[[dict[str, Any]], bool],
    judge: Callable[[dict[str, Any]], dict[str, Any]],
) -> dict[str, Any]:
    """Offer the command (at most once unless BYD clearly refused it), then poll the same serial."""
    mgr = ctx.session
    refreshed = False
    attempts = 0
    while True:
        attempts += 1
        if attempts > 1 and _remaining(ctx) < 1.5:
            raise CommandError("busy", "No time left to send the command again", 409, outcome=NOT_SENT, sent=False)
        ctx.sent = UNKNOWN  # from here until BYD answers, it may have gone out
        ctx.triggers += 1
        started = time.monotonic()
        try:
            result, serial = await _bounded(trigger(session), ctx)
            break
        except BydSessionExpiredError as err:
            ctx.sent = False  # rejected before BYD looked at it
            if refreshed or mgr is None:
                raise CommandError("login_failed", f"BYD login failed: {err}", 502, sent=False) from err
            obs.event(f"{ctx.name} session expired before sending; logging in again", code=err.code)
            session = await mgr.refresh(session)
            refreshed = True
        except BydServiceBusyError as err:  # 1008: BYD is overloaded, the command was not taken
            ctx.sent = False
            wait = config.BUSY_RETRY_WAIT_S * attempts
            if attempts >= config.TRIGGER_RETRIES or not _time_for(ctx, wait):
                raise explain(err, sent=False) from err
            obs.event(f"{ctx.name} BYD busy (1008) before sending; trying again", attempt=attempts, wait_s=wait)
            await asyncio.sleep(wait)
        except BydApiError as err:
            ctx.sent = False
            if err.code != "6024":
                raise explain(err, sent=False) from err
            # 6024: a previous command is still running on the car. It wasn't taken; wait.
            if not _time_for(ctx, config.RATE_LIMIT_WAIT_S):
                raise CommandError("busy", "Car is still busy with a previous command", 409, outcome=NOT_SENT, sent=False) from err
            obs.event(f"{ctx.name} BYD says a previous command is still running (6024); waiting", wait_s=config.RATE_LIMIT_WAIT_S)
            await asyncio.sleep(config.RATE_LIMIT_WAIT_S)
        except (BydTransportError, aiohttp.ClientError, OSError, asyncio.TimeoutError) as err:
            if not_delivered(err):
                ctx.sent = False
                if attempts >= config.TRIGGER_RETRIES or not _time_for(ctx, config.CONNECT_RETRY_WAIT_S):
                    raise explain(err, sent=False) from err
                obs.event(f"{ctx.name} BYD unreachable before sending; trying again", error=type(err).__name__)
                await asyncio.sleep(config.CONNECT_RETRY_WAIT_S)
                continue
            ctx.sent = UNKNOWN
            if ctx.idempotent and attempts < 2 and _time_for(ctx, 0):
                obs.event(f"{ctx.name} reply lost; sending again (harmless for this command)", error=type(err).__name__)
                continue
            obs.event(f"{ctx.name} reply lost after sending; not sending again", level=logging.WARNING, error=type(err).__name__)
            raise explain(err, sent=UNKNOWN) from err

    ctx.serial = serial
    ctx.trigger_ms = int((time.monotonic() - started) * 1000)
    ctx.triggered_at = time.time()
    ctx.mark_sent()
    USAGE.command()
    obs.event(f"{ctx.name} sent", serial=serial, trigger_ms=ctx.trigger_ms, attempts=attempts if attempts > 1 else None)

    if isinstance(result, dict) and is_terminal(result):
        return judge(result)
    if not serial:
        raise CommandError("timeout", "BYD took the command but gave no way to confirm it", 504, outcome=UNCONFIRMED, sent=True)

    interval = config.POLL_INTERVAL_S
    refreshes = 0
    while _remaining(ctx) > interval + 0.3:
        await asyncio.sleep(interval)
        ctx.polls += 1
        try:
            latest, serial = await _bounded(poll(session, serial), ctx, config.POLL_CALL_TIMEOUT_S)
        except BydSessionExpiredError as err:
            ctx.blips += 1
            if mgr is None or refreshes >= config.POLL_SESSION_REFRESHES:
                continue
            refreshes += 1
            obs.event(f"{ctx.name} session expired while waiting; logging in again, same serial", serial=serial, code=err.code)
            try:
                session = await mgr.refresh(session)
            except Exception as login_err:
                obs.event(f"{ctx.name} re-login failed; still waiting", level=logging.WARNING, error=repr(login_err))
            continue
        except BydServiceBusyError:
            ctx.blips += 1
            interval = min(interval * 1.5, config.POLL_MAX_INTERVAL_S)
            continue
        except (BydApiError, BydTransportError, aiohttp.ClientError, OSError, asyncio.TimeoutError) as err:
            ctx.blips += 1
            obs.event(f"{ctx.name} check failed; still waiting", serial=serial, error=f"{type(err).__name__}:{getattr(err, 'code', '')}")
            continue
        interval = max(config.POLL_INTERVAL_S, interval * 0.8)
        if isinstance(latest, dict) and is_terminal(latest):
            return judge(latest)
    raise CommandError(
        "timeout", "Car did not confirm in time (asleep or no signal); it may still do it", 504, outcome=UNCONFIRMED, sent=True
    )


# =============================================================================
# Remote control (lock, unlock, A/C, horn, lights, trunk, windows, seats)
# =============================================================================


def _judge_remote(name: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def judge(data: dict[str, Any]) -> dict[str, Any]:
        parsed = RemoteControlResult.model_validate(data)
        if int(parsed.control_state) == 2:
            msg = data.get("message") or data.get("msg") or "controlState=2"
            raise CommandError("rejected", f"Remote control {name} failed: {msg}", 502, outcome=REJECTED, sent=True)
        return data

    return judge


async def poll_remote_control(
    config_: Any,
    session: Session,
    transport: Any,
    vin: str,
    command: Any,
    *,
    control_params: dict[str, Any] | None = None,
    command_pwd: str | None = None,
    on_trigger_dispatched: Callable[[str | None], None] | None = None,
    **_ignored: Any,
) -> RemoteControlResult:
    """Drop-in for pybyd's poll_remote_control (same arguments), with the rules above."""
    ctx = STEP.get()
    if ctx is None:  # outside a job (should not happen): still never resend
        ctx = StepContext(name=command.name.lower(), deadline=time.monotonic() + config.COMMAND_TIMEOUT_S, idempotent=False)

    async def trigger(sess: Session) -> tuple[dict[str, Any], str | None]:
        return await _control_api._fetch_control_endpoint(
            _TRIGGER, config_, sess, transport, vin, command, control_params=control_params, command_pwd=command_pwd
        )

    async def poll(sess: Session, serial: str) -> tuple[dict[str, Any], str | None]:
        return await _control_api._fetch_control_endpoint(_RESULT, config_, sess, transport, vin, command, request_serial=serial)

    data = await trigger_and_poll(
        ctx, session, trigger=trigger, poll=poll, is_terminal=_control_api._is_remote_control_ready, judge=_judge_remote(command.name)
    )
    if on_trigger_dispatched is not None and ctx.serial:
        try:
            on_trigger_dispatched(ctx.serial)
        except Exception:
            pass
    return RemoteControlResult.model_validate(data)


# =============================================================================
# Smart charging (charge now, save schedule): BYD's other trigger + poll pair
# =============================================================================


def _judge_smart(name: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def judge(data: dict[str, Any]) -> dict[str, Any]:
        res = data.get("res")
        if res == 2:
            return data
        if res == 3:
            raise CommandError(
                "rejected", f"{name}: the car is likely already in that state (res=3)", 502, outcome=REJECTED, sent=True
            )
        raise CommandError("rejected", f"{name} rejected by BYD (res={res}, {data.get('message')!r})", 502, outcome=REJECTED, sent=True)

    return judge


async def smart_change(ctx: StepContext, client: BydClient, vin: str, fetch_fn: Any, trigger_endpoint: str) -> dict[str, Any]:
    transport = client._require_transport()
    byd_config = client._config
    session = await asyncio.wait_for(client.ensure_session(), config.LOGIN_TIMEOUT_S + 2)

    async def trigger(sess: Session) -> tuple[dict[str, Any], str | None]:
        return await fetch_fn(trigger_endpoint, byd_config, sess, transport, vin)

    async def poll(sess: Session, serial: str) -> tuple[dict[str, Any], str | None]:
        return await fetch_fn(_SMART_RESULT, byd_config, sess, transport, vin, serial)

    return await trigger_and_poll(
        ctx, session, trigger=trigger, poll=poll, is_terminal=_smart_api.is_charge_change_ready, judge=_judge_smart(ctx.name)
    )
