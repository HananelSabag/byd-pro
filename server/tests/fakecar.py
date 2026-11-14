"""Dummy car for testing the server without touching BYD or Render.

Importing this module:
  1. blocks every outbound connection except localhost (safety net, in fake_endpoints), and
  2. answers pybyd's calls from a fake BYD cloud that drives this car (the real pybyd client
     and the server's own command loop run unchanged).

Old names kept for the tests and the dummy server: CAR, CALLS, FAIL_NEXT, LATENCY.
"""

from __future__ import annotations

import os
import sys
import time

import fake_endpoints  # noqa: F401  (must come first: blocks the network)

# Test credentials: the fake client never sends them anywhere.
os.environ.setdefault("AUTH_SECRET", "test-secret")
for _key in ("BYD_USERNAME", "BYD_PASSWORD", "BYD_CONTROL_PIN"):
    os.environ.setdefault(_key, "test")
from fake_endpoints import VIN

TEMP_OFFSET_C = 14  # pybyd's default scale: A/C temperature = scale value + 14 °C


class FakeCar:
    def __init__(self) -> None:
        self.locked = True
        self.trunk = 0
        self.climate = False
        self.climate_started = 0.0
        self.soc = 78.0
        self.seats = {"driver": 1, "passenger": 1}  # BYD status scale: 1 off, 2 low, 3 high
        self.schedule = {"status": 0, "start": "23:00", "end": "07:00"}
        self.charging = 0
        self.doors = {"lf": 0, "rf": 0, "lr": 0, "rr": 0}
        self.windows = 1  # 1 closed, 2 open
        self.in_window = False  # inside the charging schedule window: "pause" can't stop the charge
        self.asleep = False  # an asleep car answers with an empty report
        self.recirc = True  # air source of the running A/C (cycle mode 2)
        self.set_c = 21.0  # A/C set temperature
        self.hvac_ghost = False  # the overall A/C status says "on" while the fan stands still
        self.hvac_fails = False  # the A/C endpoint errors out
        self.hvac_lag_s = 0.0  # after an A/C start, the A/C report still says off for this long
        self.charge_w = -7400  # battery power while charging (sign unconfirmed on the real car)
        self.report_time: int | None = None  # pin the realtime report's own timestamp (an old report)

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
            "gl": self.charge_w if self.charging == 1 else 0,
            "mainSeatHeatState": self.seats["driver"], "copilotSeatHeatState": self.seats["passenger"],
            "time": self.report_time if self.report_time is not None else int(time.time()),
        }  # fmt: skip

    def hvac_raw(self) -> dict:
        lagging = self.climate and time.monotonic() < self.climate_started + self.hvac_lag_s
        running = (self.climate and not lagging) or self.hvac_ghost
        return {
            "status": 1 if running else 2, "tempOutCar": 19, "tempInCar": 27,
            "windPosition": 3 if (self.climate and not lagging) else 0, "cycleChoice": 2 if self.recirc else 1,
            "mainSettingTempNew": self.set_c,
        }  # fmt: skip

    def schedule_raw(self) -> dict:
        s = self.schedule
        end = {"fullCharge": 1} if s["end"] == "full" else {"endTime": s["end"]}
        return {"smartChargeDto": {"startTime": s["start"], **end, "status": s["status"]}}

    def execute(self, action: str, params: dict | None) -> None:
        """What the car does when a command reaches it."""
        if action == "lock":
            self.locked = True
        elif action == "unlock":
            self.locked = False
        elif action == "climate_on":
            self.climate = True
            self.climate_started = time.monotonic()
            self.recirc = (params or {}).get("cycleMode") == 2
            self.set_c = float((params or {}).get("mainSettingTemp", 7)) + TEMP_OFFSET_C
        elif action == "climate_off":
            self.climate = False
        elif action == "trunk_open":
            self.trunk = 1
        elif action == "trunk_close":
            self.trunk = 0
        elif action == "windows_close":
            self.windows = 1
        elif action == "windows_vent":
            self.windows = 2
        elif action == "seat":
            status = {1: 3, 2: 2, 3: 1, 0: 1}  # command scale (1 high, 3 off) -> status scale
            self.seats["driver"] = status[int((params or {}).get("mainHeat", 0))]
            self.seats["passenger"] = status[int((params or {}).get("copilotHeat", 0))]
        elif action == "charge_now":
            self.charging = 1
        elif action.startswith("toggle_smart:"):
            enable = action.endswith("True")
            self.schedule.update(status=1 if enable else 0)
            if enable and not self.in_window:
                self.charging = 0  # waits for its window again
        elif action == "save_schedule":
            p = params or {}
            self.schedule.update(status=1, start=p.get("startChargeTime"), end=p.get("endChargeTime"))


CAR = FakeCar()
CLOUD = fake_endpoints.install(CAR)
CALLS: list[str] = CLOUD.calls  # every simulated cloud/car call, for assertions
FAIL_NEXT: dict[str, str] = CLOUD.fail_next  # action -> "timeout" | "rejected" (one-shot)
LATENCY = CLOUD.latency


def reset() -> None:
    """A fresh car and a healthy cloud (between test scenarios)."""
    CAR.__init__()
    CLOUD.reset()


__all__ = ["CALLS", "CAR", "CLOUD", "FAIL_NEXT", "LATENCY", "VIN", "FakeCar", "reset"]

SERVER_DIR = os.environ.get("BYD_SERVER_DIR") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVER_DIR:
    sys.path.insert(0, SERVER_DIR)
