"""Stable error codes for the app, and honest outcomes for every command.

Outcome (what happened to the command):
    confirmed       the car (or for schedule switches the BYD cloud) confirmed it
    unconfirmed     it was sent, but no confirmation came in time: the car may still do it
    rejected        the car or the cloud explicitly said no
    not_sent        it was never sent and is safe to send again (queue wait, BYD busy, warming up)
    failed          anything else; `sent` says whether it may have reached the car
    confirmed_late  was unconfirmed (or unknown), and a later car report shows the requested state

sent: True (it reached BYD and was accepted), False (certainly not), "unknown" (the reply was lost).
"""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp
import pydantic
from pybyd import (
    BydApiError,
    BydAuthenticationError,
    BydControlPasswordError,
    BydEndpointNotSupportedError,
    BydRateLimitError,
    BydRemoteControlError,
    BydTransportError,
)
from pybyd.exceptions import BydDataUnavailableError, BydServiceBusyError

CONFIRMED = "confirmed"
UNCONFIRMED = "unconfirmed"
REJECTED = "rejected"
NOT_SENT = "not_sent"
FAILED = "failed"
CONFIRMED_LATE = "confirmed_late"

UNKNOWN = "unknown"  # the third value of `sent`


class CommandError(Exception):
    """A request failed in a way the app can explain to the user."""

    def __init__(
        self,
        code: str,
        message: str,
        http_status: int,
        *,
        outcome: str = FAILED,
        sent: bool | str = False,
        retry_after_s: float | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.http_status = http_status
        self.outcome = outcome
        self.sent = sent
        self.retry_after_s = retry_after_s

    def with_sent(self, sent: bool | str) -> CommandError:
        self.sent = sent
        return self


_NOT_DELIVERED = tuple(
    t for t in (aiohttp.ClientConnectorError, getattr(aiohttp, "ConnectionTimeoutError", None), ConnectionRefusedError) if t
)


def not_delivered(err: BaseException) -> bool:
    """True when a transport error certainly happened before the request left (connect/DNS)."""
    cause: BaseException | None = err
    for _ in range(5):
        if cause is None:
            break
        if isinstance(cause, _NOT_DELIVERED):
            return True
        cause = cause.__cause__
    return False


def explain(err: BaseException, *, sent: bool | str = False) -> CommandError:
    """Map pybyd / network failures to a stable code. `sent` is what we know about the command."""
    if isinstance(err, CommandError):
        return err
    if isinstance(err, asyncio.TimeoutError):
        if sent is True:
            return CommandError("timeout", "Car did not confirm in time (asleep or no signal); it may still do it", 504, outcome=UNCONFIRMED, sent=True)
        if sent == UNKNOWN:
            return CommandError("timeout", "BYD did not answer in time; the car may or may not have done it", 504, outcome=FAILED, sent=UNKNOWN)
        return CommandError("timeout", "Car did not answer in time (asleep or no signal)", 504, outcome=NOT_SENT, sent=False)
    if isinstance(err, BydControlPasswordError):
        return CommandError(f"pin_{err.code or 'error'}", f"Control PIN problem: {err}", 403, outcome=FAILED, sent=False)
    if isinstance(err, BydRateLimitError) or (isinstance(err, BydApiError) and err.code == "6024"):
        return CommandError("busy", "Car is still busy with a previous command", 409, outcome=NOT_SENT, sent=False)
    if isinstance(err, BydEndpointNotSupportedError):
        return CommandError("not_supported", "This car does not support that command", 501, outcome=FAILED, sent=False)
    if isinstance(err, BydRemoteControlError):
        if err.code in ("timeout", "no_serial"):
            return CommandError("timeout", str(err), 504, outcome=UNCONFIRMED, sent=True)
        return CommandError("rejected", str(err), 502, outcome=REJECTED, sent=sent if sent is not False else (err.code == "2"))
    if isinstance(err, BydAuthenticationError):
        return CommandError("login_failed", f"BYD login failed: {err}", 502, outcome=FAILED, sent=False)
    if isinstance(err, (BydServiceBusyError, BydDataUnavailableError)):
        return CommandError(f"byd_{err.code or 'error'}", str(err), 502, outcome=NOT_SENT if sent is False else FAILED, sent=sent)
    if isinstance(err, BydApiError):
        return CommandError(f"byd_{err.code or 'error'}", str(err), 502, outcome=FAILED, sent=sent)
    if isinstance(err, (BydTransportError, aiohttp.ClientError, OSError)):
        if sent is False or not_delivered(err):
            return CommandError("byd_unreachable", "BYD cloud unreachable, try again", 502, outcome=NOT_SENT, sent=False)
        return CommandError("byd_unreachable", "BYD cloud didn't answer; the car may or may not have done it", 502, outcome=FAILED, sent=sent)
    if isinstance(err, pydantic.ValidationError):  # BYD sent something pybyd could not read
        return CommandError("byd_bad_reply", f"Unexpected reply from BYD: {err.error_count()} field(s)", 502, outcome=FAILED, sent=sent)
    if isinstance(err, ValueError):
        return CommandError("bad_request", f"Bad parameter: {err}", 400, outcome=FAILED, sent=False)
    return CommandError("server_error", f"{type(err).__name__}: {err}", 500, outcome=FAILED, sent=sent)


def bad_request(message: str) -> CommandError:
    return CommandError("bad_request", message, 400, outcome=FAILED, sent=False)


def merge_sent(values: list[Any]) -> bool | str:
    """A job's `sent` from its steps: True if any step reached the car, else unknown, else False."""
    if any(v is True for v in values):
        return True
    if any(v == UNKNOWN for v in values):
        return UNKNOWN
    return False
