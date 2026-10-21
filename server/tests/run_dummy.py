"""Local dummy server for the emulator: real main.py + fake car, network blocked except localhost.

Test hook: GET /__fail/<action>/<timeout|rejected> makes the next <action> fail that way.
"""
import os
os.environ.setdefault("FAKE_LATENCY", "1.6")
import fakecar  # noqa: F401  (blocks network, installs fake client)
import main
from aiohttp import web


@web.middleware
async def test_hooks(request, handler):
    parts = request.path.strip("/").split("/")
    if parts[0] == "__fail" and len(parts) == 3:
        fakecar.FAIL_NEXT[parts[1]] = parts[2]
        return web.Response(text=f"next {parts[1]} -> {parts[2]}")
    if parts[0] == "__door" and len(parts) == 3:  # /__door/lf/1 opens the front-left door
        fakecar.CAR.doors[parts[1]] = int(parts[2])
        return web.Response(text=f"door {parts[1]} = {parts[2]}")
    if parts[0] == "__plug" and len(parts) == 2:  # /__plug/1 = charging
        fakecar.CAR.charging = int(parts[1])
        return web.Response(text=f"charging = {parts[1]}")
    return await handler(request)


app = main.create_app()
app.middlewares.append(test_hooks)
web.run_app(app, host="0.0.0.0", port=8765, access_log=None)
