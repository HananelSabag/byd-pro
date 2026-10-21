"""Dummy car for testing main.py without touching BYD or Render.

Importing this module:
  1. blocks every outbound connection except localhost (safety net), and
  2. swaps pybyd.BydClient for FakeBydClient before main.py imports it.
"""

from __future__ import annotations

import asyncio
import os
import socket
import sys
import time

# Test credentials: the fake client never sends them anywhere.
os.environ.setdefault("AUTH_SECRET", "test-secret")
for _key in ("BYD_USERNAME", "BYD_PASSWORD", "BYD_CONTROL_PIN"):
    os.environ.setdefault(_key, "test")

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

# ---------------------------------------------------------------- fake client
import pybyd  # noqa: E402
from pybyd import BydRemoteControlError  # noqa: E402
from pybyd.models import HvacStatus, VehicleCapabilities, VehicleRealtimeData  # noqa: E402
from pybyd.models.vehicle import Vehicle  # noqa: E402

CALLS: list[str] = []  # every simulated car command, for assertions
LATENCY = float(os.environ.get("FAKE_LATENCY", "0.4"))
FAIL_NEXT: dict[str, str] = {}  # action -> "timeout" | "rejected" (one-shot)


class FakeCar:
    def __init__(self) -> None:
        self.locked = True
        self.trunk = 0
        self.climate = False
        self.soc = 78.0
        self.seats = {"driver": 1, "passenger": 1}  # BYD status scale: 1 off, 2 low, 3 high
        self.schedule = {"status": 0, "start": "23:00", "end": "07:00"}
        self.charging = 0
        self.doors = {"lf": 0, "rf": 0, "lr": 0, "rr": 0}
        self.windows = 1  # 1 closed, 2 open
        self.in_window = False  # inside the charging schedule window: "pause" can't stop the charge
        self.asleep = False  # an asleep car answers with an empty report

    def raw(self) -> dict:
        if self.asleep:
            return {"onlineState": 2, "time": 0}
        lock = 2 if self.locked else 1
        return {
            "onlineState": 1, "elecPercent": self.soc, "enduranceMileage": 331,
            "totalMileageV2": 18452.6, "tempInCar": 27, "powerGear": 1,
            "leftFrontDoor": self.doors["lf"], "rightFrontDoor": self.doors["rf"],
            "leftRearDoor": self.doors["lr"], "rightRearDoor": self.doors["rr"],
            "trunkLid": self.trunk,
            "leftFrontDoorLock": lock, "rightFrontDoorLock": lock, "leftRearDoorLock": lock, "rightRearDoorLock": lock,
            "leftFrontWindow": self.windows, "rightFrontWindow": self.windows, "leftRearWindow": self.windows, "rightRearWindow": self.windows,
            "leftFrontTirepressure": 2.5, "rightFrontTirepressure": 2.5,
            "leftRearTirepressure": 2.4, "rightRearTirepressure": 2.6, "tirePressUnit": 1,
            "chargeState": self.charging, "nearestEnergyConsumption": "14.8",
            "mainSeatHeatState": self.seats["driver"], "copilotSeatHeatState": self.seats["passenger"],
            "time": int(time.time()),
        }  # fmt: skip


CAR = FakeCar()


