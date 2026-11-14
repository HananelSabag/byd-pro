"""The one warm BYD session for the whole process.

  • Login, vehicle lookup, PIN check and capability fetch happen once (warm-up), never on a tap,
    and every one of them has a time limit.
  • Logins are single-flight: however many callers find the session expired at once (three
    parallel reads, a command, pybyd's own push-channel recovery), BYD sees one login. Callers
    arriving within LOGIN_REUSE_S of a fresh login share it instead of logging in again.
  • A failed login is not retried on every request: callers fail fast for LOGIN_RETRY_S (which
    doubles up to LOGIN_RETRY_MAX_S), so a wrong password can't lock the account.
  • pybyd's HTTP session gets a real timeout (aiohttp's default would let one call hang 5 min).
  • The session is renewed quietly before pybyd's 12 h limit, from the /health ping, when idle.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import TYPE_CHECKING, Any

import aiohttp
from pybyd import BydClient, BydConfig, BydControlPasswordError

from . import config, obs, usage
from .errors import NOT_SENT, CommandError, explain
from .state import CAPABILITY_KEYS

if TYPE_CHECKING:
    from pybyd.session import Session

    from .app import Bridge


class SessionManager:
    def __init__(self, bridge: Bridge) -> None:
        self.bridge = bridge
        self.client: BydClient | None = None
        self.http: aiohttp.ClientSession | None = None
        self.vin = ""
        self.car_name = "BYD"
        self.commands_ready = False
        self.caps: dict[str, bool | None] = {}

        self.session_since = 0.0  # time.time() of the last successful login
        self.last_api_ok = 0.0  # time.monotonic() of the last successful BYD call
        self.logins = 0
        self.login_failures = 0
        self.mqtt_events = 0
        self.mqtt_last_event = 0.0

        self._connect_task: asyncio.Task | None = None
        self._connect_error: BaseException | None = None
        self._connect_retry_at = 0.0
        self._connect_failures = 0

        self._login_lock = asyncio.Lock()
        self._original_login: Any = None
        self._last_session: Session | None = None
        self._last_login_ok = 0.0  # monotonic
        self._login_error: BaseException | None = None
        self._login_retry_at = 0.0  # monotonic
        self._background: set[asyncio.Task] = set()

    # ------------------------------------------------------------------ warm-up

    @property
    def warm(self) -> bool:
        return self.client is not None

    async def ensure(self, timeout: float) -> BydClient:
        """The warm client. Waits at most `timeout` for a warm-up that is still running."""
        if self.client is not None:
            return self.client
        if self._connect_error is not None and time.monotonic() < self._connect_retry_at:
            raise self._cooling_down(self._connect_error, self._connect_retry_at)
        if self._connect_task is None or self._connect_task.done():
            self._connect_task = asyncio.create_task(self._connect())
            self._connect_task.add_done_callback(lambda t: t.cancelled() or t.exception())
        try:
            return await asyncio.wait_for(asyncio.shield(self._connect_task), max(0.1, timeout))
        except asyncio.TimeoutError:
            raise CommandError(
                "warming_up", "Still connecting to BYD; try again in a moment", 503,
                outcome=NOT_SENT, sent=False, retry_after_s=config.WARMING_RETRY_S,
            ) from None  # fmt: skip

    @staticmethod
    def _cooling_down(err: BaseException, retry_at: float) -> CommandError:
        base = explain(err)
        wait = max(1.0, retry_at - time.monotonic())
        return CommandError(
            base.code, f"{base} (not retried for another {wait:.0f}s)", base.http_status,
            outcome=base.outcome, sent=False, retry_after_s=round(wait),
        )  # fmt: skip

    def start_warmup(self) -> None:
        """Connect in the background (boot, health ping); errors are only logged."""
        if self.client is not None or (self._connect_task is not None and not self._connect_task.done()):
            return
        if self._connect_error is not None and time.monotonic() < self._connect_retry_at:
            return

        async def warm() -> None:
            try:
                await self.ensure(config.CONNECT_TIMEOUT_S + 5)
            except Exception as err:
                obs.event("warm-up failed; will retry on the next request or health ping", level=logging.WARNING, error=repr(err))

        self._spawn(warm())

    async def _connect(self) -> BydClient:
        started = time.monotonic()
        http = aiohttp.ClientSession(
            cookie_jar=aiohttp.CookieJar(unsafe=True),
            timeout=aiohttp.ClientTimeout(total=config.HTTP_TIMEOUT_S, connect=config.HTTP_CONNECT_TIMEOUT_S),
            trace_configs=[usage.trace_config()],  # counts the bytes this server exchanges with BYD
        )
        byd_config = BydConfig(
            username=config.BYD_USERNAME,
            password=config.BYD_PASSWORD,
            control_pin=config.BYD_CONTROL_PIN,
            mqtt_timeout=config.MQTT_WAIT_S,
            mqtt_enabled=config.MQTT_ENABLED,
        )
        client = BydClient(byd_config, session=http, on_vehicle_info=self._on_push, on_mqtt_event=self._on_mqtt_event)
        self._install_login(client)
        try:
            async with asyncio.timeout(config.CONNECT_TIMEOUT_S):
                await client.async_start()
                vehicles = await client.get_vehicles()  # logs in on first use
                if not vehicles:
                    raise CommandError("no_vehicle", "No vehicle on this BYD account", 502)
                vehicle = vehicles[0]
                self.vin = vehicle.vin
                obs.hide(self.vin, f"{self.vin[:3]}…{self.vin[-4:]}")
                self.car_name = vehicle.auto_alias or vehicle.model_name or "BYD"
                try:
                    await asyncio.wait_for(client.verify_command_access(self.vin), config.PIN_TIMEOUT_S)
                    self.commands_ready = True
                except BydControlPasswordError as err:
                    obs.event("control PIN rejected; commands are disabled, status still works", level=logging.ERROR, code=err.code)
                await self.load_capabilities(client)
        except BaseException as err:
            await _close(client, http)
            self._connect_failures += 1
            self._connect_error = err if isinstance(err, Exception) else None
            delay = min(config.LOGIN_RETRY_S * 2 ** (self._connect_failures - 1), config.LOGIN_RETRY_MAX_S)
            self._connect_retry_at = time.monotonic() + delay
            if isinstance(err, Exception):
                obs.event("BYD warm-up failed", level=logging.WARNING, error=repr(err), retry_in_s=delay)
            raise
        self._connect_failures = 0
        self._connect_error = None
        self.client, self.http = client, http
        self.last_api_ok = time.monotonic()
        supported = [k for k, v in self.caps.items() if v]
        obs.log.info(
            "BYD session ready for %s…%s in %.1fs · supports: %s",
            self.vin[:3], self.vin[-4:], time.monotonic() - started, ", ".join(supported) or "unknown",
        )  # fmt: skip
        return client

    async def load_capabilities(self, client: BydClient, *, force: bool = False) -> None:
        try:
            caps = await asyncio.wait_for(client.get_vehicle_capabilities(self.vin, force_refresh=force), config.CAPS_TIMEOUT_S)
        except asyncio.TimeoutError:
            obs.event("capability list timed out; will retry on the next command", level=logging.WARNING)
            return
        if caps.source.startswith("unknown"):
            obs.event("capability list unavailable; will retry on the next command", level=logging.WARNING, source=caps.source)
        self.caps = {key: getattr(caps, key, None) for key in CAPABILITY_KEYS}

    async def ensure_commands(self, client: BydClient) -> None:
        """Commands need the PIN checked once; a wrong PIN raises (pin_*)."""
        if self.commands_ready:
            return
        await asyncio.wait_for(client.verify_command_access(self.vin), config.PIN_TIMEOUT_S)
        self.commands_ready = True

    async def close(self) -> None:
        for task in list(self._background):
            task.cancel()
        if self._connect_task is not None and not self._connect_task.done():
            self._connect_task.cancel()
        client, http, self.client, self.http = self.client, self.http, None, None
        self.commands_ready = False
        await _close(client, http)

    # ------------------------------------------------------------------ logins

    def _install_login(self, client: BydClient) -> None:
        """Route every pybyd login (its re-auth, its push-channel recovery) through one gate."""
        self._original_login = client.login

        async def login() -> None:
            await self.login(client, reason="pybyd")

        client.login = login  # type: ignore[method-assign]

    async def login(self, client: BydClient, *, stale: Session | None = None, reason: str = "") -> None:
        if self._login_error is not None and time.monotonic() < self._login_retry_at:
            raise self._cooling_down(self._login_error, self._login_retry_at)  # no login storm after a failure
        async with self._login_lock:
            now = time.monotonic()
            fresh = self._last_session is not None and self._last_session is not stale
            if fresh and now - self._last_login_ok < config.LOGIN_REUSE_S:
                client._session = self._last_session  # someone just logged in: share it
                return
            if self._login_error is not None and now < self._login_retry_at:
                raise self._cooling_down(self._login_error, self._login_retry_at)
            started = time.monotonic()
            try:
                await asyncio.wait_for(self._original_login(), config.LOGIN_TIMEOUT_S)
            except Exception as err:
                self.login_failures += 1
                failures = self.login_failures
                delay = min(config.LOGIN_RETRY_S * 2 ** (min(failures, 8) - 1), config.LOGIN_RETRY_MAX_S)
                self._login_error = err
                self._login_retry_at = time.monotonic() + delay
                obs.event("BYD login failed", level=logging.WARNING, reason=reason, error=repr(err), retry_in_s=delay)
                raise
            self.login_failures = 0
            self._login_error = None
            self._last_session = client._session
            self._last_login_ok = time.monotonic()
            self.logins += 1
            self.session_since = time.time()
            obs.event("BYD login", reason=reason, ms=int((time.monotonic() - started) * 1000), count=self.logins)

    async def refresh(self, stale: Session | None) -> Session:
        """A fresh session after BYD rejected `stale` (code 1005 and friends)."""
        client = self.client
        if client is None:
            raise CommandError("login_failed", "BYD session is gone", 502)
        current = client._session
        if current is not None and current is not stale and not current.is_expired:
            return current  # someone else already renewed it
        client.invalidate_session()
        await self.login(client, stale=stale, reason="expired")
        assert client._session is not None  # noqa: S101
        return client._session

    # ------------------------------------------------------------------ keep-alive

    def on_health_ping(self, *, busy: bool) -> None:
        """Called by /health (cron-job.org, Render). Never touches the car."""
        if any(not t.done() for t in self._background):
            return
        if self.client is None:
            self.start_warmup()  # boot warm-up failed earlier: try again quietly
            return
        if busy:
            return
        session = self.client._session
        if session is not None and session.age > config.SESSION_REFRESH_S:
            self._spawn(self._renew(session))
        elif time.monotonic() - self.last_api_ok > config.KEEPALIVE_CHECK_S:
            self._spawn(self._touch())

    async def _renew(self, session: Session) -> None:
        if self.client is not None:
            await self.login(self.client, stale=session, reason="renew")

    async def _touch(self) -> None:
        """Cheap cloud call that renews the login if it expired."""
        client = self.client
        if client is None:
            return
        await asyncio.wait_for(client.get_vehicles(), config.HTTP_TIMEOUT_S + config.LOGIN_TIMEOUT_S)
        self.last_api_ok = time.monotonic()
        obs.event("keep-alive: BYD session verified")

    def _spawn(self, coro: Any) -> None:
        task = asyncio.create_task(coro)
        self._background.add(task)
        task.add_done_callback(self._background_done)

    def _background_done(self, task: asyncio.Task) -> None:
        self._background.discard(task)
        if not task.cancelled() and task.exception() is not None:
            obs.event("background task failed", level=logging.WARNING, error=repr(task.exception()))

    # ------------------------------------------------------------------ push channel

    def _on_push(self, vin: str, realtime: Any) -> None:
        """MQTT 'vehicleInfo' push: the car reported on its own, free update."""
        if vin == self.vin:
            self.bridge.reads.on_push(realtime)

    def _on_mqtt_event(self, event: str, vin: str, data: dict[str, Any]) -> None:
        self.mqtt_events += 1
        self.mqtt_last_event = time.time()

    def info(self) -> dict[str, Any]:
        client = self.client
        session = client._session if client is not None else None
        runtime = getattr(client, "_mqtt_runtime", None) if client is not None else None
        return {
            "connected": client is not None,
            "age_min": round((time.time() - self.session_since) / 60, 1) if self.session_since else None,
            "token_age_min": round(session.age / 60, 1) if session is not None else None,
            "commands_ready": self.commands_ready,
            "vin": f"{self.vin[:3]}…{self.vin[-4:]}" if self.vin else None,
            "logins": self.logins,
            "login_failures": self.login_failures,
            "push_running": bool(runtime is not None and getattr(runtime, "is_running", False)),
        }


async def _close(client: BydClient | None, http: aiohttp.ClientSession | None) -> None:
    if client is not None:
        try:
            await client.async_close()
        except Exception:
            pass
    if http is not None and not http.closed:
        try:
            await http.close()
        except Exception:
            pass
