"""A fake BYD cloud at the endpoint level, for tests and the dummy server.

Importing this module:
  1. blocks every outbound connection except localhost (safety net), then
  2. replaces pybyd's `post_token_json` in every endpoint module, and `BydClient.login`.

So the REAL pybyd client runs (its re-login wrapper, its capability gate, its realtime polling)
and so does the server's own trigger/poll loop — only the last hop, the HTTP call to BYD, is
answered here. Nothing leaves the machine.

Scripting (tests):
    CLOUD.latency / CLOUD.action_latency[action]   how long the car takes to confirm
    CLOUD.fail_next[action] = "timeout"|"rejected"  never confirms / controlState=2 (one-shot)
    CLOUD.fault(where, kind, action=None, times=1) one-shot faults, where = trigger|poll|realtime|
        hvac|schedule|toggle|login|any; kind = transport|connect|timeout|lost|1005|1008|6024|auth
        ("lost": the car does it, the reply is lost; "1005": every session is invalidated)
    CLOUD.busy_until = time.monotonic() + s        BYD answers 6024 to every command trigger
    CLOUD.expire_sessions()                        server-side expiry: the next call gets 1005
    CLOUD.cpu_delay = s                            blocking sleep per call (pure-Python crypto)
    CLOUD.login_delay / login_delay_once           slow logins (cold start)
"""

from __future__ import annotations

import asyncio
import collections
import json
import os
import socket
import time
from dataclasses import dataclass, field
from typing import Any

# ---------------------------------------------------------------- network block
_LOCAL = {"127.0.0.1", "::1", "localhost", "0.0.0.0", "10.0.2.2"}
_real_getaddrinfo = socket.getaddrinfo
_real_connect = socket.socket.connect


def _guard_getaddrinfo(host, *a, **k):
    if host not in _LOCAL and host is not None:
        raise OSError(f"[dummy] network blocked: {host}")
    return _real_getaddrinfo(host, *a, **k)


def _guard_connect(self, address):
    host = address[0] if isinstance(address, tuple) else address
    if host not in _LOCAL:
        raise OSError(f"[dummy] network blocked: {address}")
    return _real_connect(self, address)


socket.getaddrinfo = _guard_getaddrinfo
socket.socket.connect = _guard_connect

# ---------------------------------------------------------------- the fake cloud
import aiohttp  # noqa: E402
import pybyd  # noqa: E402
import pybyd._api as _api_pkg  # noqa: E402
from pybyd import BydAuthenticationError, BydClient, BydTransportError  # noqa: E402
from pybyd._api import _common  # noqa: E402
from pybyd.models.control import RemoteCommand  # noqa: E402
from pybyd.models.latest_config import registered_latest_config_function_nos  # noqa: E402
from pybyd.models.realtime import VehicleRealtimeData  # noqa: E402
from pybyd.session import Session  # noqa: E402

VIN = "LGXCE4CB0P0000000"  # a made-up VIN

ACTION_OF = {
    RemoteCommand.LOCK: "lock",
    RemoteCommand.UNLOCK: "unlock",
    RemoteCommand.START_CLIMATE: "climate_on",
    RemoteCommand.STOP_CLIMATE: "climate_off",
    RemoteCommand.FIND_CAR: "locate",
    RemoteCommand.FLASH_LIGHTS: "flash",
    RemoteCommand.CLOSE_WINDOWS: "windows_close",
    RemoteCommand.OPEN_WINDOWS: "windows_vent",
    RemoteCommand.SEAT_CLIMATE: "seat",
    RemoteCommand.OPEN_TRUNK: "trunk_open",
    RemoteCommand.CLOSE_TRUNK: "trunk_close",
}


@dataclass
class Fault:
    where: str
    kind: str
    action: str | None = None
    times: int = 1


@dataclass
class Pending:
    action: str
    params: Any
    ready_at: float
    fail: str | None
    serial: str
    applied: bool = False
    extra: dict[str, Any] = field(default_factory=dict)


