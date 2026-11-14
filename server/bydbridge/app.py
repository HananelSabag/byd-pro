"""Wires the pieces together (Bridge) and builds the aiohttp app."""

from __future__ import annotations

import os
import secrets
import sys
import time
from typing import Any

from aiohttp import web
from pybyd import __version__ as PYBYD_VERSION

from . import __version__, config, obs, remote, routes
from .pipeline import Pipeline
from .usage import USAGE
from .reads import Reader
from .session import SessionManager
from .state import CarState

obs.setup()


class Bridge:
    """One car: its BYD session, snapshot, reads and command queue."""

    def __init__(self) -> None:
        self.boot = secrets.token_hex(4)  # changes on every start: clients can tell a restart
        self.started_at = time.time()
        self.state = CarState()
        self.session = SessionManager(self)
        self.reads = Reader(self)
        self.pipeline = Pipeline(self)
        remote.install()

    # ------------------------------------------------------------------ lifecycle

    def start(self) -> None:
        self.session.start_warmup()  # login only; the car is not woken at boot

    async def close(self) -> None:
        await self.pipeline.close()
        await self.session.close()
        obs.log.info("BYD session cleared")

    async def restart(self) -> None:
        """Forget everything and connect again, as after a Render restart (dummy server hook)."""
        await self.close()
        self.__init__()  # type: ignore[misc]
        obs.log.info("bridge restarted · boot %s", self.boot)
        self.start()

    def on_health_ping(self) -> None:
        self.session.on_health_ping(busy=self.pipeline.busy or self.reads.busy)

    # ------------------------------------------------------------------ replies

    def state_payload(self, debug: bool = False) -> dict[str, Any]:
        car = self.state.view()
        payload: dict[str, Any] = {
            "car": car,
            "name": self.session.car_name,
            "updated": int(self.state.read_at) or None,  # when the car itself last reported
            "caps": self.session.caps,
            "usage": USAGE.report(),  # what this server asked of the car today (see usage.py)
            # Flat legacy fields, kept for MacroDroid macros built on the old server.
            "soc": str(car.get("soc", "N/A")),
            "range": str(car.get("range_km", "N/A")),
        }
        if debug:
            payload["debug_dump"] = self.state.last_raw
        return payload

    def health(self) -> dict[str, Any]:
        return {
            "ok": True,
            "boot": self.boot,
            "uptime_s": int(time.time() - self.started_at),
            "warm": self.session.warm,
            "version": __version__,
            "rev": self.state.rev,
        }

    def info(self) -> dict[str, Any]:
        state, session = self.state, self.session
        return {
            "version": __version__,
            "boot": self.boot,
            "rev": state.rev,
            "uptime_min": round((time.time() - self.started_at) / 60, 1),
            "pybyd": PYBYD_VERSION,
            "python": sys.version.split()[0],
            "session": session.info(),
            "push_channel": {
                "running": session.info()["push_running"],
                "events_received": session.mqtt_events,
                "last_event_min_ago": round((time.time() - session.mqtt_last_event) / 60, 1) if session.mqtt_last_event else None,
                "wait_s": config.MQTT_WAIT_S,
            },
            "last_car_read_s_ago": round(state.read_age) if state.read_at else None,
            "climate_until_min": round((state.climate_until - time.time()) / 60, 1) if state.climate_until else None,
            "last_hvac": state.last_hvac or None,
            "holds": state.holds_info(),
            "queue": self.pipeline.info(),
            "recent_commands": list(self.pipeline.recent),
            "caps": session.caps,
        }

    # ------------------------------------------------------------------ old names (tests, tools)

    @property
    def client(self) -> Any:
        return self.session.client

    @property
    def car(self) -> dict[str, Any]:
        return self.state.car

    @property
    def caps(self) -> dict[str, Any]:
        return self.session.caps

    @property
    def vin(self) -> str:
        return self.session.vin

    @property
    def recent(self) -> Any:
        return self.pipeline.recent

    @property
    def last_hvac(self) -> str:
        return self.state.last_hvac

    @property
    def _held(self) -> dict[str, Any]:
        return self.state.holds

    @property
    def climate_until(self) -> float:
        return self.state.climate_until

    @climate_until.setter
    def climate_until(self, value: float) -> None:
        self.state.climate_until = value

    @property
    def read_at(self) -> float:
        return self.state.read_at

    @read_at.setter
    def read_at(self, value: float) -> None:
        self.state.read_at = value

    def view(self) -> dict[str, Any]:
        return self.state.view()


_current: Bridge | None = None


def current_bridge() -> Bridge | None:
    """The bridge of the most recently created app (main.link, for older tests and tools)."""
    return _current


def create_app() -> web.Application:
    global _current
    bridge = Bridge()
    _current = bridge
    app = web.Application()
    app["bridge"] = bridge

    async def on_startup(_app: web.Application) -> None:
        obs.log.info(
            "BYD bridge %s starting · pybyd %s · boot %s · commands sent once, checked every %.1fs",
            __version__, PYBYD_VERSION, bridge.boot, config.POLL_INTERVAL_S,
        )  # fmt: skip
        bridge.start()

    async def on_cleanup(_app: web.Application) -> None:
        await bridge.close()

    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    app.router.add_route("*", "/{tail:.*}", routes.handle)
    return app


def run() -> None:
    web.run_app(create_app(), host="0.0.0.0", port=int(os.environ.get("PORT", 10000)), access_log=None, print=None)
