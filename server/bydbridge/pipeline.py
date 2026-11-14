"""Jobs, idempotency and the per-car command queue.

  • Every command is a Job with an id and explicit state: queued → running → sent → done,
    and on done an honest outcome (see errors.py).
  • Request ids: the same X-Request-Id joins the running job or returns the finished one; it is
    never run twice (remembered JOB_TTL_S). Identical commands already queued or running are
    joined too (a double tap sends one command), whether or not they carry an id.
  • One car, one queue: BYD rejects overlapping commands (6024), so commands run one after the
    other. A command waits for its turn up to QUEUE_WAIT_S; past that it is dropped unsent
    (outcome not_sent), so a horn never sounds long after the tap. Only when more than QUEUE_MAX
    commands are waiting does a new one get 429 queue_full.
  • After an unconfirmed command (or a lost reply) the car is looked at again later
    (VERIFY_DELAYS_S); if it shows the requested state, the job becomes confirmed_late.
"""

from __future__ import annotations

import asyncio
import collections
import logging
import secrets
import time
from typing import TYPE_CHECKING, Any

from . import commands, config, obs
from .errors import (
    CONFIRMED,
    CONFIRMED_LATE,
    FAILED,
    NOT_SENT,
    UNCONFIRMED,
    UNKNOWN,
    CommandError,
    explain,
    merge_sent,
)

if TYPE_CHECKING:
    from .app import Bridge


class Step:
    def __init__(self, name: str, effects: dict[str, Any]) -> None:
        self.name = name
        self.effects = effects
        self.outcome: str | None = None
        self.sent: bool | str = False
        self.error: str | None = None
        self.serial: str | None = None
        self.ms: int | None = None
        self.polls = 0
        self.triggered_at = 0.0

    def finish(self, outcome: str, *, sent: bool | str, error: str | None = None, serial: str | None = None,
               ms: int | None = None, polls: int = 0, triggered_at: float = 0.0) -> None:  # fmt: skip
        self.outcome, self.sent, self.error, self.serial = outcome, sent, error, serial
        self.ms, self.polls, self.triggered_at = ms, polls, triggered_at

    def to_json(self) -> dict[str, Any]:
        return {"name": self.name, "outcome": self.outcome, "sent": self.sent, "error": self.error,
                "serial": self.serial, "ms": self.ms}  # fmt: skip


class Job:
    def __init__(self, action: str, params: dict[str, str], key: str, *, request_id: str, caller: str) -> None:
        self.id = "j" + secrets.token_hex(5)
        self.action = action
        self.final_action = action
        self.params = params
        self.key = key
        self.request_id = request_id
        self.caller = caller
        self.state = "queued"
        self.outcome: str | None = None
        self.error: CommandError | None = None
        self.sent: bool | str = False
        self.partial = False
        self.steps: list[Step] = []
        self.post: str | None = None
        self.created = time.time()
        self.updated = self.created
        self.queued_m = time.monotonic()
        self.started_m: float | None = None
        self.finished_m: float | None = None
        self.finished_at = 0.0
        self.done = asyncio.Event()

    # ------------------------------------------------------------------ updates

    def touch(self, state: str | None = None) -> None:
        if state is not None:
            self.state = state
        self.updated = time.time()

    def add_step(self, name: str, effects: dict[str, Any]) -> Step:
        step = Step(name, effects)
        self.steps.append(step)
        self.touch()
        return step

    def mark_sent(self) -> None:
        self.sent = True
        self.touch("sent")

    # ------------------------------------------------------------------ views

    @property
    def is_done(self) -> bool:
        return self.done.is_set()

    @property
    def ok(self) -> bool:
        return self.is_done and (self.error is None or self.outcome == CONFIRMED_LATE)

    @property
    def queue_ms(self) -> int:
        end = self.started_m if self.started_m is not None else (self.finished_m or time.monotonic())
        return int((end - self.queued_m) * 1000)

    @property
    def car_ms(self) -> int:
        return sum(step.ms or 0 for step in self.steps)

    @property
    def total_ms(self) -> int:
        return int(((self.finished_m or time.monotonic()) - self.queued_m) * 1000)

    def to_json(self) -> dict[str, Any]:
        error = self.error
        return {
            "id": self.id,
            "request_id": self.request_id,
            "action": self.final_action,
            "requested": self.action,
            "state": self.state,
            "outcome": self.outcome,
            "ok": self.ok if self.is_done else None,  # null until done
            "error": error.code if error is not None else None,
            "message": obs.redact(str(error)) if error is not None else None,
            "sent": self.sent,
            "partial": self.partial,
            "steps": [step.to_json() for step in self.steps],
            "queue_ms": self.queue_ms,
            "car_ms": self.car_ms,
            "total_ms": self.total_ms,
            "created": int(self.created),
            "updated": int(self.updated),
        }


