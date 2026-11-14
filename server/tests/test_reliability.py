"""Reliability scenarios: the real pybyd flow against a scripted fake BYD cloud (no network).

Run (Git Bash):  cd dev/server/tests && PYTHONIOENCODING=utf-8 ../../.venv/Scripts/python test_reliability.py
Each scenario gets a fresh server (new boot), a fresh car and a healthy cloud.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import fakecar  # must come first: blocks network + installs the fake cloud
from aiohttp import ClientError
from aiohttp.test_utils import TestClient, TestServer
from fakecar import CALLS, CAR, CLOUD
from pybyd.session import Session

import main
from bydbridge import config, obs

H = {"Authorization": f"Bearer {main.AUTH_SECRET}"}
results: list[tuple[str, bool]] = []
_DEFAULTS = {name: getattr(config, name) for name in dir(config) if name.isupper()}

# Fast but faithful: the same code paths, shorter waits.
FAST = {
    "POLL_INTERVAL_S": 0.1,
    "COMMAND_TIMEOUT_S": 3.0,
    "PAUSE_CHECK_S": 0.05,
    "VERIFY_DELAYS_S": (0.3, 1.5),
    "RATE_LIMIT_WAIT_S": 0.3,
    "LOGIN_RETRY_S": 2.0,
    "BUSY_RETRY_WAIT_S": 0.2,
    "CONNECT_RETRY_WAIT_S": 0.2,
    "POLL_CALL_TIMEOUT_S": 0.5,
}


def check(name: str, cond: object, extra: object = "") -> None:
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra != "" else ""))


def rid_headers(rid: str) -> dict[str, str]:
    return {**H, "X-Request-Id": rid}


class Scenario:
    """A fresh server + car + cloud; config overrides are undone afterwards."""

    def __init__(self, title: str, *, warm: bool = True, **overrides: object) -> None:
        self.title, self.warm, self.overrides = title, warm, overrides
        self.client: TestClient | None = None

    async def __aenter__(self) -> TestClient:
        print(f"\n--- {self.title}")
        fakecar.reset()
        CLOUD.latency = 0.3
        for name, value in _DEFAULTS.items():
            setattr(config, name, value)
        for name, value in {**FAST, **self.overrides}.items():
            setattr(config, name, value)
        self.client = TestClient(TestServer(main.create_app()))
        await self.client.start_server()
        if self.warm:
            await wait_for(lambda: main.link.session.warm, 5)
        return self.client

    async def __aexit__(self, *exc: object) -> None:
        assert self.client is not None
        await self.client.close()


async def wait_for(cond, timeout: float, step: float = 0.05) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if cond():
            return True
        await asyncio.sleep(step)
    return bool(cond())


async def get(c: TestClient, path: str, headers: dict[str, str] | None = None) -> tuple[int, dict, Any]:
    r = await c.get(path, headers=headers if headers is not None else H)
    try:
        body = await r.json()
    except Exception:
        body = {"text": await r.text()}
    return r.status, body, r.headers  # case-insensitive, like HTTP


async def job_when_done(c: TestClient, ident: str, timeout: float = 10, until=None) -> dict:
    until = until or (lambda j: j["state"] == "done")
    deadline = time.monotonic() + timeout
    job: dict = {}
    while time.monotonic() < deadline:
        status, body, _ = await get(c, f"/jobs/{ident}")
        job = body.get("job") or {}
        if status == 200 and until(job):
            return job
        await asyncio.sleep(0.1)
    return job


# =============================================================================
# Scenarios
# =============================================================================


async def t_slow_confirm() -> None:
    async with Scenario("slow confirmation: unconfirmed, then confirmed_late; never resent",
                        COMMAND_TIMEOUT_S=1.5, VERIFY_DELAYS_S=(0.5, 3.0)) as c:  # fmt: skip
        CAR.locked = False
        await get(c, "/status?fresh=1")
        CLOUD.action_latency["lock"] = 2.5
        status, j, _ = await get(c, "/lock")
        check("old client: 504 timeout, outcome unconfirmed, sent true",
              status == 504 and j["error"] == "timeout" and j["outcome"] == "unconfirmed" and j["sent"] is True, (status, j.get("error")))
        check("unconfirmed effect is not shown as done", j["car"].get("locked") is False, j["car"].get("locked"))
        job = await job_when_done(c, j["job"]["id"], 6, until=lambda x: x["outcome"] == "confirmed_late")
        check("verification read turns it into confirmed_late", job.get("outcome") == "confirmed_late", job.get("outcome"))
        check("the slow command went to the car exactly once", CLOUD.offers["lock"] == 1, CLOUD.offers["lock"])

        CLOUD.action_latency["unlock"] = 2.5
        status, j, headers = await get(c, "/unlock", rid_headers("slow-unlock-1"))
        check("new client (request id): 202 unconfirmed instead of 504",
              status == 202 and j["error"] == "unconfirmed" and j["outcome"] == "unconfirmed", (status, j.get("error")))
        check("request id echoed in body and header",
              j["request_id"] == "slow-unlock-1" and headers.get("X-Request-Id") == "slow-unlock-1")


async def t_rejected() -> None:
    async with Scenario("car says no (controlState=2)") as c:
        CAR.locked = False
        await get(c, "/status?fresh=1")
        fakecar.FAIL_NEXT["lock"] = "rejected"
        status, j, _ = await get(c, "/lock")
        check("rejected: 502, outcome rejected, sent true",
              status == 502 and j["error"] == "rejected" and j["outcome"] == "rejected" and j["sent"] is True, (status, j))
        check("rejected command not resent", CLOUD.offers["lock"] == 1, CLOUD.offers["lock"])
        check("rejected effect not shown", j["car"]["locked"] is False)


async def t_transport_blip() -> None:
    async with Scenario("network blip while waiting for the car") as c:
        CLOUD.action_latency["lock"] = 0.6
        CLOUD.fault("poll", "transport", action="lock", times=2)
        CLOUD.fault("poll", "timeout", action="lock")
        status, j, _ = await get(c, "/lock")
        check("blips and a hung check while polling: still confirmed", status == 200 and j["outcome"] == "confirmed", (status, j.get("error")))
        check("blip did not resend the command", CLOUD.offers["lock"] == 1, CLOUD.offers["lock"])
        check("blips are logged", any("lock check failed; still waiting" in line for line in obs.LOG_LINES))


async def t_session_expiry_mid_poll() -> None:
    async with Scenario("session expires (1005) while waiting for the car") as c:
        logins = CLOUD.logins
        CLOUD.action_latency["lock"] = 0.6
        CLOUD.fault("poll", "1005", action="lock")
        status, j, _ = await get(c, "/lock")
        check("1005 mid-poll: re-login, same serial, confirmed", status == 200 and j["outcome"] == "confirmed", (status, j.get("error")))
        check("1005 mid-poll: command sent exactly once (pybyd would send it twice)", CLOUD.offers["lock"] == 1, CLOUD.offers["lock"])
        check("1005 mid-poll: exactly one new login", CLOUD.logins == logins + 1, CLOUD.logins - logins)
        steps = j["job"]["steps"]
        check("same serial kept", len({s["serial"] for s in steps}) == 1 and steps[0]["serial"], steps)

        CLOUD.action_latency["charge_now"] = 0.6
        CLOUD.fault("poll", "1005", action="charge_now")
        status, j, _ = await get(c, "/charge-now")
        check("smart charging (own loop) survives 1005 too, sent once",
              status == 200 and CLOUD.offers["charge_now"] == 1, (status, j.get("error"), CLOUD.offers["charge_now"]))

        CLOUD.fault("trigger", "1005", action="unlock")
        status, j, _ = await get(c, "/unlock")
        check("1005 on the trigger itself: re-login and send (it was never taken)",
              status == 200 and CLOUD.triggers["unlock"] == 1 and CLOUD.offers["unlock"] == 2, (status, CLOUD.offers["unlock"]))


async def t_busy_codes() -> None:
    async with Scenario("BYD busy: 6024 / 1008 bursts") as c:
        CLOUD.fault("trigger", "6024", action="lock", times=2)
        status, j, _ = await get(c, "/lock")
        check("6024 x2 then accepted: confirmed", status == 200 and j["outcome"] == "confirmed", (status, j.get("error")))
        check("6024: refused offers are not commands (accepted once)", CLOUD.triggers["lock"] == 1 and CLOUD.offers["lock"] == 3,
              (CLOUD.triggers["lock"], CLOUD.offers["lock"]))

        CLOUD.fault("poll", "1008", action="unlock", times=3)
        status, j, _ = await get(c, "/unlock")
        check("1008 bursts while polling: backoff, confirmed, sent once",
              status == 200 and CLOUD.offers["unlock"] == 1, (status, j.get("error"), CLOUD.offers["unlock"]))

        CLOUD.fault("trigger", "1008", action="flash")
        status, j, _ = await get(c, "/flash")
        check("1008 on the trigger: offered again, executed once",
              status == 200 and CLOUD.triggers["flash"] == 1, (status, CLOUD.triggers["flash"]))

    async with Scenario("BYD busy for longer than the deadline", COMMAND_TIMEOUT_S=1.5) as c:
        CLOUD.busy_until = time.monotonic() + 30
        status, j, _ = await get(c, "/lock")
        check("6024 until the deadline: 409 busy, not_sent, sent false",
              status == 409 and j["error"] == "busy" and j["outcome"] == "not_sent" and j["sent"] is False, (status, j))
        check("6024 until the deadline: never reached the car", CLOUD.triggers["lock"] == 0)


async def t_request_ids() -> None:
    async with Scenario("request ids: join, replay, conflict") as c:
        CLOUD.action_latency["locate"] = 0.6
        (s1, j1, _), (s2, j2, _) = await asyncio.gather(
            get(c, "/horn", rid_headers("tap-1")), get(c, "/horn", rid_headers("tap-1"))
        )
        check("same id twice at once: one job", s1 == s2 == 200 and j1["job"]["id"] == j2["job"]["id"], (s1, s2))
        check("same id twice at once: horn sounded once", CLOUD.offers["locate"] == 1, CLOUD.offers["locate"])
        t = time.monotonic()
        s3, j3, _ = await get(c, "/horn", rid_headers("tap-1"))
        check("same id after completion: stored result, not run again",
              s3 == 200 and j3["job"]["id"] == j1["job"]["id"] and CLOUD.offers["locate"] == 1 and time.monotonic() - t < 0.3,
              CLOUD.offers["locate"])  # fmt: skip
        s4, j4, _ = await get(c, "/horn?async=1", rid_headers("tap-1"))
        check("same id with async=1: the same job", s4 == 202 and j4["job"]["id"] == j1["job"]["id"])
        s5, j5, _ = await get(c, "/unlock", rid_headers("tap-1"))
        check("same id for a different command: 409 request_id_reused", s5 == 409 and j5["error"] == "request_id_reused", (s5, j5))
        s6, j6, _ = await get(c, "/lock", rid_headers("bad id with spaces"))
        check("malformed request id: 400 bad_request_id", s6 == 400 and j6["error"] == "bad_request_id", (s6, j6))
        CLOUD.action_latency["flash"] = 0.6
        (a, ja, _), (b, jb, _) = await asyncio.gather(get(c, "/flash", rid_headers("x-1")), get(c, "/flash", rid_headers("x-2")))
        check("identical command with a second id while running: joined (double tap = one command)",
              a == b == 200 and ja["job"]["id"] == jb["job"]["id"] and CLOUD.offers["flash"] == 1, CLOUD.offers["flash"])


async def t_queue() -> None:
    async with Scenario("back-to-back different commands: queued, not busy") as c:
        CLOUD.latency = 0.4
        replies = await asyncio.gather(get(c, "/lock"), get(c, "/flash"), get(c, "/windows-close"), get(c, "/trunk"))
        check("four different commands at once: all confirmed (no 409)", all(s == 200 for s, _, _ in replies), [s for s, _, _ in replies])
        log = sorted(CLOUD.trigger_log, key=lambda x: x[1])
        overlap = any(nxt[1] < cur[2] - 0.01 for cur, nxt in zip(log, log[1:]))
        check("they reached the car one after another (no overlap)", not overlap and len(log) == 4, [(a, round(s, 2), round(r, 2)) for a, s, r in log])
        queued = [j["job"]["queue_ms"] for _, j, _ in replies]
        check("queue time reported per job", max(queued) > 500, queued)

    async with Scenario("queue overflow: 429 queue_full", QUEUE_MAX=1) as c:
        CLOUD.latency = 1.0
        s1, j1, _ = await get(c, "/lock?async=1")
        s2, j2, _ = await get(c, "/flash?async=1")
        s3, j3, h3 = await get(c, "/windows-close")
        check("third while one runs and one waits: 429 queue_full + retry_after_s",
              s1 == s2 == 202 and s3 == 429 and j3["error"] == "queue_full" and j3["retry_after_s"] > 0 and "Retry-After" in h3, (s1, s2, s3, j3))
        check("overflowed command never reached the car", CLOUD.offers["windows_close"] == 0)
        await job_when_done(c, j2["job"]["id"])

    async with Scenario("queue wait deadline: dropped unsent, never late", QUEUE_WAIT_S=0.5) as c:
        CLOUD.action_latency["lock"] = 1.5
        first = asyncio.create_task(get(c, "/lock"))
        await asyncio.sleep(0.1)
        s2, j2, _ = await get(c, "/flash")
        s1, j1, _ = await first
        check("first confirmed", s1 == 200, s1)
        check("second waited too long: 409 busy, not_sent", s2 == 409 and j2["outcome"] == "not_sent" and j2["sent"] is False, (s2, j2))
        check("the late one was never sent", CLOUD.offers["flash"] == 0, CLOUD.offers["flash"])


async def t_single_login() -> None:
    async with Scenario("expired session + parallel reads + a command: one login") as c:
        await get(c, "/status?fresh=1")
        logins = CLOUD.logins
        CLOUD.expire_sessions()
        CLOUD.action_latency["lock"] = 0.3
        replies = await asyncio.gather(get(c, "/status?fresh=1"), get(c, "/status?fresh=1"), get(c, "/lock"))
        check("all answered", all(s == 200 for s, _, _ in replies), [s for s, _, _ in replies])
        check("server-side expiry (1005) seen by parallel calls: exactly one new login", CLOUD.logins == logins + 1, CLOUD.logins - logins)

        logins = CLOUD.logins
        client = main.link.session.client
        old = client._session
        client._session = Session(user_id=old.user_id, sign_token=old.sign_token, encry_token=old.encry_token,
                                  created_at=time.monotonic() - 13 * 3600)  # fmt: skip
        main.link.session._last_login_ok = 0.0
        replies = await asyncio.gather(get(c, "/status?fresh=1"), get(c, "/unlock"))
        check("pybyd's 12 h limit reached during parallel calls: exactly one new login",
              CLOUD.logins == logins + 1 and all(s == 200 for s, _, _ in replies), (CLOUD.logins - logins, [s for s, _, _ in replies]))

    async with Scenario("a failing login is not hammered", LOGIN_RETRY_S=5.0) as c:
        await get(c, "/status?fresh=1")
        CLOUD.expire_sessions()
        CLOUD.fault("login", "auth", times=10)
        attempts = CALLS.count("login")
        s1, j1, _ = await get(c, "/unlock")
        s2, j2, _ = await get(c, "/unlock")
        check("login failure: login_failed, not sent", s1 == 502 and j1["error"] == "login_failed" and j1["sent"] is False, (s1, j1))
        check("second request inside the cool-down fails fast without another login",
              s2 == 502 and CALLS.count("login") - attempts == 1, (s2, CALLS.count("login") - attempts))
        check("nothing reached the car", CLOUD.offers["unlock"] == 0)


async def t_cold_start() -> None:
    CLOUD_DELAY = 2.0
    async with Scenario("cold start: a command during a slow first login waits for it", warm=False) as c:
        CLOUD.login_delay = 0.05
        CLOUD.login_delay_once = None
        await main.link.close()  # undo the quick warm-up; start again with a slow login
        main.link.__init__()
        CLOUD.login_delay_once = CLOUD_DELAY
        main.link.start()
        s0, h0, _ = await get(c, "/health?v=2", headers={})
        t = time.monotonic()
        s, j, _ = await get(c, "/lock", rid_headers("cold-1"))
        took = time.monotonic() - t
        check("health v2 during warm-up says warm=false", s0 == 200 and h0["warm"] is False and h0["boot"], h0)
        check("command waited for the login and was confirmed", s == 200 and j["outcome"] == "confirmed" and took >= 1.5, (s, round(took, 1)))

    async with Scenario("cold start: login slower than the queue deadline", warm=False, QUEUE_WAIT_S=1.0, STATUS_TIMEOUT_S=1.0) as c:
        await main.link.close()
        main.link.__init__()
        CLOUD.login_delay_once = 4.0
        main.link.start()
        s, j, _ = await get(c, "/lock")
        check("503 warming_up, not_sent, retry_after_s", s == 503 and j["error"] == "warming_up" and j["outcome"] == "not_sent"
              and j.get("retry_after_s"), (s, j))  # fmt: skip
        check("nothing reached the car", CLOUD.offers["lock"] == 0)
        s, j, _ = await get(c, "/status")
        check("status with nothing known yet: 503 warming_up", s == 503 and j["error"] == "warming_up", (s, j.get("error")))
        await wait_for(lambda: main.link.session.warm, 6)
        s, j, _ = await get(c, "/health?v=2", headers={})
        check("health v2 after warm-up says warm=true", j["warm"] is True and j["version"] and j["uptime_s"] >= 0, j)


async def t_ac_lag() -> None:
    async with Scenario("A/C spin-up lag: no flip") as c:
        CAR.hvac_lag_s = 1.5
        s, j, _ = await get(c, "/ac?temp=22&mins=10")
        check("A/C confirmed on", s == 200 and j["car"]["climate_on"] is True)
        seen = []
        for _ in range(3):
            _, j, _ = await get(c, "/status?fresh=1")
            seen.append(j["car"]["climate_on"])
            await asyncio.sleep(0.25)
        check("car still reports off while spinning up: app keeps showing on", all(seen), seen)
        await asyncio.sleep(1.2)
        _, j, _ = await get(c, "/status?fresh=1")
        check("car catches up: on, hold ended", j["car"]["climate_on"] is True and "climate_on" not in main.link.state.holds, j["car"]["climate_on"])

    async with Scenario("A/C really off after the grace window", CLIMATE_HOLD_S=0.5) as c:
        await get(c, "/ac?temp=22&mins=10")
        CAR.climate = False
        _, j, _ = await get(c, "/status?fresh=1")
        check("inside the grace window: held on", j["car"]["climate_on"] is True)
        await asyncio.sleep(0.6)
        _, j, _ = await get(c, "/status?fresh=1")
        check("after it: the car's off wins", j["car"]["climate_on"] is False)


async def t_holds() -> None:
    async with Scenario("key fob during a hold: the car's newer report wins", HOLD_MARGIN_S=0) as c:
        CAR.locked = False
        await get(c, "/status?fresh=1")
        s, j, _ = await get(c, "/lock")
        check("locked by the app", s == 200 and j["car"]["locked"] is True)
        await asyncio.sleep(1.1)  # the car's next report is made after the confirmation
        CAR.locked = False  # unlocked with the key fob
        _, j, _ = await get(c, "/status?fresh=1")
        check("fob unlock shows within seconds, not after the 60 s hold", j["car"]["locked"] is False, j["car"]["locked"])

    async with Scenario("an old car report can't undo a confirmed command") as c:
        CAR.locked = False
        await get(c, "/status?fresh=1")
        CLOUD.push = False  # no fresh push confirming the lock
        fakecar.FAIL_NEXT.clear()
        s, j, _ = await get(c, "/lock")
        CAR.locked = False
        CAR.report_time = int(time.time()) - 30  # the cloud serves a report made before the lock
        _, j, _ = await get(c, "/status?fresh=1")
        check("older report (car timestamp before the confirmation): lock stays shown", j["car"]["locked"] is True, j["car"]["locked"])
        CAR.report_time = int(time.time()) - 600
        _, j, _ = await get(c, "/status?fresh=1")
        check("out-of-order report older than the snapshot is ignored entirely", j["car"]["locked"] is True)


async def t_partial() -> None:
    async with Scenario("multi-step: A/C confirmed, seat refused") as c:
        fakecar.FAIL_NEXT["seat"] = "rejected"
        s, j, _ = await get(c, "/ac?temp=22&mins=10&driver_seat=1&passenger_seat=1")
        steps = j["job"]["steps"]
        check("reply: rejected + partial", s == 502 and j["error"] == "rejected" and j.get("partial") is True, (s, j.get("error"), j.get("partial")))
        check("steps: climate confirmed, driver seat rejected, passenger not attempted",
              [(x["name"], x["outcome"]) for x in steps] == [("climate_on", "confirmed"), ("seat_driver", "rejected")], steps)
        check("the A/C state is kept (on, with its end time)", j["car"]["climate_on"] is True and j["car"].get("climate_until"), j["car"])


async def t_disconnect() -> None:
    async with Scenario("client disconnects mid-command") as c:
        CLOUD.action_latency["lock"] = 1.0
        CAR.locked = False
        task = asyncio.create_task(c.get("/lock", headers=rid_headers("gone-1")))
        await asyncio.sleep(0.3)
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, ClientError):
            pass
        job = await job_when_done(c, "gone-1", 5)
        check("the job still finished and is queryable by request id", job.get("outcome") == "confirmed", job.get("outcome"))
        check("sent once", CLOUD.offers["lock"] == 1)


async def t_cpu_delay() -> None:
    async with Scenario("slow CPU: every BYD call blocks the loop 30 ms") as c:
        CLOUD.cpu_delay = 0.03
        replies = await asyncio.gather(get(c, "/lock"), get(c, "/status?fresh=1"), get(c, "/flash"))
        check("command + read + command all fine", all(s == 200 for s, _, _ in replies), [s for s, _, _ in replies])
        check("each command sent once", CLOUD.offers["lock"] == 1 and CLOUD.offers["flash"] == 1)


async def t_lost_reply() -> None:
    async with Scenario("lost reply after sending") as c:
        CLOUD.fault("trigger", "lost", action="locate")
        s, j, _ = await get(c, "/horn")
        check("horn: reply lost -> failed, sent unknown, not resent",
              s == 502 and j["outcome"] == "failed" and j["sent"] == "unknown" and CLOUD.offers["locate"] == 1, (s, j.get("sent"), CLOUD.offers["locate"]))
        CLOUD.fault("trigger", "lost", action="lock")
        s, j, _ = await get(c, "/lock")
        check("lock (harmless twice): reply lost -> sent again once, confirmed",
              s == 200 and CLOUD.offers["lock"] == 2, (s, CLOUD.offers["lock"]))
        CLOUD.fault("trigger", "connect", action="flash")
        s, j, _ = await get(c, "/flash")
        check("connection never opened: safe to offer again, executed once",
              s == 200 and CLOUD.triggers["flash"] == 1 and CLOUD.offers["flash"] == 2, (s, CLOUD.offers["flash"]))


async def t_observability() -> None:
    async with Scenario("logs, rev, boot, jobs, health") as c:
        s, j, _ = await get(c, "/lock", rid_headers("trace-me"))
        lines = [line for line in obs.LOG_LINES if "rid=trace-me" in line]
        check("every phase line of the request carries its rid", len(lines) >= 4, len(lines))
        check("the sent/confirmed lines carry the BYD serial", any("lock sent" in x and "serial=S" in x for x in lines)
              and any("lock confirmed" in x and "serial=S" in x for x in lines), lines)  # fmt: skip
        check("pybyd logs at INFO", logging.getLogger("pybyd").getEffectiveLevel() == logging.INFO)

        boot = j["boot"]
        revs = [j["rev"]]
        for path in ("/unlock", "/status", "/lock?async=1"):
            _, body, _ = await get(c, path)
            revs.append(body["rev"])
            check(f"{path}: reply carries boot + rev", body["boot"] == boot and isinstance(body["rev"], int))
        check("rev never goes back", revs == sorted(revs), revs)
        job_id = body["job"]["id"]
        check("async: 202 with the queued job", body["ok"] is True and body["job"]["state"] in ("queued", "running", "sent", "done"), body["job"]["state"])
        job = await job_when_done(c, job_id)
        check("GET /jobs/<id>: done + confirmed + timings", job.get("outcome") == "confirmed" and job["total_ms"] >= job["car_ms"] > 0, job)
        s, body, _ = await get(c, f"/jobs/{job_id}")
        check("GET /jobs/<id> also returns the car snapshot", s == 200 and "locked" in body["car"])
        s, body, _ = await get(c, "/jobs/trace-me")
        check("GET /jobs/<request id> works too", s == 200 and body["job"]["request_id"] == "trace-me")
        s, body, _ = await get(c, "/jobs/j-nope")
        check("unknown job: 404 unknown_job", s == 404 and body["error"] == "unknown_job")
        s, body, _ = await get(c, "/jobs/j-nope", headers={})
        check("/jobs needs the secret", s == 401)
        r = await c.get("/health")
        check("/health stays plain OK", r.status == 200 and await r.text() == "OK")
        _, body, _ = await get(c, "/info")
        check("/info shows queue and holds", "queue" in body and "holds" in body and body["session"]["logins"] >= 1, list(body))


async def t_hvac_after_wake() -> None:
    async with Scenario("A/C is read after the car wakes, not alongside") as c:
        CLOUD.read_latency = 0.5
        CLOUD.stamps.clear()
        await get(c, "/status?fresh=1")
        stamps = dict(CLOUD.stamps)
        check("hvac read starts after the realtime wake finished",
              stamps.get("hvac", 0) >= stamps.get("realtime_done", 1e9), {k: round(v % 100, 2) for k, v in stamps.items()})


async def t_keepalive() -> None:
    async with Scenario("health ping: renews an old session when idle, never touches the car", JOB_TTL_S=0.5) as c:
        client = main.link.session.client
        old = client._session
        client._session = Session(user_id=old.user_id, sign_token=old.sign_token, encry_token=old.encry_token,
                                  created_at=time.monotonic() - 11.5 * 3600)  # fmt: skip
        main.link.session._last_session = client._session  # as if that old login were the last one
        main.link.session._last_login_ok = 0.0
        logins, reads = CLOUD.logins, CALLS.count("realtime")
        await c.get("/health")
        await wait_for(lambda: CLOUD.logins > logins, 3)
        check("session older than 11 h: renewed quietly on /health", CLOUD.logins == logins + 1, CLOUD.logins - logins)
        main.link.session.last_api_ok = time.monotonic() - 21 * 60
        vehicles = CALLS.count("get_vehicles")
        await c.get("/health")
        await wait_for(lambda: CALLS.count("get_vehicles") > vehicles, 3)
        check("idle 20 min: keep-alive checks the login (cloud only)", CALLS.count("get_vehicles") == vehicles + 1)
        check("health pings never woke the car", CALLS.count("realtime") == reads)

        s, j, _ = await get(c, "/flash", rid_headers("old-1"))
        await asyncio.sleep(0.7)
        s, j2, _ = await get(c, "/jobs/old-1")
        check("finished jobs are forgotten after JOB_TTL_S", s == 404 and j2["error"] == "unknown_job", s)
        s, j3, _ = await get(c, "/flash", rid_headers("old-1"))
        check("...and an expired id is a new request", s == 200 and j3["job"]["id"] != j["job"]["id"] and CLOUD.offers["flash"] == 2)


async def t_drop_hook() -> None:
    import run_dummy  # the emulator server's hooks

    print("\n--- dummy server: /__drop, /__busy, /__latency, /__coldstart")
    fakecar.reset()
    CLOUD.latency = 0.3
    for name, value in {**_DEFAULTS, **FAST}.items():
        setattr(config, name, value)
    async with TestClient(TestServer(run_dummy.make_app())) as c:
        await wait_for(lambda: main.link.session.warm, 5)
        await c.get("/__drop/lock")
        try:
            r = await c.get("/lock", headers=rid_headers("dropped-1"))
            dropped = r.status >= 500
        except ClientError:
            dropped = True
        job = await job_when_done(c, "dropped-1", 5)
        check("/__drop: reply lost, but the command ran and is queryable", dropped and job.get("outcome") == "confirmed", (dropped, job.get("outcome")))
        await c.get("/__busy/0.5")
        s, j, _ = await get(c, "/flash")
        check("/__busy: 6024 for a moment, then the command goes through", s == 200 and CLOUD.offers["flash"] >= 2, (s, CLOUD.offers["flash"]))
        await c.get("/__latency/unlock/0.8")
        t = time.monotonic()
        s, j, _ = await get(c, "/unlock")
        check("/__latency: the car takes that long", s == 200 and time.monotonic() - t >= 0.8)
        boot = main.link.boot
        await c.get("/__coldstart/1")
        s, h, _ = await get(c, "/health?v=2", headers={})
        check("/__coldstart: new boot id, not warm yet", h["boot"] != boot and h["warm"] is False, h)
        s, j, _ = await get(c, "/lock")
        check("/__coldstart: the next command waits for the slow login", s == 200, s)


async def run() -> bool:
    for scenario in (
        t_slow_confirm, t_rejected, t_transport_blip, t_session_expiry_mid_poll, t_busy_codes, t_request_ids,
        t_queue, t_single_login, t_cold_start, t_ac_lag, t_holds, t_partial, t_disconnect, t_cpu_delay,
        t_lost_reply, t_observability, t_hvac_after_wake, t_keepalive, t_drop_hook,
    ):  # fmt: skip
        try:
            await scenario()
        except Exception as err:  # a crashing scenario is a failure, the rest still run
            check(f"{scenario.__name__} ran without crashing", False, repr(err))
    print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")
    return all(ok for _, ok in results)


if __name__ == "__main__":
    raise SystemExit(0 if asyncio.run(run()) else 1)
