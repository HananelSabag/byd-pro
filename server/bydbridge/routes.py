"""HTTP: every old path keeps working; request ids, async jobs and job lookups are additions.

See API.md for the contract. In short:
  GET /<command>            runs it and answers when it is done (as before), plus outcome/sent/job
  GET /<command>?async=1    answers 202 at once with the job; follow it with GET /jobs/<id>
  GET /jobs/<id>            a job by its id or by its request id
  X-Request-Id: <id>        (or ?rid=) the same id never runs twice; it is echoed back
"""

from __future__ import annotations

import asyncio
import functools
import json
import re
import secrets
import time
from typing import TYPE_CHECKING, Any

from aiohttp import web

from . import obs
from .auth import authorized, can_read_logs, caller
from .errors import CONFIRMED_LATE, UNCONFIRMED, CommandError, explain
from .state import describe

if TYPE_CHECKING:
    from .app import Bridge
    from .pipeline import Job

# Every path the old server accepted keeps working (app, MacroDroid, shortcuts).
ROUTES = {
    "status": "status",
    "lock": "lock",
    "unlock": "unlock",
    "ac": "climate_on", "ac-on": "climate_on", "climate": "climate_on", "climate-on": "climate_on",
    "ac-off": "climate_off", "climate-off": "climate_off", "stop-ac": "climate_off",
    "recirc": "recirc",
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
CONTROL_PARAMS = {"secret", "rid", "async"}  # never part of a command's parameters
_RID = re.compile(r"^[A-Za-z0-9._:\-]{1,64}$")
_dumps = functools.partial(json.dumps, ensure_ascii=False)


def route_action(path: str) -> str | None:
    """The action for a lower-cased path without slashes (tolerates /api/lock style)."""
    return ROUTES.get(path) or ROUTES.get(path.rsplit("/", 1)[-1])


def _flag(value: str | None) -> bool:
    return (value or "").strip().lower() in ("1", "true", "yes")


class Reply:
    """Builds JSON replies that all carry rev, boot and the request id."""

    def __init__(self, bridge: Bridge, rid: str) -> None:
        self.bridge = bridge
        self.rid = rid

    def json(self, body: dict[str, Any], status: int = 200, *, timing_ms: int | None = None) -> web.Response:
        body.setdefault("request_id", self.rid)
        body["rev"] = self.bridge.state.rev
        body["boot"] = self.bridge.boot
        headers = {"X-Request-Id": self.rid, "X-Boot-Id": self.bridge.boot}
        if timing_ms is not None:
            headers["Server-Timing"] = f"byd;dur={timing_ms}"
        return web.json_response(body, status=status, headers=headers, dumps=_dumps)


async def handle(request: web.Request) -> web.StreamResponse:
    bridge: Bridge = request.app["bridge"]
    raw_path = request.path.strip("/")
    path = raw_path.lower()
    if path in HEALTH_PATHS:
        bridge.on_health_ping()
        if request.query.get("v") == "2":
            return web.json_response(bridge.health(), headers={"X-Boot-Id": bridge.boot}, dumps=_dumps)
        return web.Response(text="OK", headers={"X-Boot-Id": bridge.boot})

    if path in ("logs", "info") and can_read_logs(request):
        headers = {"X-Boot-Id": bridge.boot}
        if path == "logs":
            return web.Response(text="\n".join(obs.LOG_LINES) + "\n", content_type="text/plain", charset="utf-8", headers=headers)
        pretty = functools.partial(json.dumps, ensure_ascii=False, indent=2)
        return web.json_response(bridge.info(), dumps=pretty, headers=headers)

    supplied = request.headers.get("X-Request-Id", "").strip() or request.query.get("rid", "").strip()
    client_rid = bool(supplied)
    valid = not client_rid or bool(_RID.match(supplied))
    rid = supplied if client_rid and valid else "s-" + secrets.token_hex(4)
    reply = Reply(bridge, rid)

    token = obs.rid_var.set(rid)
    try:
        if not authorized(request):
            obs.log.warning("✗ /%s rejected: wrong or missing secret (from %s)", path, caller(request))
            return reply.json({"ok": False, "error": "unauthorized"}, 401)
        if not valid:
            return reply.json({"ok": False, "error": "bad_request_id", "message": "X-Request-Id: 1-64 of A-Z a-z 0-9 . _ : -"}, 400)
        if path == "jobs" or path.startswith("jobs/"):
            return _job_reply(bridge, reply, raw_path.partition("/")[2])
        action = route_action(path)
        if action is None:
            obs.log.warning("✗ unknown route /%s (from %s)", path, caller(request))
            return reply.json({"ok": False, "error": "unknown_route", "message": f"/{path}"}, 404)
        params = {k: v for k, v in request.query.items() if k not in CONTROL_PARAMS}
        if action == "status":
            return await _status(bridge, reply, request, params)
        return await _command(bridge, reply, request, action, params, client_rid=client_rid)
    finally:
        obs.rid_var.reset(token)


def _job_reply(bridge: Bridge, reply: Reply, ident: str) -> web.Response:
    job = bridge.pipeline.find(ident.strip("/")) if ident else None
    if job is None:
        return reply.json({"ok": False, "error": "unknown_job", "message": "No such job (unknown id, expired, or the server restarted)"}, 404)
    return reply.json({"ok": True, "job": job.to_json(), "car": bridge.state.view()})


async def _status(bridge: Bridge, reply: Reply, request: web.Request, params: dict[str, str]) -> web.Response:
    started = time.monotonic()
    shown = " ".join(f"{k}={v}" for k, v in params.items())
    obs.log.info("→ status%s  (from %s) %s", f"  {shown}" if shown else "", caller(request), obs.fields())
    try:
        await bridge.reads.status(fresh=_flag(params.get("fresh")))
    except Exception as raw:
        err = explain(raw)
        ms = int((time.monotonic() - started) * 1000)
        obs.log.warning("← status ✗ %s after %.1fs: %s  %s", err.code, ms / 1000, err, obs.fields(),
                        exc_info=raw if err.code == "server_error" else None)  # fmt: skip
        bridge.pipeline.recent.append({"action": "status", "ok": False, "error": err.code, "s": round(ms / 1000, 1)})
        body: dict[str, Any] = {"ok": False, "action": "status", "error": err.code, "message": obs.redact(str(err)), "ms": ms}
        if err.retry_after_s is not None:
            body["retry_after_s"] = err.retry_after_s
        if bridge.state.car:
            body.update(bridge.state_payload())
        return reply.json(body, err.http_status, timing_ms=ms)
    ms = int((time.monotonic() - started) * 1000)
    cached = ms < 50
    obs.log.info("← status ✓ %.1fs%s · %s  %s", ms / 1000, " (cache)" if cached else "", describe(bridge.state.view()), obs.fields())
    body = {"ok": True, "action": "status", "ms": ms, **bridge.state_payload(debug=params.get("debug") == "1")}
    return reply.json(body, timing_ms=ms)


async def _command(bridge: Bridge, reply: Reply, request: web.Request, action: str, params: dict[str, str], *,
                   client_rid: bool) -> web.Response:  # fmt: skip
    started = time.monotonic()
    shown = " ".join(f"{k}={v}" for k, v in params.items())
    run_async = _flag(request.query.get("async"))
    obs.log.info("→ %s%s  (from %s) %s", action, f"  {shown}" if shown else "", caller(request),
                 obs.fields(mode="async" if run_async else None))  # fmt: skip
    try:
        job, joined = bridge.pipeline.submit(action, params, rid=reply.rid, caller=caller(request))
    except CommandError as err:
        ms = int((time.monotonic() - started) * 1000)
        obs.log.warning("← %s ✗ %s after %.1fs: %s  %s", action, err.code, ms / 1000, err, obs.fields(outcome=err.outcome))
        body: dict[str, Any] = {"ok": False, "action": action, "error": err.code, "message": obs.redact(str(err)), "ms": ms,
                                "outcome": err.outcome, "sent": err.sent}  # fmt: skip
        if err.retry_after_s is not None:
            body["retry_after_s"] = err.retry_after_s
        if bridge.state.car:
            body.update(bridge.state_payload())
        response = reply.json(body, err.http_status, timing_ms=ms)
        if err.retry_after_s is not None:
            response.headers["Retry-After"] = str(max(1, round(err.retry_after_s)))
        return response

    if run_async:
        obs.log.info("← %s accepted (async) %s", action, obs.fields(job=job.id, state=job.state, joined=joined or None))
        return reply.json({"ok": True, "action": action, "job": job.to_json()}, 202)

    await asyncio.shield(job.done.wait())  # the job finishes even if the phone gives up
    return _command_reply(bridge, reply, job, started, params, client_rid=client_rid)


def _command_reply(bridge: Bridge, reply: Reply, job: Job, started: float, params: dict[str, str], *,
                   client_rid: bool) -> web.Response:  # fmt: skip
    ms = int((time.monotonic() - started) * 1000)
    err = job.error
    tags = obs.fields(job=job.id, outcome=job.outcome, sent=job.sent if job.sent is not True else None,
                      partial=job.partial or None)  # fmt: skip
    if err is None or job.outcome == CONFIRMED_LATE:
        obs.log.info("← %s ✓ %.1fs · %s  %s", job.final_action, ms / 1000, describe(bridge.state.view()), tags)
        body = {"ok": True, "action": job.final_action, "ms": ms, "outcome": job.outcome, "sent": job.sent,
                "job": job.to_json(), **bridge.state_payload(debug=params.get("debug") == "1")}  # fmt: skip
        return reply.json(body, timing_ms=ms)

    code, status = err.code, err.http_status
    if client_rid and job.outcome == UNCONFIRMED:
        code, status = "unconfirmed", 202  # new clients: "sent, not confirmed yet" — follow /jobs/<id>
    obs.log.warning("← %s ✗ %s after %.1fs: %s  %s", job.final_action, code, ms / 1000, err, tags,
                    exc_info=err if err.code == "server_error" else None)  # fmt: skip
    body: dict[str, Any] = {"ok": False, "action": job.final_action, "error": code, "message": obs.redact(str(err)), "ms": ms,
                            "outcome": job.outcome, "sent": job.sent, "job": job.to_json()}  # fmt: skip
    if job.partial:
        body["partial"] = True
    if err.retry_after_s is not None:
        body["retry_after_s"] = err.retry_after_s
    if bridge.state.car:
        body.update(bridge.state_payload())  # the app can still refresh its screen
    return reply.json(body, status, timing_ms=ms)