class FakeCloud:
    def __init__(self, car: Any) -> None:
        self.car = car
        self.calls: list[str] = []
        self.fail_next: dict[str, str] = {}
        self.latency = float(os.environ.get("FAKE_LATENCY", "0.4"))
        self.reset()

    def reset(self) -> None:
        """Back to a healthy cloud (the lists/dicts keep their identity: fakecar shares them)."""
        self.calls.clear()
        self.fail_next.clear()
        self.action_latency: dict[str, float] = {}
        self.read_latency: float | None = None
        self.login_delay = 0.05
        self.login_delay_once: float | None = None
        self.cpu_delay = 0.0
        self.faults: list[Fault] = []
        self.busy_until = 0.0
        self.push = True
        self.wrong_pin = False
        self.single_session = False
        self.tokens: set[str] = set()
        self.clients: dict[str, BydClient] = {}
        self.logins = 0
        self.triggers: collections.Counter[str] = collections.Counter()  # accepted by the cloud
        self.offers: collections.Counter[str] = collections.Counter()  # every attempt, refused ones too
        self.trigger_log: list[tuple[str, float, float]] = []  # (action, accepted at, ready at)
        self.pending: dict[str, Pending] = {}
        self._serial = 0
        self.http_calls = 0
        self.stamps: list[tuple[str, float]] = []  # (event, time.monotonic()) for ordering checks

    # ------------------------------------------------------------------ scripting

    def fault(self, where: str, kind: str, *, action: str | None = None, times: int = 1) -> None:
        self.faults.append(Fault(where, kind, action, times))

    def expire_sessions(self) -> None:
        self.tokens.clear()

    def _take_fault(self, where: str, action: str | None = None) -> Fault | None:
        for fault in self.faults:
            if fault.times > 0 and fault.where in (where, "any") and (fault.action is None or fault.action == action):
                fault.times -= 1
                return fault
        return None

    async def _apply_fault(self, fault: Fault | None, endpoint: str, ctx: dict[str, Any]) -> None:
        if fault is None or fault.kind == "lost":
            return
        kind = fault.kind
        if kind == "transport":
            raise BydTransportError(f"HTTP 502 from {endpoint}: bad gateway", status_code=502, endpoint=endpoint)
        if kind == "connect":
            try:
                raise aiohttp.ClientConnectorError(_FakeKey(), OSError(111, "Connection refused"))
            except aiohttp.ClientConnectorError as exc:
                raise BydTransportError(f"Request to {endpoint} failed: {exc}", endpoint=endpoint) from exc
        if kind == "timeout":
            await asyncio.sleep(3600)
        if kind == "auth":
            raise BydAuthenticationError("Login failed: code=1001 message=fake", code="1001")
        if kind == "1005":
            self.tokens.clear()
        self._raise_code(kind, endpoint, ctx)

    def _raise_code(self, code: str, endpoint: str, ctx: dict[str, Any]) -> None:
        _common._raise_for_code(
            endpoint=endpoint, code=code, message="fake", vin=ctx.get("vin"),
            not_supported_codes=ctx.get("not_supported_codes"), extra_code_map=ctx.get("extra_code_map"),
        )  # fmt: skip

    def _new_serial(self) -> str:
        self._serial += 1
        return f"S{self._serial:04d}"

    # ------------------------------------------------------------------ endpoints

    async def post(self, endpoint: str, session: Session, inner: dict[str, Any], ctx: dict[str, Any]) -> Any:
        self.http_calls += 1
        if self.cpu_delay:
            time.sleep(self.cpu_delay)  # blocking on purpose: pybyd's crypto is pure Python
        if session.sign_token not in self.tokens:
            self._raise_code("1005", endpoint, ctx)
        if endpoint == "/app/account/getAllListByUserId":
            await asyncio.sleep(0.05)
            self.calls.append("get_vehicles")
            return [{"vin": VIN, "modelName": "ATTO 3", "autoAlias": "ATTO 3"}]
        if endpoint == "/vehicle/vehicleswitch/getLatestConfig":
            self.calls.append("caps")
            functions = [{"functionNo": no, "code": f"F{no}"} for no in sorted(registered_latest_config_function_nos())]
            return {VIN: {"cfFixedList": functions}}
        if endpoint == "/vehicle/vehicleswitch/verifyControlPassword":
            self.calls.append("verify_pin")
            if self.wrong_pin:
                self._raise_code("5005", endpoint, ctx)
            return {}
        if endpoint == "/vehicleInfo/vehicle/vehicleRealTimeRequest":
            return await self._realtime(endpoint, inner, ctx, trigger=True)
        if endpoint == "/vehicleInfo/vehicle/vehicleRealTimeResult":
            return await self._realtime(endpoint, inner, ctx, trigger=False)
        if endpoint == "/control/getStatusNow":
            self.calls.append("hvac")
            self.stamps.append(("hvac", time.monotonic()))
            await self._apply_fault(self._take_fault("hvac"), endpoint, ctx)
            await asyncio.sleep(0.05)
            if self.car.hvac_fails:
                raise asyncio.TimeoutError()
            return self.car.hvac_raw()
        if endpoint == "/control/smartCharge/homePage":
            await self._apply_fault(self._take_fault("schedule"), endpoint, ctx)
            return self.car.schedule_raw()
        if endpoint == "/control/remoteControl":
            return await self._remote_trigger(endpoint, inner, ctx)
        if endpoint == "/control/remoteControlResult":
            return await self._result(endpoint, inner, ctx, smart=False)
        if endpoint == "/control/smartCharge/changeChargeStatue":
            if "smartChargeSwitch" in inner:
                return await self._toggle(endpoint, inner, ctx)
            return await self._smart_trigger(endpoint, "charge_now", None, ctx)
        if endpoint == "/control/smartCharge/saveOrUpdate":
            entry = f"schedule:{inner['startChargeTime']}-{inner['endChargeTime']}:{inner['chargeWay']}:{inner['status'] == '1'}"
            self.calls.append(entry)
            return await self._smart_trigger(endpoint, "save_schedule", inner, ctx)
        if endpoint == "/control/smartCharge/changeResult":
            return await self._result(endpoint, inner, ctx, smart=True)
        raise AssertionError(f"fake cloud: unexpected endpoint {endpoint}")

    async def _realtime(self, endpoint: str, inner: dict[str, Any], ctx: dict[str, Any], *, trigger: bool) -> dict[str, Any]:
        if trigger:
            self.calls.append("realtime")
            self.stamps.append(("realtime_start", time.monotonic()))
            await self._apply_fault(self._take_fault("realtime"), endpoint, ctx)
            await asyncio.sleep(self.read_latency if self.read_latency is not None else self.latency)
            self.stamps.append(("realtime_done", time.monotonic()))
        else:
            await asyncio.sleep(0.01)
        raw = self.car.raw()
        serial = inner.get("requestSerial") or self._new_serial()
        if trigger and not VehicleRealtimeData.is_ready_raw(raw):
            return {"requestSerial": serial}  # asleep: pybyd polls the result endpoint
        return {**raw, "requestSerial": serial}

    async def _remote_trigger(self, endpoint: str, inner: dict[str, Any], ctx: dict[str, Any]) -> dict[str, Any]:
        command = RemoteCommand(inner["commandType"])
        action = ACTION_OF.get(command, command.name.lower())
        params = json.loads(inner["controlParamsMap"]) if inner.get("controlParamsMap") else None
        self.offers[action] += 1
        fault = self._take_fault("trigger", action)
        await self._apply_fault(fault, endpoint, ctx)
        if time.monotonic() < self.busy_until:
            self._raise_code("6024", endpoint, ctx)
        if action == "climate_on":
            self.calls.append(f"climate_params:{params}")
        elif action == "seat":
            self.calls.append(f"seat_params:{params}")
        self.calls.append(action)
        pending = self._accept(action, params)
        await asyncio.sleep(min(0.05, self._latency(action)))
        if fault is not None and fault.kind == "lost":
            raise BydTransportError(f"HTTP 504 from {endpoint}: reply lost", status_code=504, endpoint=endpoint)
        return {"requestSerial": pending.serial, "controlState": 0}

    async def _smart_trigger(self, endpoint: str, action: str, params: Any, ctx: dict[str, Any]) -> dict[str, Any]:
        self.offers[action] += 1
        fault = self._take_fault("trigger", action)
        await self._apply_fault(fault, endpoint, ctx)
        if action == "charge_now":
            self.calls.append("charge_now")
        pending = self._accept(action, params)
        await asyncio.sleep(min(0.05, self._latency(action)))
        if fault is not None and fault.kind == "lost":
            raise BydTransportError(f"HTTP 504 from {endpoint}: reply lost", status_code=504, endpoint=endpoint)
        return {"requestSerial": pending.serial}

    async def _toggle(self, endpoint: str, inner: dict[str, Any], ctx: dict[str, Any]) -> dict[str, Any]:
        enable = inner["smartChargeSwitch"] == "1"
        name = f"toggle_smart:{enable}"
        await self._apply_fault(self._take_fault("toggle"), endpoint, ctx)
        await asyncio.sleep(self.latency)
        self.calls.append(name)
        self.car.execute(name, None)
        self._push()
        return {}

    def _latency(self, action: str) -> float:
        return self.action_latency.get(action, self.latency)

    def _accept(self, action: str, params: Any) -> Pending:
        serial = self._new_serial()
        fail = self.fail_next.pop(action, None)
        latency = self._latency(action)
        pending = Pending(action, params, time.monotonic() + latency, fail, serial)
        self.pending[serial] = pending
        self.triggers[action] += 1
        self.trigger_log.append((action, time.monotonic(), pending.ready_at))
        if fail is None:
            asyncio.get_running_loop().call_later(latency, self._apply, pending)
        return pending

    def _apply(self, pending: Pending) -> None:
        if pending.applied:
            return
        pending.applied = True
        self.car.execute(pending.action, pending.params)
        self._push()

    def _push(self) -> None:
        """The real car pushes fresh state over MQTT after a command."""
        if not self.push:
            return
        for token in list(self.tokens):
            client = self.clients.get(token)
            callback = getattr(client, "_on_vehicle_info", None)
            if callback is not None:
                callback(VIN, VehicleRealtimeData.model_validate(self.car.raw()))

    async def _result(self, endpoint: str, inner: dict[str, Any], ctx: dict[str, Any], *, smart: bool) -> dict[str, Any]:
        serial = inner.get("requestSerial", "")
        pending = self.pending.get(serial)
        await self._apply_fault(self._take_fault("poll", pending.action if pending else None), endpoint, ctx)
        await asyncio.sleep(0.01)
        if pending is None:
            return {"res": 1} if smart else {"controlState": 0, "requestSerial": serial}
        if pending.fail == "timeout" or time.monotonic() < pending.ready_at:
            return {"res": 1, "requestSerial": serial} if smart else {"controlState": 0, "requestSerial": serial}
        if pending.fail == "rejected":
            if smart:
                return {"res": 3, "requestSerial": serial, "message": "Operation failure"}
            return {"controlState": 2, "requestSerial": serial, "message": "Remote control failed"}
        self._apply(pending)
        return {"res": 2, "requestSerial": serial} if smart else {"controlState": 1, "requestSerial": serial}


