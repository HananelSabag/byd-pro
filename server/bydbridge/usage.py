"""
What this server asks of the car each day: every car read (each one wakes the car and has it send a
fresh report over its SIM), every command sent to it, and every byte exchanged with the BYD cloud
for them (bodies plus headers). Counted per day in the car's time zone and returned with every
state reply as `usage`, so the app can show it.

The bytes are the server ↔ BYD-cloud traffic, which is measured; what the car's own SIM moves for
the same read or command is of the same order and is not visible from here. The count lives in
memory: a restart (a deploy, or the free plan going to sleep) starts the day again.
"""
from __future__ import annotations

import datetime as dt
from typing import Any

import aiohttp

from . import config


class Usage:
    def __init__(self) -> None:
        self.day: str | None = None
        self.yesterday: dict[str, Any] | None = None
        self.car_reads = 0
        self.commands = 0
        self.bytes = 0

    def _roll(self) -> None:
        today = dt.datetime.now(config.CAR_TIMEZONE).date().isoformat()
        if today != self.day:
            if self.day is not None:
                self.yesterday = self._counts()
            self.day, self.car_reads, self.commands, self.bytes = today, 0, 0, 0

    def car_read(self) -> None:
        self._roll()
        self.car_reads += 1

    def command(self) -> None:
        self._roll()
        self.commands += 1

    def add_bytes(self, n: int) -> None:
        self._roll()
        self.bytes += n

    def _counts(self) -> dict[str, Any]:
        return {"day": self.day, "car_reads": self.car_reads, "commands": self.commands, "kb": round(self.bytes / 1024, 1)}

    def report(self) -> dict[str, Any]:
        self._roll()
        return {**self._counts(), **({"yesterday": self.yesterday} if self.yesterday else {})}


USAGE = Usage()


def trace_config() -> aiohttp.TraceConfig:
    """Counts every byte of the BYD client's HTTP traffic (request and response bodies and headers)."""
    trace = aiohttp.TraceConfig()

    async def sent(_session: Any, _ctx: Any, params: aiohttp.TraceRequestChunkSentParams) -> None:
        USAGE.add_bytes(len(params.chunk))

    async def received(_session: Any, _ctx: Any, params: aiohttp.TraceResponseChunkReceivedParams) -> None:
        USAGE.add_bytes(len(params.chunk))

    async def ended(_session: Any, _ctx: Any, params: aiohttp.TraceRequestEndParams) -> None:
        headers = sum(len(k) + len(v) + 4 for k, v in params.headers.items())
        headers += sum(len(k) + len(v) + 4 for k, v in params.response.raw_headers)
        USAGE.add_bytes(headers)

    trace.on_request_chunk_sent.append(sent)
    trace.on_response_chunk_received.append(received)
    trace.on_request_end.append(ended)
    return trace
