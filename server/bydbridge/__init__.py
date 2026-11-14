"""BYD Cloud Bridge: the server behind the BYD Pro app, its widgets and the watch.

    phone ──HTTPS──▶ this server ──▶ BYD cloud (pybyd) ──▶ car

Modules, in the order a command flows through them:

    config     every setting (credentials come from the environment, with defaults)
    obs        logging: one readable line per phase, kept in memory for /logs
    auth       the shared secret and the logs-only key
    routes     HTTP: legacy paths, request ids, async jobs, response bodies
    pipeline   jobs, idempotency and the per-car command queue
    commands   what each action does on the car (steps, effects, holds)
    remote     sends one car command once and polls its confirmation
    reads      live status reads (one at a time, shared by every caller)
    session    the one warm BYD login: single-flight, bounded, kept fresh
    state      the car snapshot, holds and the JSON the app reads
    errors     stable error codes and honest outcomes
    app        wires it all together (Bridge) and builds the aiohttp app
"""

__version__ = "3.0.0"
