"""
BYD Cloud Bridge: the Render server behind the BYD app and its widgets.

    phone ──HTTPS──▶ this server ──▶ BYD cloud (pybyd) ──▶ car

Render runs this file (`python main.py`, listens on $PORT, default 10000). The server itself
lives in the `bydbridge` folder next to it; upload both. API.md describes every route.

Diagnostics (add ?secret=… or ?key=<logs key> in a browser):
  /logs   the last few thousand log lines, ready to copy and share
  /info   session, queue, holds, timings and capabilities as JSON
  /health plain "OK" (cron-job.org pings it to keep the free instance awake); /health?v=2 is JSON
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # find bydbridge/ from any working dir

from aiohttp import web  # noqa: E402

from bydbridge import app as _app  # noqa: E402
from bydbridge import auth, config, state  # noqa: E402,F401
from bydbridge.app import create_app  # noqa: E402,F401


def __getattr__(name: str):  # older tests and tools read main.link, main.AUTH_SECRET, main.STATUS_CACHE_S, …
    if name == "link":
        return _app.current_bridge()
    for module in (config, state, auth):
        if hasattr(module, name):
            return getattr(module, name)
    raise AttributeError(name)


if __name__ == "__main__":
    web.run_app(create_app(), host="0.0.0.0", port=int(os.environ.get("PORT", 10000)), access_log=None, print=None)


# =============================================================================
# ORA 03 (GWM): parked. Kept for reference, fully commented out so it cannot
# interfere with the BYD code above.
#
# To bring it back:
#   1. put ora_client.py next to this file (it is not part of this update)
#   2. uncomment this block
#   3. in bydbridge/routes.py handle(), right after the authorized() check, add:
#          if path.startswith("ora"):
#              return await handle_ora(path, request)
# =============================================================================
#
# from ora_client import OraClient
#
# ORA_VIN = "LGWEEUA58NK646074"
# ORA_CLIENT = OraClient(email=BYD_USERNAME, password=BYD_PASSWORD, pin=BYD_CONTROL_PIN, vin=ORA_VIN)
#
#
# async def handle_ora(path: str, request: web.Request) -> web.Response:
#     route = path[3:].strip("/")
#     try:
#         if route == "send-code":
#             return web.json_response({"status": "Code sent to email", "gwm": await ORA_CLIENT.send_email_code()})
#         if route == "verify":
#             code = request.query.get("code")
#             if not code:
#                 return web.json_response({"error": "Missing code parameter"}, status=400)
#             return web.json_response({"status": "Device verified", "gwm": await ORA_CLIENT.verify_email_code(code)})
#         if route in ("", "status"):
#             return web.json_response(await ORA_CLIENT.get_all_data())
#         if route == "lock":
#             return web.json_response(await ORA_CLIENT.lock())
#         if route == "unlock":
#             return web.json_response(await ORA_CLIENT.unlock())
#         if route == "ac":
#             return web.json_response(await ORA_CLIENT.start_climate(float(request.query.get("temp", 22.0))))
#         if route in ("ac-off", "climate-off"):
#             return web.json_response(await ORA_CLIENT.stop_climate())
#         return web.Response(status=404, text=f"Unknown ORA route: /{path}")
#     except Exception as err:
#         log.exception("ORA request failed")
#         return web.Response(status=500, text=f"ORA error: {err}")
