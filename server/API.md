# BYD Bridge HTTP API (server 3.0)

This is the contract between the server (`server/`, entry point `python main.py`) and its clients:
the phone app, widgets, tiles, MacroDroid, and through the phone the watch. It is backward
compatible: every route, parameter and response field of the 2.7 server still works the same way,
except for the changes listed in [Changes from 2.7](#changes-from-27).

## 1. Basics

- **Base URL**: the Render service URL. Every route is a path segment at the root (`/lock`). A prefix
  is tolerated (`/api/lock` works like `/lock`), and paths are case-insensitive.
- **Method**: any. GET and POST behave the same. The new app sends commands as POST with an empty
  body. **Parameters always go in the query string.**
- **Auth**: `Authorization: Bearer <AUTH_SECRET>` (preferred), or `?secret=<AUTH_SECRET>`. A wrong
  or missing secret gets `401 {"ok": false, "error": "unauthorized"}`. `/health` needs no auth.
  `/logs` and `/info` also accept the logs-only key, `?key=<HMAC-SHA256(AUTH_SECRET, "logs")[:24]>`.
- **Body**: JSON, UTF-8. Keys that the server doesn't know are absent, not `null`, unless this
  document says otherwise.

### Fields on every JSON reply

| field | type | meaning |
|---|---|---|
| `request_id` | string | the caller's `X-Request-Id` (or `?rid=`), or a server-made `s-xxxxxxxx` when none was sent |
| `rev` | int | revision of the car snapshot: goes up on every visible change and never goes down within one `boot` |
| `boot` | string | changes every time the server process starts. A new value means memory was wiped: jobs, request ids, holds, and `rev` restarts at 0 |

Headers on every reply: `X-Request-Id` (the same value as `request_id`) and `X-Boot-Id`.
Status and command replies also carry `Server-Timing: byd;dur=<ms>`. A 429 reply carries `Retry-After`.

**Ordering replies**: a reply with the same `boot` and a higher `rev` is newer. When `boot` differs
from the last one seen, don't compare `rev`; take the reply.

## 2. Routes

| route | auth | what it does |
|---|---|---|
| `/health`, `/ping`, `/` (also HEAD) | no | plain text `OK`. Keeps the free instance awake and the BYD login fresh; it never wakes the car |
| `/health?v=2` | no | `{"ok": true, "boot", "uptime_s", "warm", "version", "rev"}`. `warm` is `false` until the BYD login after a start is done |
| `/status` | yes | the car snapshot (§5) |
| `/<command>` | yes | runs a command (§3, §4) |
| `/jobs/<id>` | yes | one job, by job id or by request id (§4.3) |
| `/logs` | yes/key | the last ~2000 log lines as plain text |
| `/info` | yes/key | diagnostics JSON: session, queue, holds, recent commands, capabilities |

Anything else gets `404 {"ok": false, "error": "unknown_route"}`.

## 3. Commands

| path(s) | action | parameters |
|---|---|---|
| `lock` | `lock` | |
| `unlock` | `unlock` | |
| `ac`, `ac-on`, `climate`, `climate-on` | `climate_on` | `temp` (°C, clamped to 15–31, default 21); `mins` (snapped to 10/15/20/25/30, default 10); `air=recirc` (default) or `air=fresh`; optional `driver_seat`, `passenger_seat` (1–2, sent as extra steps); `defrost` is ignored |
| `ac-off`, `climate-off`, `stop-ac` | `climate_off` | |
| `recirc` | `recirc` | `on=1` recirculate / `on=0` outside air. Restarts the running A/C with the same temperature for the time left. With the A/C off: `409 climate_off` |
| `locate`, `find`, `horn` | `locate` | horn and lights |
| `flash`, `lights` | `flash` | lights only |
| `trunk`, `tailgate`, `open-trunk`, `trunk-open` | `trunk_open` | |
| `trunk-close`, `close-trunk`, `tailgate-close` | `trunk_close` | |
| `trunk-toggle`, `toggle-trunk` | `trunk_toggle` | opens or closes depending on the trunk state (re-read if older than 15 s). The reply's `action` says which one was sent |
| `windows-close` | `windows_close` | |
| `windows-vent` | `windows_vent` | opens to a vent crack |
| `seat`, `seats`, `seat-heat` | `seat` | either `seat=driver\|passenger&level=0..2`, or `driver=0..2&passenger=0..2` (only seats that change are sent) |
| `steering`, `steering-heat` | `steering` | `on=1\|0` |
| `charge-now`, `charge-start` | `charge_now` | |
| `charge-pause` | `charge_pause` | turns the schedule back on so the car waits for its window, then checks the car about 8 s later |
| `charge-schedule` | `charge_schedule` | `mode=window&start=HH:MM&end=HH:MM`, `mode=start_only&start=HH:MM` (until full), `mode=stop_only&end=HH:MM` (from now); `now=HH:MM` is the phone's clock (default: car time zone); `enabled=false` turns the schedule off. Without `mode` or `enabled`: `400` |

Control parameters, which are never part of the command: `secret`, `rid`, `async`. Any other
parameter is part of the command's identity (see request ids).

### 3.1 Request ids (idempotency)

Send `X-Request-Id: <id>` (or `?rid=<id>`). The id is 1–64 characters of `A-Z a-z 0-9 . _ : -`;
anything else gets `400 bad_request_id`.

- **The same id never runs twice.** While the job runs, a request with the same id joins it and
  gets the same result. After it finishes, the stored result comes back at once and nothing is sent
  to the car. Ids are remembered for **15 minutes** after the job is created, within one `boot`.
- **The same id for a different command or different parameters** gets `409 request_id_reused`.
- **An id names one attempt.** To deliberately try again (after `not_sent`, for example), use a
  **new** id. To recover the result of a request whose reply was lost, use the **same** id
  (`GET /jobs/<id>`, or send the same request again).
- **Identical commands are merged**: a command with the same action and parameters as one that is
  still queued or running joins it, whatever its id. A double tap sends one command. The second id
  then also finds that job.
- Without an id, the server makes one (`s-…`) and returns it. Only joining identical commands applies.

### 3.2 The queue

There is one car, so there is one queue: commands run one after the other (BYD refuses overlapping
commands). A command waits for its turn for at most **20 s**. If it hasn't started by then, it is
dropped unsent (`409 busy`, outcome `not_sent`), so a horn can't sound long after the tap. When
more than **4** commands are already waiting, a new one gets
`429 queue_full` with `retry_after_s` (and a `Retry-After` header).

Status reads don't wait in the queue.

## 4. Command replies

### 4.1 Synchronous (default)

The reply comes when the command is done: confirmed, refused, or timed out. That is normally
1–10 s. A single command takes at most about 50 s (20 s in the queue + 30 s for the car). A/C with
seats can take up to 70 s, and `charge-pause` with its check up to about 95 s. Rather than long
client timeouts, use `async=1` (§4.2).

Success (HTTP 200):

```json
{
  "ok": true, "action": "lock", "ms": 3412,
  "outcome": "confirmed", "sent": true,
  "job": { ...job object, §4.4... },
  "car": { ...snapshot, §5... }, "name": "ATTO 3", "updated": 1794038613,
  "caps": { "lock": true, ... }, "soc": "78", "range": "331",
  "request_id": "a1b2", "rev": 17, "boot": "60fae647"
}
```

Failure (HTTP status per §6):

```json
{
  "ok": false, "action": "lock", "error": "timeout", "message": "Car did not confirm in time …",
  "ms": 30120, "outcome": "unconfirmed", "sent": true,
  "partial": true,
  "retry_after_s": 10,
  "job": { ... },
  "car": { ... }, "name": ..., "updated": ..., "caps": ..., "soc": ..., "range": ...,
  "request_id": "a1b2", "rev": 17, "boot": "60fae647"
}
```

- `partial` appears only when it is `true`. `retry_after_s` appears only when the server suggests
  a wait.
- The car fields `car`, `name`, `updated`, `caps`, `soc` and `range` appear whenever anything is
  known about the car.
- `ok` is `true` when the job finished without an error, or when it became `confirmed_late`.

**The only difference for clients that send a request id**: an `unconfirmed` command answers
**`202`** with `"error": "unconfirmed"`, instead of `504` with `"error": "timeout"` as before.
Everything else is identical.

### 4.2 Asynchronous: `?async=1`

The reply comes at once:
`202 {"ok": true, "action": "lock", "job": {...}, "request_id", "rev", "boot"}`.
The job is `queued` (or later). Follow it with `GET /jobs/<job.id>` or `GET /jobs/<request_id>`.
A job that is still running is a normal 200 reply with `job.state` not equal to `done`.
Polling about every 1 s is fine; no job takes longer than about 95 s.

These are refused before any job exists, synchronously: `401`, `400 bad_request_id`,
`409 request_id_reused`, `429 queue_full`.

### 4.3 `GET /jobs/<id>`

`<id>` is a job id (`j…`) or a request id. The reply is
`{"ok": true, "job": {...}, "car": {...snapshot}, "request_id", "rev", "boot"}`.
An unknown, expired (older than 15 min) or pre-restart id gets `404 {"ok": false, "error": "unknown_job"}`.
Compare `boot` to tell a restart apart from a typo.

### 4.4 The job object

| field | type | meaning |
|---|---|---|
| `id` | string | job id (`j` + 10 hex) |
| `request_id` | string | the id it was created with |
| `action` | string | what was actually done (for `trunk_toggle`: `trunk_open` / `trunk_close`) |
| `requested` | string | the action that was asked for |
| `state` | string | `queued` → `running` → `sent` (it reached BYD) → `done` |
| `outcome` | string \| null | `null` until done; then one of the outcomes below |
| `ok` | bool \| null | `null` until done |
| `error` | string \| null | error code (§6), if any |
| `message` | string \| null | human text for `error` |
| `sent` | `true` \| `false` \| `"unknown"` | whether it reached BYD: `"unknown"` = the reply to the send was lost |
| `partial` | bool | some steps were done, a later one failed |
| `steps` | list | one entry per car command: `{name, outcome, sent, error, serial, ms}`. `name` is e.g. `climate_on`, `seat_driver`, `seat_passenger`, `lock`; `serial` is BYD's request serial |
| `queue_ms`, `car_ms`, `total_ms` | int | time waiting in the queue, time talking to the car, and time from request to done |
| `created`, `updated` | int | unix seconds |

### 4.5 Outcomes: what happened, and what the client should do

| outcome | sent | meaning | client |
|---|---|---|---|
| `confirmed` | true | the car confirmed it. For schedule switches (`charge_pause`, `charge-schedule?enabled=false`), BYD's cloud confirmed it | done |
| `unconfirmed` | true | sent, but the car didn't confirm in time (asleep, no signal). **The car may still do it.** The server looks at the car again after ~6 s and ~20 s | don't resend. Follow the job: it may become `confirmed_late` |
| `confirmed_late` | true/"unknown" | was unconfirmed or unknown, and a later car report shows the requested state | treat as done |
| `rejected` | true/false | the car (`sent: true`) or BYD's cloud (`sent: false`) said no | show the message; don't resend automatically |
| `not_sent` | false | certainly never sent: waited too long, BYD busy, server warming up, queue full, A/C off for `recirc` | safe to try again with a **new** request id |
| `failed` | false | it didn't happen: PIN, not supported, bad parameter, BYD login | fix the cause |
| `failed` | `"unknown"` | the reply to the send was lost: it may or may not have happened. The server checks the car later, so it can still become `confirmed_late` | don't resend horn/lights/A/C; follow the job |

`still_charging` (`409`) is the one case with outcome `confirmed` and `ok: false`: the pause was
applied, but the car is inside its charging window and keeps charging.

The server never sends one command twice to the car, except for commands where a second send does
no harm. Those are lock, unlock, A/C off, trunk open/close, windows, seats, steering wheel and the
charging commands. For them the server may send once more when BYD's reply to the first send was lost.
Horn, lights, A/C start and `recirc` are never sent twice.

## 5. Status and the car snapshot

`GET /status` answers from memory while the car's last report is under 20 s old. Otherwise it does
one live read, shared by everyone asking at the same time. `?fresh=1` always reads live (it wakes
the car), and `?debug=1` adds `debug_dump` (BYD's raw realtime payload).

- A live read wakes the car, then reads the A/C, at most 15 s in total.
- If the read fails but the car's state is known, the reply is a normal `200` with the last known
  state. `updated` tells its age.
- With nothing known yet, the reply is an error: `504 timeout` when the car is asleep, or
  `503 warming_up` right after a restart.

Reply: `{"ok": true, "action": "status", "ms", "car", "name", "updated", "caps", "soc", "range", "request_id", "rev", "boot"}`.
`updated` is the unix time of the car's own last report: commands don't make it fresher.

`car` (a missing key means unknown):

| key | type | |
|---|---|---|
| `soc`, `range_km`, `odometer_km` | int | |
| `cabin_c`, `outside_c` | int | °C |
| `locked`, `doors_open`, `trunk_open`, `windows_open` | bool | |
| `doors`, `windows` | `{lf, rf, lr, rr: bool}` | |
| `charging`, `plugged` | bool | |
| `charge_kw` | number | 0 when not charging |
| `charge_eta_min` | int | while charging |
| `car_on`, `online` | bool | |
| `efficiency_kwh100` | number | |
| `seat_heat` | `{driver, passenger: 0..2}` | |
| `steering_heat` | bool | only after a steering command |
| `tires` | `{fl, fr, rl, rr: number, unit: "bar"\|"psi"\|"kPa"}` | |
| `climate_on`, `recirc` | bool | |
| `climate_set_c` | int | |
| `climate_until` | int | unix time the A/C started from the app runs out. Present only while it runs |
| `schedule` | `{enabled, start, end ("HH:MM" or "full"), mode?}` | |
| `data_ts` | int | the car's own timestamp of its last realtime report |

**Holds.** A confirmed command shows its effect at once, and that effect is held against slower
car reports. The hold ends at the first of these:

- the car reports the same value;
- the car sends a report made at least 2 s after the confirmation (by the car's own clock) that
  says otherwise, for example locked with the key fob;
- a report without a car timestamp (A/C, schedule) says otherwise after a 20 s grace window, which
  covers the A/C spinning up;
- 60 s have passed.

Car reports older than the snapshot (by `data_ts`) are ignored.

## 6. Error codes

| `error` | HTTP | outcome | sent | when |
|---|---|---|---|---|
| `busy` | 409 | not_sent | false | waited more than 20 s in the queue; or BYD kept answering "previous command still running" (6024) until the deadline; or no time left in the job for this step |
| `queue_full` | 429 | not_sent | false | more than 4 commands waiting; see `retry_after_s` |
| `warming_up` | 503 | not_sent | false | the server is still logging in to BYD after a start; see `retry_after_s` |
| `timeout` | 504 | unconfirmed | true | sent, not confirmed in time (for clients without a request id) |
| `unconfirmed` | 202 | unconfirmed | true | the same, for clients that send a request id |
| `timeout` | 504 | failed / not_sent | unknown / false | rare: BYD didn't answer in time, or the seat levels couldn't be read (nothing sent). `/status`: the car is asleep and nothing is known |
| `rejected` | 502 | rejected | true / false | the car refused (controlState 2, or `res` 3 for charging), or the cloud refused (1009/1001) |
| `climate_off` | 409 | not_sent | false | `recirc` while the A/C is off |
| `still_charging` | 409 | confirmed | true | after `charge-pause` (see §4.5) |
| `byd_unreachable` | 502 | not_sent / failed | false / unknown | network trouble on the way to BYD; `sent` says whether it may have gone out |
| `byd_1008` | 502 | not_sent | false | BYD overloaded, kept refusing |
| `byd_6002` | 502 | not_sent | false | BYD can't reach the car right now |
| `byd_<code>` | 502 | failed | false | another BYD error code |
| `byd_bad_reply` | 502 | failed | as known | BYD's answer could not be read |
| `login_failed` | 502 | failed | false | BYD login refused. It is retried at most every 10 s, doubling up to 2 min. While a retry is pending, `retry_after_s` says how long |
| `pin_5005` / `pin_5006` / `pin_5011` / `pin_commands_disabled` | 403 | failed | false | wrong control PIN / locked for today / not set / not verified |
| `not_supported` | 501 | failed | false | the car doesn't offer that command |
| `bad_request` | 400 | failed | false | bad or missing parameter |
| `request_id_reused` | 409 | not_sent | false | the id was used for another command |
| `bad_request_id` | 400 | — | — | malformed `X-Request-Id` |
| `unauthorized` | 401 | — | — | |
| `unknown_route` | 404 | — | — | |
| `unknown_job` | 404 | — | — | |
| `no_vehicle` | 502 | failed | false | the BYD account has no car |
| `server_error` | 500 / 503 | failed / not_sent | as known | a bug, or the server shutting down (503, not sent) |

## 7. Timings (server settings, `bydbridge/config.py`)

| setting | value |
|---|---|
| one car command: send + confirmation | ≤ 30 s, checked every 0.8 s (slower while BYD says busy) |
| one request with several steps | ≤ 50 s (a step isn't started with less than 6 s left) |
| queue wait | ≤ 20 s |
| live status read | ≤ 15 s |
| status cache | 20 s |
| look again after an unconfirmed command | after ~6 s and ~20 s |
| request ids / finished jobs remembered | 15 min |
| one HTTP call to BYD | ≤ 12 s (a single confirmation check ≤ 5 s) |

## 8. Changes from 2.7

For clients that don't send a request id:

1. **Different commands at the same time queue** instead of answering `409 busy` after 10 s. A
   command still waiting after 20 s is dropped unsent (`409 busy`). More than 4 waiting gives `429 queue_full`.
2. **Never sent twice.** Before, an expired BYD session while waiting for the car made pybyd send
   the command again. Now polling continues with the same serial. A network blip while waiting no
   longer fails a command the car is executing.
3. "Previous command still running" (6024) is waited out, re-sending every 3 s until the deadline,
   instead of 3 × 5 s inside pybyd.
4. **Holds** end as soon as the car reports newer data (it was a fixed 60 s for lock, windows and
   trunk). The A/C hold is a 20 s spin-up window (it was 6 s), so the A/C no longer flips back to
   off on the app's check at +8 s.
5. The A/C is read after the car wakes, not at the same time.
6. **Multi-step commands** (A/C with seats, both seats) report `partial` and keep the steps that
   were done. Before, a failed seat step also dropped the A/C state.
7. Unconfirmed commands are checked once or twice afterwards (this wakes the car) and can become
   `confirmed_late` (visible through `/jobs`).
8. Right after a restart, commands wait for the BYD login for up to 20 s, then get `503 warming_up`.
   Before, they waited without a limit. `/status` with nothing known gets `503 warming_up`.
9. A failed BYD login no longer resets the client on every request. Logins are retried after
   10 s, doubling up to 2 min.
10. A BYD reply without a serial counts as `timeout`/unconfirmed (it was `rejected`).
11. New fields everywhere: `request_id`, `rev`, `boot`. Commands also get `outcome`, `sent`, `job`
    and sometimes `partial` or `retry_after_s`. New headers: `X-Request-Id`, `X-Boot-Id`, `Retry-After`.
    `/info` has new keys. The VIN is masked in logs and in error messages.
