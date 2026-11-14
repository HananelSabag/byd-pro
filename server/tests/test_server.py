"""End-to-end checks of main.py against the dummy car (no real network)."""

import asyncio
import time

import fakecar  # must come first: blocks network + installs the fake client
from aiohttp.test_utils import TestClient, TestServer

import main

H = {"Authorization": f"Bearer {main.AUTH_SECRET}"}
results = []


def check(name, cond, extra=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra else ""))


async def run():
    main.config.PAUSE_CHECK_S = 0.05
    main.config.COMMAND_TIMEOUT_S = 4  # the "car never confirms" case waits this long, not 30 s
    main.config.VERIFY_DELAYS_S = (0.2,)  # the follow-up look after an unconfirmed command settles at once
    async with TestClient(TestServer(main.create_app())) as c:
        await asyncio.sleep(0.3)  # boot warm-up
        check("warm-up logs in once at boot, without waking the car",
              fakecar.CALLS.count("get_vehicles") == 1 and "realtime" not in fakecar.CALLS, fakecar.CALLS)

        r = await c.get("/health"); check("health 200 without auth", r.status == 200 and await r.text() == "OK")
        r = await c.head("/"); check("HEAD / for uptime pingers", r.status == 200)
        r = await c.get("/lock"); check("no secret -> 401", r.status == 401)
        r = await c.get("/lock?secret=wrong"); check("wrong secret -> 401", r.status == 401)
        check("rejected requests sent nothing to the car", "lock" not in fakecar.CALLS)

        r = await c.get("/status", headers=H); j = await r.json()
        check("status ok", r.status == 200 and j["ok"], j.get("car"))
        car = j["car"]
        check("status fields", car["soc"] == 78 and car["range_km"] == 331 and car["locked"] is True
              and car["trunk_open"] is False and car["tires"]["unit"] == "bar" and car["climate_on"] is False
              and car["outside_c"] == 19 and car["odometer_km"] == 18453, car)
        check("legacy soc/range strings kept", j["soc"] == "78" and j["range"] == "331")
        check("caps exported", j["caps"]["lock"] is True and j["caps"]["open_windows"] is True, j["caps"])
        check("per-window state reported", car["windows"] == {"lf": False, "rf": False, "lr": False, "rr": False}, car.get("windows"))

        n = fakecar.CALLS.count("realtime")
        t = time.monotonic(); r = await c.get(f"/status?secret={main.AUTH_SECRET}"); dt = time.monotonic() - t
        check("second status served from cache (car not woken again)",
              fakecar.CALLS.count("realtime") == n and dt < 0.2, f"{dt*1000:.0f} ms")
        r = await c.get("/status?fresh=1", headers=H)
        check("fresh=1 forces a live read", fakecar.CALLS.count("realtime") == n + 1)

        # double tap: two identical unlocks at the same time -> one car command
        before = fakecar.CALLS.count("unlock")
        r1, r2 = await asyncio.gather(c.get("/unlock", headers=H), c.get("/unlock", headers=H))
        j1, j2 = await r1.json(), await r2.json()
        check("double tap merged into one command", fakecar.CALLS.count("unlock") == before + 1)
        check("both taps get the result + new state", j1["ok"] and j2["ok"] and j1["car"]["locked"] is False)

        # different commands are serialised, not overlapped
        t = time.monotonic()
        await asyncio.gather(c.get("/lock", headers=H), c.get("/locate", headers=H))
        dt = time.monotonic() - t
        check("different commands queued one after another", dt >= 2 * fakecar.LATENCY * 0.95, f"{dt:.2f}s")

        r = await c.get("/ac?temp=40&mins=12&defrost=true", headers=H); j = await r.json()
        params = next(x for x in fakecar.CALLS if x.startswith("climate_params"))
        check("climate clamps temp to 31 and snaps 12 -> 10 min",
              j["ok"] and "'mainSettingTemp': 17" in params and "'timeSpan': 1" in params, params)
        check("climate_on reflected", j["car"]["climate_on"] is True)
        fakecar.CAR.climate = False  # the car still reports "off" while the A/C spins up
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("confirmed command beats a stale car report (held)", j["car"]["climate_on"] is True, j["car"].get("climate_on"))
        fakecar.CAR.climate = True

        r = await c.get("/windows-vent", headers=H); j = await r.json()
        check("windows vent opens every window", j["ok"] and j["car"]["windows_open"] is True and all(j["car"]["windows"].values()), j.get("car", {}).get("windows"))
        r = await c.get("/windows-close", headers=H); j = await r.json()
        check("windows close shuts every window", j["ok"] and j["car"]["windows_open"] is False and not any(j["car"]["windows"].values()))
        r = await c.get("/trunk-toggle", headers=H); j = await r.json()
        check("trunk toggle opens a closed trunk", j["action"] == "trunk_open" and j["car"]["trunk_open"] is True)
        r = await c.get("/trunk-toggle", headers=H); j = await r.json()
        check("trunk toggle closes an open trunk", j["action"] == "trunk_close" and j["car"]["trunk_open"] is False)

        fakecar.FAIL_NEXT["lock"] = "timeout"
        before = fakecar.CALLS.count("lock")
        r = await c.get("/lock", headers=H); j = await r.json()
        check("timeout -> 504 + code, state still returned", r.status == 504 and j["error"] == "timeout" and "car" in j, j.get("error"))
        check("failed command is NOT re-sent by the server", fakecar.CALLS.count("lock") == before + 1)
        check("session kept after a car timeout (no re-login)", fakecar.CALLS.count("get_vehicles") == 1)

        fakecar.FAIL_NEXT["unlock"] = "rejected"
        r = await c.get("/unlock", headers=H); j = await r.json()
        check("car rejected -> 502 rejected", r.status == 502 and j["error"] == "rejected")

        for path in ("climate-off", "stop-ac", "find", "horn", "tailgate", "close-trunk", "api/lock", "LOCK"):
            r = await c.get(f"/{path}", headers=H)
            check(f"legacy route /{path}", r.status == 200)
        r = await c.get("/nope", headers=H); check("unknown route -> 404", r.status == 404)
        r = await c.get("/status?debug=1", headers=H); j = await r.json()
        check("debug=1 includes raw dump", "debug_dump" in j)

        # --- new features: seats, charging, per-door state, diagnostics
        r = await c.get("/status?fresh=1", headers=H); j = await r.json(); car = j["car"]
        check("status has per-door map + seat levels", car.get("doors", {}).get("lf") is False and car.get("seat_heat") == {"driver": 0, "passenger": 0}, car.get("seat_heat"))
        check("status has charge schedule", car.get("schedule", {}).get("start") == "23:00", car.get("schedule"))
        r = await c.get("/seat?seat=driver&level=2", headers=H); j = await r.json()
        seat_cmd = [x for x in fakecar.CALLS if x.startswith("seat_params")][-1]
        check("seat heat driver high: command 1, passenger kept off (3), chair 1",
              j["ok"] and "'mainHeat': 1" in seat_cmd and "'copilotHeat': 3" in seat_cmd and "'chairType': '1'" in seat_cmd, seat_cmd)
        check("seat level reflected", j["car"]["seat_heat"]["driver"] == 2)
        r = await c.get("/ac?temp=22&mins=10&driver_seat=1&passenger_seat=1", headers=H); j = await r.json()
        check("old-app climate+seats still works", j["ok"] and j["car"]["seat_heat"] == {"driver": 1, "passenger": 1}, j["car"].get("seat_heat"))
        r = await c.get("/charge-schedule?mode=window&start=23:30&end=6:00&enabled=true", headers=H); j = await r.json()
        check("charge window saved", j["ok"] and any(x == "schedule:23:30-06:00:e:True" for x in fakecar.CALLS), [x for x in fakecar.CALLS if x.startswith("schedule")])
        r = await c.get("/charge-schedule?mode=start_only&start=01:00&enabled=true", headers=H); j = await r.json()
        check("start-only charges until full", "schedule:01:00-full:e:True" in fakecar.CALLS and j["car"]["schedule"]["end"] == "full")
        r = await c.get("/charge-schedule?mode=stop_only&end=07:15&enabled=true", headers=H)
        stop = [x for x in fakecar.CALLS if x.startswith("schedule")][-1]
        check("stop-only starts now (car time) and stops at end", stop.endswith("-07:15:e:True") and not stop.startswith("schedule:01:00"), stop)
        r = await c.get("/charge-schedule?enabled=false", headers=H); j = await r.json()
        check("schedule off", j["ok"] and j["car"]["schedule"]["enabled"] is False and "toggle_smart:False" in fakecar.CALLS)
        r = await c.get("/charge-now", headers=H); j = await r.json()
        check("charge now", j["ok"] and j["car"]["charging"] is True)
        r = await c.get("/charge-pause", headers=H); j = await r.json()
        check("charge pause re-enables schedule and checks the car stopped",
              j["ok"] and "toggle_smart:True" in fakecar.CALLS and j["car"]["charging"] is False, j.get("car", {}).get("charging"))
        await c.get("/charge-now", headers=H)
        fakecar.CAR.in_window = True
        r = await c.get("/charge-pause", headers=H); j = await r.json()
        check("pause inside the charging window says the car keeps charging",
              r.status == 409 and j["error"] == "still_charging" and j["car"]["schedule"]["enabled"] is True, j.get("error"))
        fakecar.CAR.in_window = False
        r = await c.get("/charge-schedule", headers=H)
        check("bare charge-schedule is refused (no silent daily schedule)", r.status == 400)

        # A/C we started is reported off once its run time is over, without waking the car.
        r = await c.get("/ac?temp=22&mins=10", headers=H); j = await r.json()
        air = [x for x in fakecar.CALLS if x.startswith("climate_params")][-1]
        check("recirculation is the default air source", "'cycleMode': 2" in air, air[:120])
        await c.get("/ac?temp=15&mins=10&air=fresh", headers=H)
        air = [x for x in fakecar.CALLS if x.startswith("climate_params")][-1]
        check("air=fresh asks for outside air", "'cycleMode': 1" in air, air[:160])
        r = await c.get("/ac?temp=22&mins=10", headers=H); j = await r.json()
        check("A/C run end sent to the app", j["ok"] and j["car"].get("climate_until", 0) > time.time() + 500, j["car"].get("climate_until"))
        main.link.climate_until = time.time() - 1
        main.link.read_at = time.time() - main.STATUS_CACHE_S / 2
        r = await c.get("/status", headers=H); j = await r.json()
        check("A/C reported off after its run time", j["car"]["climate_on"] is False, j["car"].get("climate_on"))

        # "updated" is when the car itself last reported, not when a command changed the cache.
        stamp = main.link.read_at
        fakecar.CLOUD.push = False  # no MQTT push this time
        await c.get("/lock", headers=H)
        fakecar.CLOUD.push = True
        r = await c.get("/status", headers=H); j = await r.json()
        check("command doesn't make old data look fresh", j["updated"] == int(stamp), (j["updated"], int(stamp)))

        # An asleep car's empty report must not wipe the known state.
        fakecar.CAR.asleep = True
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("asleep car: last known state kept", j["ok"] and j["car"]["soc"] == 78 and j["car"]["range_km"] == 331, j["car"].get("soc"))
        fakecar.CAR.asleep = False

        # Commands go to the car once (the server's own loop; pybyd's 3x resend is never used).
        before = fakecar.CLOUD.offers["lock"]
        await c.get("/lock", headers=H)
        check("commands are sent once and checked every 0.8 s",
              fakecar.CLOUD.offers["lock"] == before + 1 and main.config.POLL_INTERVAL_S == 0.8,
              (fakecar.CLOUD.offers["lock"] - before, main.config.POLL_INTERVAL_S))
        before = len([x for x in fakecar.CALLS if x == "seat"])
        r = await c.get("/seat?driver=2&passenger=1", headers=H); j = await r.json()
        sent = len([x for x in fakecar.CALLS if x == "seat"]) - before
        check("both seats in one request; unchanged seat not resent", j["ok"] and j["car"]["seat_heat"] == {"driver": 2, "passenger": 1} and sent == 1, sent)
        r = await c.get("/logs", headers=H); text = await r.text()
        check("/logs returns readable request lines", r.status == 200 and "→ lock" in text and "← lock ✓" in text, text.splitlines()[-1][:80])
        r = await c.get("/logs"); check("/logs needs the secret", r.status == 401)
        r = await c.get(f"/logs?key={main.LOGS_KEY}"); check("logs-only key opens /logs", r.status == 200)
        r = await c.get(f"/unlock?key={main.LOGS_KEY}"); check("logs-only key can't send commands", r.status == 401)
        r = await c.get("/charge-schedule?enabled=true", headers=H); check("schedule without a mode is refused", r.status == 400)
        r = await c.get("/info", headers=H); j = await r.json()
        check("/info diagnostics", j["session"]["connected"] and "push_channel" in j and j["recent_commands"], j["push_channel"])

        # --- 2.7: the car's own A/C report wins; air source; charging power
        def end_climate_hold():
            for key in main.CLIMATE_KEYS:
                main.link._held.pop(key, None)

        await c.get("/ac-off", headers=H)
        await c.get("/seat?seat=driver&level=0", headers=H)
        r = await c.get("/seat?seat=driver&level=1", headers=H); j = await r.json()
        check("seat heat doesn't mark the A/C as on", j["ok"] and j["car"]["climate_on"] is False, j["car"].get("climate_on"))
        fakecar.CAR.hvac_ghost = True
        end_climate_hold()
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("A/C 'on' with the fan at 0 counts as off", j["car"]["climate_on"] is False, j["car"].get("climate_on"))
        check("raw A/C fields kept for diagnosis", "wind_position=0" in main.link.last_hvac, main.link.last_hvac)
        fakecar.CAR.hvac_ghost = False
        r = await c.get("/ac?temp=22&mins=10", headers=H); j = await r.json()
        check("A/C start reports on + the air source", j["car"]["climate_on"] is True and j["car"]["recirc"] is True, j["car"].get("recirc"))
        fakecar.CAR.climate = False  # switched off in the car or from the BYD app
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("right after a start the A/C is held on briefly", j["car"]["climate_on"] is True)
        end_climate_hold()
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("A/C switched off elsewhere shows off after the short hold", j["car"]["climate_on"] is False, j["car"].get("climate_on"))
        check("A/C hold is a spin-up window, shorter than the lock hold cap", 0 < main.CLIMATE_HOLD_S < main.COMMAND_HOLD_S)

        r = await c.get("/recirc?on=0", headers=H); j = await r.json()
        check("air source can't change while the A/C is off", r.status == 409 and j["error"] == "climate_off", j.get("error"))
        await c.get("/ac?temp=20&mins=20", headers=H)
        r = await c.get("/recirc?on=0", headers=H); j = await r.json()
        air = [x for x in fakecar.CALLS if x.startswith("climate_params")][-1]
        check("outside air while running: restarts with the same temp and the time left",
              j["ok"] and j["car"]["recirc"] is False and "'cycleMode': 1" in air and "'timeSpan': 3" in air
              and "'mainSettingTemp': 6" in air, air)
        end_climate_hold()
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("air source read back from the car", j["car"]["recirc"] is False and j["car"]["climate_on"] is True, j["car"].get("recirc"))
        check("set temperature read back from the car", j["car"].get("climate_set_c") == 20, j["car"].get("climate_set_c"))

        fakecar.CAR.charging = 1
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("charging power in kW", j["car"].get("charge_kw") == 7.4, j["car"].get("charge_kw"))
        fakecar.CAR.charging = 0
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("charging power cleared when the charge ends", j["car"].get("charge_kw") == 0, j["car"].get("charge_kw"))
        check("kW from W or already kW", main._charge_kw(-10400) == 10.4 and main._charge_kw(11) == 11 and main._charge_kw(None) == 0)

        fakecar.CAR.hvac_fails = True
        r = await c.get("/status?fresh=1", headers=H); j = await r.json()
        check("a failing A/C read doesn't break status", j["ok"] and j["car"]["soc"] == 78)
        fakecar.CAR.hvac_fails = False

    print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")
    return all(ok for _, ok in results)


if __name__ == "__main__":
    raise SystemExit(0 if asyncio.run(run()) else 1)