class _FakeKey:
    """Enough of aiohttp's ConnectionKey for ClientConnectorError's message."""

    host = "fake-byd"
    port = 443
    ssl = True


CLOUD: FakeCloud | None = None


def install(car: Any) -> FakeCloud:
    """Route pybyd's last hop to a fake cloud for `car` (idempotent)."""
    global CLOUD
    if CLOUD is not None:
        CLOUD.car = car
        return CLOUD
    cloud = CLOUD = FakeCloud(car)

    async def fake_post_token_json(*, endpoint: str, config: Any, session: Session, transport: Any, inner: dict[str, Any],
                                   now_ms: int | None = None, vin: str | None = None, not_supported_codes: Any = None,
                                   extra_code_map: Any = None, user_type: Any = None) -> Any:  # fmt: skip
        ctx = {"vin": vin, "not_supported_codes": not_supported_codes, "extra_code_map": extra_code_map}
        return await cloud.post(endpoint, session, inner, ctx)

    for name in dir(_api_pkg):
        module = getattr(_api_pkg, name)
        if hasattr(module, "post_token_json"):
            module.post_token_json = fake_post_token_json
    import pybyd._mqtt as _mqtt

    _mqtt.post_token_json = fake_post_token_json

    async def fake_login(self: BydClient) -> None:
        cloud.calls.append("login")
        delay = cloud.login_delay if cloud.login_delay_once is None else cloud.login_delay_once
        cloud.login_delay_once = None
        await asyncio.sleep(delay)
        await cloud._apply_fault(cloud._take_fault("login"), "/app/account/login", {})
        cloud.logins += 1
        token = f"T{cloud.logins}"
        if cloud.single_session:
            cloud.tokens.clear()
        cloud.tokens.add(token)
        cloud.clients[token] = self
        ttl = self._config.session_ttl if self._config.session_ttl > 0 else float("inf")
        self._session = Session(user_id="fake-user", sign_token=token, encry_token=token, ttl=ttl)

    BydClient.login = fake_login  # type: ignore[method-assign]
    pybyd.BydClient = BydClient
    return cloud


# Make sure every endpoint module is imported before install() walks them.
import pybyd._api.charging  # noqa: E402,F401
import pybyd._api.control  # noqa: E402,F401
import pybyd._api.hvac  # noqa: E402,F401
import pybyd._api.latest_config  # noqa: E402,F401
import pybyd._api.realtime  # noqa: E402,F401
import pybyd._api.smart_charging  # noqa: E402,F401
import pybyd._api.vehicle  # noqa: E402,F401