class Pipeline:
    def __init__(self, bridge: Bridge) -> None:
        self.bridge = bridge
        self.jobs: collections.OrderedDict[str, Job] = collections.OrderedDict()
        self.by_rid: dict[str, Job] = {}
        self.inflight: dict[str, Job] = {}  # dedupe key -> queued/running job
        self.waiting: collections.deque[Job] = collections.deque()
        self.running: Job | None = None
        self.recent: collections.deque[dict[str, Any]] = collections.deque(maxlen=20)
        self._wake: asyncio.Event | None = None
        self._worker: asyncio.Task | None = None
        self._tasks: set[asyncio.Task] = set()

    @property
    def busy(self) -> bool:
        return self.running is not None or bool(self.waiting)

    def find(self, ident: str) -> Job | None:
        self._expire()
        return self.jobs.get(ident) or self.by_rid.get(ident)

    # ------------------------------------------------------------------ submit

    def submit(self, action: str, params: dict[str, str], *, rid: str, caller: str) -> tuple[Job, bool]:
        """The job for this request: an existing one (same request id or identical command
        still running) or a new one in the queue. Returns (job, joined)."""
        self._expire()
        key = action + "|" + "&".join(f"{k}={v}" for k, v in sorted(params.items()))
        existing = self.by_rid.get(rid)
        if existing is not None:
            if existing.key != key:
                raise CommandError(
                    "request_id_reused", "This request id was already used for a different command", 409, outcome=NOT_SENT
                )
            obs.event(f"{action} joined", reason="same_request_id", state=existing.state, job=existing.id)
            return existing, True
        running = self.inflight.get(key)
        if running is not None and not running.is_done:
            self.by_rid[rid] = running
            obs.event(f"{action} joined", reason="same_command_running", job=running.id)
            return running, True
        if len(self.waiting) >= config.QUEUE_MAX:
            eta = self._eta()
            raise CommandError(
                "queue_full", f"{len(self.waiting)} commands are already waiting; try again in {eta:.0f}s", 429,
                outcome=NOT_SENT, retry_after_s=eta,
            )  # fmt: skip
        job = Job(action, params, key, request_id=rid, caller=caller)
        self.jobs[job.id] = job
        self.by_rid[rid] = job
        self.inflight[key] = job
        position = len(self.waiting) + (1 if self.running is not None else 0)
        self.waiting.append(job)
        obs.event(f"{action} queued", job=job.id, position=position)
        self._ensure_worker()
        assert self._wake is not None  # noqa: S101
        self._wake.set()
        return job, False

    def _eta(self) -> float:
        running = self.running
        left = 0.0
        if running is not None and running.started_m is not None:
            left = max(1.0, config.COMMAND_TIMEOUT_S - (time.monotonic() - running.started_m))
        return round(min(60.0, left + 10.0 * len(self.waiting)), 1)

    def _ensure_worker(self) -> None:
        if self._wake is None:
            self._wake = asyncio.Event()
        if self._worker is None or self._worker.done():
            self._worker = asyncio.create_task(self._work())

    # ------------------------------------------------------------------ worker

    async def _work(self) -> None:
        assert self._wake is not None  # noqa: S101
        while True:
            while not self.waiting:
                self._wake.clear()
                await self._wake.wait()
            job = self.waiting.popleft()
            self.running = job
            try:
                await self._run(job)
            except asyncio.CancelledError:
                if not job.is_done:
                    self._finish(job, CommandError("server_error", "Server is shutting down", 503, outcome=NOT_SENT,
                                                   sent=merge_sent([s.sent for s in job.steps])))  # fmt: skip
                raise
            except Exception as err:  # never let one job stop the queue
                obs.event("job crashed", level=logging.ERROR, job=job.id, error=repr(err))
                if not job.is_done:
                    self._finish(job, explain(err, sent=merge_sent([s.sent for s in job.steps])))
            finally:
                self.running = None

    async def _run(self, job: Job) -> None:
        obs.rid_var.set(job.request_id)
        obs.job_var.set(job.id)
        now = time.monotonic()
        waited = now - job.queued_m
        if waited > config.QUEUE_WAIT_S:
            self._finish(job, CommandError(
                "busy", f"Waited {waited:.0f}s behind other commands; it was not sent", 409, outcome=NOT_SENT, sent=False
            ))  # fmt: skip
            return
        job.started_m = now
        job.touch("running")
        if waited > 0.5:
            obs.event(f"{job.action} started", queue_ms=job.queue_ms)
        error: CommandError | None = None
        try:
            client = await self.bridge.session.ensure(max(0.5, job.queued_m + config.QUEUE_WAIT_S - time.monotonic()))
            await commands.execute(self.bridge, job, client)
        except Exception as err:
            error = explain(err, sent=merge_sent([s.sent for s in job.steps]))
        if error is None and job.post == "pause_check":
            self._spawn(self._pause_check(job))  # outside the queue: a Lock right after Pause isn't held up
            return
        self._finish(job, error)

    def _finish(self, job: Job, error: CommandError | None) -> None:
        job.error = error
        job.sent = merge_sent([step.sent for step in job.steps])
        if error is None:
            job.outcome = CONFIRMED
        else:
            job.outcome = error.outcome
            job.partial = any(step.outcome == CONFIRMED for step in job.steps) and error.outcome != CONFIRMED
            if not job.steps and error.sent not in (False, None):
                job.sent = error.sent
        job.finished_m = time.monotonic()
        job.finished_at = time.time()
        job.touch("done")
        job.done.set()
        if self.inflight.get(job.key) is job:
            del self.inflight[job.key]
        entry: dict[str, Any] = {"action": job.final_action, "ok": job.ok, "s": round(job.total_ms / 1000, 1),
                                 "outcome": job.outcome, "job": job.id}  # fmt: skip
        if error is not None:
            entry["error"] = error.code
        self.recent.append(entry)
        if job.outcome == UNCONFIRMED or (job.outcome == FAILED and job.sent == UNKNOWN):
            self._spawn(self._verify(job))

    # ------------------------------------------------------------------ after the reply

    async def _pause_check(self, job: Job) -> None:
        """The cloud only says "received" to a pause. Look at the car again shortly after: inside
        its charging window it simply carries on, and the owner should hear that."""
        obs.rid_var.set(job.request_id)
        obs.job_var.set(job.id)
        state = self.bridge.state
        state.release("charging")  # let the car's own answer through
        await asyncio.sleep(config.PAUSE_CHECK_S)
        try:
            await self.bridge.reads.status(fresh=True, timeout=config.STATUS_TIMEOUT_S + 2)
        except Exception as err:
            obs.event("pause check skipped", error=getattr(err, "code", None) or repr(err))
            self._finish(job, None)
            return
        if state.car.get("charging"):
            self._finish(job, CommandError(
                "still_charging", "Schedule is on, but the car is inside its window and keeps charging", 409,
                outcome=CONFIRMED, sent=True,
            ))  # fmt: skip
            return
        self._finish(job, None)

    async def _verify(self, job: Job) -> None:
        """Look at the car again after an unconfirmed command; confirmed_late if it shows the result."""
        obs.rid_var.set(job.request_id)
        obs.job_var.set(job.id)
        step = next((s for s in reversed(job.steps) if s.outcome in (UNCONFIRMED, FAILED)), None)
        if step is None or not step.effects:
            return  # horn, lights: nothing to look at
        state = self.bridge.state
        since = step.triggered_at or job.created
        checked_read = state.read_at
        waited = 0.0
        for delay in config.VERIFY_DELAYS_S:
            await asyncio.sleep(max(0.0, delay - waited))
            waited = delay
            try:
                if state.read_at <= max(checked_read, job.finished_at):
                    await self.bridge.reads.status(fresh=True, timeout=config.STATUS_TIMEOUT_S + 2)
            except Exception as err:
                obs.event(f"{step.name} verification read failed", error=getattr(err, "code", None) or repr(err))
                continue
            checked_read = state.read_at
            car = state.car
            if state.read_at > since and all(car.get(k) == v for k, v in step.effects.items()):
                job.outcome = CONFIRMED_LATE
                step.outcome = CONFIRMED_LATE
                job.touch()
                obs.event(f"{step.name} confirmed late", after_s=delay)
                return
        obs.event(f"{step.name} still unconfirmed after a fresh look at the car")

    def _spawn(self, coro: Any) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    # ------------------------------------------------------------------ housekeeping

    def _expire(self) -> None:
        now = time.time()
        while self.jobs:
            oldest = next(iter(self.jobs.values()))
            too_many = len(self.jobs) > config.JOBS_MAX
            if not oldest.is_done or (not too_many and now - oldest.created < config.JOB_TTL_S):
                break
            self.jobs.popitem(last=False)
            for rid in [r for r, j in self.by_rid.items() if j is oldest]:
                del self.by_rid[rid]

    def info(self) -> dict[str, Any]:
        return {
            "running": self.running.to_json() if self.running is not None else None,
            "waiting": [job.id for job in self.waiting],
            "jobs_remembered": len(self.jobs),
        }

    async def close(self) -> None:
        tasks = [t for t in (self._worker, *self._tasks) if t is not None and not t.done()]
        for task in tasks:
            task.cancel()
        for task in tasks:
            try:
                await task
            except BaseException:
                pass
        for job in list(self.waiting):
            if not job.is_done:
                self._finish(job, CommandError("server_error", "Server is shutting down; not sent", 503, outcome=NOT_SENT))
        self.waiting.clear()
