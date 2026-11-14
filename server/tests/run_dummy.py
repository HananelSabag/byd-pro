"""Local dummy server for the emulator: the real server + a fake BYD cloud, network blocked
except localhost. Listens on port 8765 (the emulator reaches it as http://10.0.2.2:8765).

Test hooks (GET, no secret needed; they only change the fake car and cloud):
  /__fail/<action>/<timeout|rejected>   the next <action> never confirms / is refused by the car
  /__door/<lf|rf|lr|rr>/<0|1>           close / open a door
  /__plug/<0|1>                         charging off / on
  /__ac/<on|off|ghost>                  A/C switched in the car; ghost = "on" with the fan at 0
  /__coldstart/<s>                      a Render restart: memory is wiped, the next BYD login takes <s> s
  /__latency/<action>/<s>               the car takes <s> s to confirm <action> (all = every command,
                                        realtime = status reads)
  /__drop/<action>                      the next <action> request runs, then its reply is dropped
  /__busy[/<s>]                         BYD answers "previous command still running" (6024) for <s> s (10)
  /__reset                              a healthy cloud and a fresh car
"""

from __future__ import annotations

import os
import time

os.environ.setdefault("FAKE_LATENCY", "1.6")
import fakecar  # noqa: E402  (blocks the network, installs the fake cloud)
from aiohttp import web  # noqa: E402

import main  # noqa: E402
from bydbridge import routes  # noqa: E402

DROP: set[str] = set()


def _text(message: str) -> web.Response:
    return web.Response(text=message + "\n")


@web.middleware
async def test_hooks(request: web.Request, handler):
    parts = request.path.strip("/").split("/")
    hook = parts[0]
    cloud, car = fakecar.CLOUD, fakecar.CAR
    if hook == "__fail" and len(parts) == 3:
        fakecar.FAIL_NEXT[parts[1]] = parts[2]
        return _text(f"next {parts[1]} -> {parts[2]}")
    if hook == "__door" and len(parts) == 3:  # /__door/lf/1 opens the front-left door
        car.doors[parts[1]] = int(parts[2])
        return _text(f"door {parts[1]} = {parts[2]}")
    if hook == "__plug" and len(parts) == 2:  # /__plug/1 = charging
        car.charging = int(parts[1])
        return _text(f"charging = {parts[1]}")
    if hook == "__ac" and len(parts) == 2:  # /__ac/off: switched off in the car; /__ac/ghost: "on", fan at 0
        car.climate = parts[1] == "on"
        car.hvac_ghost = parts[1] == "ghost"
        return _text(f"A/C = {parts[1]}")
    if hook == "__coldstart" and len(parts) == 2:
        seconds = float(parts[1])
        cloud.login_delay_once = seconds
        cloud.expire_sessions()
        await request.app["bridge"].restart()
        return _text(f"restarted (boot {request.app['bridge'].boot}); the next login takes {seconds:g}s")
    if hook == "__latency" and len(parts) == 3:
        action, seconds = parts[1], float(parts[2])
        if action == "all":
            cloud.latency = seconds
            cloud.action_latency.clear()
        elif action == "realtime":
            cloud.read_latency = seconds
        else:
            cloud.action_latency[action] = seconds
        return _text(f"latency {action} = {seconds:g}s")
    if hook == "__drop" and len(parts) == 2:
        DROP.add(parts[1])
        return _text(f"the next {parts[1]} reply will be dropped")
    if hook == "__busy" and len(parts) in (1, 2):
        seconds = float(parts[1]) if len(parts) == 2 else 10.0
        cloud.busy_until = time.monotonic() + seconds
        return _text(f"BYD says busy (6024) for {seconds:g}s")
    if hook == "__reset" and len(parts) == 1:
        fakecar.reset()
        cloud.latency = float(os.environ.get("FAKE_LATENCY", "1.6"))
        await request.app["bridge"].restart()
        return _text("fresh car, healthy cloud, server restarted")

    action = routes.route_action(request.path.strip("/").lower())
    if action is not None and action in DROP:
        DROP.discard(action)
        response = await handler(request)  # the command runs to the end...
        if request.transport is not None:
            request.transport.abort()  # ...and the phone never hears back
        return response
    return await handler(request)


def make_app() -> web.Application:
    app = main.create_app()
    app.middlewares.append(test_hooks)
    return app


if __name__ == "__main__":
    web.run_app(make_app(), host="0.0.0.0", port=8765, access_log=None)
