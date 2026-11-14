"""Logging: one readable line per event, kept in memory for /logs.

Request lines keep the shape the owner already reads:

    → lock  (from app) rid=a1b2
      lock queued job=j7 rid=a1b2 position=0
      lock sent job=j7 rid=a1b2 serial=S123 trigger_ms=412
      lock confirmed job=j7 rid=a1b2 serial=S123 polls=4 car_ms=3610
    ← lock ✓ 3.7s · 78% · 331 km · locked  rid=a1b2 job=j7 outcome=confirmed

Phase lines carry key=value fields; the request id (rid) and job id are filled in from the
current asyncio context, so every line of one request can be found with one search.
"""

from __future__ import annotations

import collections
import contextvars
import logging
import sys
from typing import Any

from . import config

LOG_LINES: collections.deque[str] = collections.deque(maxlen=config.LOG_LINES_MAX)

rid_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("rid", default=None)
job_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("job", default=None)

# Strings that must never reach the logs (filled in once the VIN is known).
_REDACT: dict[str, str] = {}


class _RingHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            LOG_LINES.append(redact(self.format(record)))
        except Exception:  # a log line must never break a request
            pass


class _RedactingFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        return redact(super().format(record))


_formatter = _RedactingFormatter("%(asctime)s %(levelname)-5s %(message)s", datefmt="%m-%d %H:%M:%S")
_ready = False


def setup() -> None:
    """Configure logging once: stdout (Render shows it live) and the /logs ring."""
    global _ready
    if _ready:
        return
    _ready = True
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, force=True)
    logging.getLogger().handlers[0].setFormatter(_formatter)
    ring = _RingHandler()
    ring.setFormatter(_formatter)
    logging.getLogger().addHandler(ring)
    # pybyd at INFO: its re-logins, push-channel recoveries and BYD "busy" retries show in /logs.
    logging.getLogger("pybyd").setLevel(logging.INFO)
    logging.getLogger("aiohttp.access").setLevel(logging.WARNING)


log = logging.getLogger("byd")


def hide(secret: str, shown: str) -> None:
    """Replace `secret` with `shown` in every log line from now on (used for the VIN)."""
    if secret and len(secret) >= 6:
        _REDACT[secret] = shown


def redact(text: str) -> str:
    for secret, shown in _REDACT.items():
        if secret in text:
            text = text.replace(secret, shown)
    return text


def _fmt(value: Any) -> str:
    if isinstance(value, float):
        return f"{value:.1f}"
    if isinstance(value, bool):
        return "true" if value else "false"
    text = str(value)
    return text if text and " " not in text else repr(text)


def fields(**values: Any) -> str:
    """key=value pairs, skipping empty values; job and rid come from the asyncio context."""
    merged: dict[str, Any] = {"job": job_var.get(), "rid": rid_var.get()}
    merged.update(values)
    return " ".join(f"{key}={_fmt(value)}" for key, value in merged.items() if value is not None and value != "")


def event(what: str, *, level: int = logging.INFO, **values: Any) -> None:
    """One phase line: `  <what> job=… rid=… key=value…`."""
    log.log(level, "  %s %s", what, fields(**values))
