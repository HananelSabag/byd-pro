"""Settings: everything you may want to change lives here.

Credentials and the app secret come from the environment (Render → Environment). The defaults
below are the owner's choice and are kept as they were in the single-file server.

Every module reads these as `config.NAME` at the moment it needs them, so a test (or a quick
experiment) can change one value without restarting anything.
"""

from __future__ import annotations

import os
from zoneinfo import ZoneInfo

# =============================================================================
# Account (environment first)
# =============================================================================

BYD_USERNAME = os.environ.get("BYD_USERNAME", "")  # BYD app account (email)
BYD_PASSWORD = os.environ.get("BYD_PASSWORD", "")
BYD_CONTROL_PIN = os.environ.get("BYD_CONTROL_PIN", "")
AUTH_SECRET = os.environ.get("AUTH_SECRET", "")
CAR_TIMEZONE = ZoneInfo(os.environ.get("CAR_TIMEZONE", "Asia/Jerusalem"))  # "charge now until …" uses car time

# BYD's push channel (MQTT). Pushes that do arrive are used; set BYD_MQTT=0 to switch it off.
MQTT_ENABLED = os.environ.get("BYD_MQTT", "1").strip().lower() not in ("0", "false", "no", "off")

# =============================================================================
# Status reads
# =============================================================================

STATUS_CACHE_S = 20  # /status is answered from memory while the last real car report is newer than this
TRUNK_STATE_MAX_AGE_S = 15  # trunk-toggle re-reads the car if its last real report is older
TRUNK_READ_TIMEOUT_S = 8  # ...but never waits longer than this for that read
STATUS_TIMEOUT_S = 15  # hard cap for one live read: the car wake, then the A/C, then done
HVAC_TIMEOUT_S = 4  # the A/C read runs after the wake and gets this long (inside STATUS_TIMEOUT_S)
STATUS_POLL_ATTEMPTS = 12  # the wake gives up after ~10 s (car asleep); the last state is served

# =============================================================================
# Commands
# =============================================================================

COMMAND_TIMEOUT_S = 30  # one car command: sending it plus waiting for the car's confirmation
JOB_BUDGET_S = 50  # one request, all its steps together (A/C + both seats is three car commands)
MIN_STEP_S = 6  # a step that would start with less time than this left is not sent at all
QUEUE_WAIT_S = 20  # a command waiting longer than this for its turn is dropped, never sent late
QUEUE_MAX = 4  # commands waiting behind the running one; more answer 429 queue_full
PAUSE_CHECK_S = 8  # after "pause charging", look at the car again this much later
VERIFY_DELAYS_S = (6, 20)  # unconfirmed command: look at the car again after these delays

# BYD answers through a push channel (MQTT) or, failing that, polling. On this account (the
# official BYD app is signed in too) the push rarely arrives, so don't wait for it: poll.
MQTT_WAIT_S = 0
POLL_INTERVAL_S = 0.8  # commands are sent once, then their confirmation is checked this often
POLL_MAX_INTERVAL_S = 3.0  # while BYD says "busy" (1008) the checks slow down to this
RATE_LIMIT_WAIT_S = 3.0  # BYD 6024 "a previous command is still running": wait, then send (it wasn't taken)
BUSY_RETRY_WAIT_S = 1.5  # BYD 1008 "busy" before sending: wait this (times the attempt), then offer again
CONNECT_RETRY_WAIT_S = 1.0  # the connection to BYD never opened: wait this, then offer again
POLL_CALL_TIMEOUT_S = 5  # one confirmation check; a slower one is dropped and the next one sent
TRIGGER_RETRIES = 3  # how often a command may be offered again when BYD clearly did not take it
POLL_SESSION_REFRESHES = 2  # logins allowed while polling one command (the same serial is kept)

# =============================================================================
# Holds: a confirmed command's effect beats slower car reports
# =============================================================================

COMMAND_HOLD_S = 60  # the longest a confirmed effect can be held against the car's reports
HOLD_MARGIN_S = 2  # a car report counts as newer than a confirmation only if made this much later
HOLD_GRACE_S = 20  # reports without a car timestamp (A/C, schedule) can't overrule an effect sooner
CLIMATE_HOLD_S = 20  # the same for the A/C: it spins up slowly, its report must not flip it back

# =============================================================================
# BYD session
# =============================================================================

HTTP_TIMEOUT_S = 12  # one HTTP call to BYD, end to end
HTTP_CONNECT_TIMEOUT_S = 6  # ...of which connecting
LOGIN_TIMEOUT_S = 25  # one login (it also opens the push channel)
CONNECT_TIMEOUT_S = 45  # the whole warm-up: login, vehicle, PIN check, capabilities
PIN_TIMEOUT_S = 10
CAPS_TIMEOUT_S = 10
LOGIN_REUSE_S = 10  # callers asking for a login within this of a fresh one share it
LOGIN_RETRY_S = 10  # after a failed login, wait this long before trying again (doubles, capped)
LOGIN_RETRY_MAX_S = 120
SESSION_REFRESH_S = 11 * 3600  # pybyd's session lives 12 h; renew it quietly before that (health ping)
KEEPALIVE_CHECK_S = 20 * 60  # on /health: re-validate the BYD login after this long idle
WARMING_RETRY_S = 5  # retry_after_s sent while the server is still connecting to BYD

# =============================================================================
# Jobs and logs
# =============================================================================

JOB_TTL_S = 15 * 60  # request ids and finished jobs are remembered this long
JOBS_MAX = 500  # never keep more jobs than this in memory
LOG_LINES_MAX = 2000  # /logs ring buffer
