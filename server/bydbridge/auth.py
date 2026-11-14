"""The shared secret, and a logs-only key that can be shared without it."""

from __future__ import annotations

import hashlib
import hmac

from aiohttp import web

from . import config

# A key that opens /logs and /info only, so a logs link can be shared without the password
# (which can also unlock the car). The app derives the same key: HMAC-SHA256(password, "logs").
LOGS_KEY = hmac.new(config.AUTH_SECRET.encode(), b"logs", hashlib.sha256).hexdigest()[:24]


def authorized(request: web.Request) -> bool:
    if not config.AUTH_SECRET:
        return False  # no secret configured: refuse everything rather than run open
    supplied = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    supplied = supplied or request.query.get("secret", "")
    return hmac.compare_digest(supplied.encode(), config.AUTH_SECRET.encode())


def can_read_logs(request: web.Request) -> bool:
    return authorized(request) or hmac.compare_digest(request.query.get("key", "").encode(), LOGS_KEY.encode())


def caller(request: web.Request) -> str:
    agent = request.headers.get("User-Agent", "").lower()
    for name, marker in (("app", "okhttp"), ("macrodroid", "macrodroid"), ("browser", "mozilla"), ("curl", "curl")):
        if marker in agent:
            return name
    return agent[:20] or "?"