class FakeBydClient:
    def __init__(self, config, *, on_vehicle_info=None, **_):
        self.config = config
        self.on_vehicle_info = on_vehicle_info

    async def async_start(self):
        CALLS.append("start")

    async def async_close(self):
        CALLS.append("close")

    async def get_vehicles(self):
        await asyncio.sleep(0.05)
        CALLS.append("get_vehicles")
        return [Vehicle.model_validate({"vin": "LGXCE4CB0P0000000", "modelName": "ATTO 3", "autoAlias": "ATTO 3"})]

    async def verify_command_access(self, vin):
        CALLS.append("verify_pin")

    async def get_vehicle_capabilities(self, vin, *, force_refresh=False):
        CALLS.append("caps")
        return VehicleCapabilities.model_validate(
            {"vin": vin, "lock": True, "unlock": True, "climate": True, "findCar": True, "flashLights": True,
             "openTrunk": True, "closeTrunk": True, "closeWindows": True, "openWindows": True,
             "driverSeatHeat": True, "passengerSeatHeat": True, "startCharge": True}
        )  # fmt: skip

    async def get_vehicle_realtime(self, vin, **_):
        await asyncio.sleep(LATENCY)
        CALLS.append("realtime")
        return VehicleRealtimeData.model_validate(CAR.raw())

    async def get_hvac_status(self, vin):
        await asyncio.sleep(0.05)
        return HvacStatus.model_validate({"status": 1 if CAR.climate else 2, "tempOutCar": 19, "tempInCar": 27})

    async def _cmd(self, name, effect=None):
        await asyncio.sleep(LATENCY)
        CALLS.append(name)
        failure = FAIL_NEXT.pop(name, None)
        if failure == "timeout":
            raise asyncio.TimeoutError()
        if failure == "rejected":
            raise BydRemoteControlError("Remote control failed: controlState=2", code="2")
        if effect:
            effect()
        if self.on_vehicle_info:  # the real car pushes fresh state over MQTT after a command
            self.on_vehicle_info("LGXCE4CB0P0000000", VehicleRealtimeData.model_validate(CAR.raw()))

    async def lock(self, vin):
        await self._cmd("lock", lambda: setattr(CAR, "locked", True))

    async def unlock(self, vin):
        await self._cmd("unlock", lambda: setattr(CAR, "locked", False))

    async def start_climate(self, vin, *, params):
        CALLS.append(f"climate_params:{params.to_control_params_map()}")
        await self._cmd("climate_on", lambda: setattr(CAR, "climate", True))

    async def stop_climate(self, vin):
        await self._cmd("climate_off", lambda: setattr(CAR, "climate", False))

    async def find_car(self, vin):
        await self._cmd("locate")

    async def flash_lights(self, vin):
        await self._cmd("flash")

    async def open_trunk(self, vin):
        await self._cmd("trunk_open", lambda: setattr(CAR, "trunk", 1))

    async def close_trunk(self, vin):
        await self._cmd("trunk_close", lambda: setattr(CAR, "trunk", 0))

    async def close_windows(self, vin):
        await self._cmd("windows_close", lambda: setattr(CAR, "windows", 1))

    async def open_windows(self, vin):
        await self._cmd("windows_vent", lambda: setattr(CAR, "windows", 2))

    async def set_seat_climate(self, vin, *, params):
        CALLS.append(f"seat_params:{params.to_control_params_map()}")
        status = {1: 3, 2: 2, 3: 1, 0: 1}  # command scale (1 high, 3 off) -> status scale

        def apply():
            CAR.seats["driver"] = status[params.main_heat]
            CAR.seats["passenger"] = status[params.copilot_heat]

        await self._cmd("seat", apply)

    async def start_charging(self, vin, **_):
        await self._cmd("charge_now", lambda: setattr(CAR, "charging", 1))

    async def toggle_smart_charging(self, vin, *, enable):
        def apply():
            CAR.schedule.update(status=1 if enable else 0)
            if enable and not CAR.in_window:
                CAR.charging = 0  # waits for its window again

        await self._cmd(f"toggle_smart:{enable}", apply)

    async def save_charging_schedule(self, vin, *, start_charge_time, end_charge_time, charge_way, enabled=True, **_):
        CALLS.append(f"schedule:{start_charge_time}-{end_charge_time}:{charge_way}:{enabled}")
        await self._cmd("save_schedule", lambda: CAR.schedule.update(status=1, start=start_charge_time, end=end_charge_time))

    async def get_charging_schedule(self, vin):
        from pybyd.models.smart_charging import SmartChargingSchedule
        s = CAR.schedule
        end = {"fullCharge": 1} if s["end"] == "full" else {"endTime": s["end"]}
        return SmartChargingSchedule.model_validate(
            {"vin": vin, "smartChargeDto": {"startTime": s["start"], **end, "status": s["status"]}}
        )


pybyd.BydClient = FakeBydClient

SERVER_DIR = os.environ.get("BYD_SERVER_DIR") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVER_DIR:
    sys.path.insert(0, SERVER_DIR)
