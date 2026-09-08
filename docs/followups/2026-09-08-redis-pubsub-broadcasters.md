# Follow-up: Redis pub/sub for the four in-process broadcasters

**Filed by:** workstream B7 (ops hardening), 2026-09-08.
**Blocks:** running this backend with more than one uvicorn worker, and running
more than one backend container behind Caddy.
**Audit references:** backend audit §6 (`docs/DEPLOY_UBUNTU.md` gaps, item 9).

## Why this is a correctness item, not a performance item

`backend/entrypoint.sh` runs uvicorn with `--workers 1` and `docker-compose.yml`
carries a long comment saying it must stay that way. B7 turned that comment into
an assertion — `assert_single_worker()` in `backend/app/main.py` refuses to boot
when `ENV=production` and more than one worker is detected, and logs `CRITICAL`
otherwise. That is a guard rail, not a fix. Until the work below is done, the
worker count is **not a tuning knob**: raising it does not degrade the service,
it makes it quietly wrong.

Four things hold state in one worker process's memory. Every one of them fails
*silently* above one worker — no exception, no log line, just missing data:

| # | State | File | Symptom above one worker |
|---|---|---|---|
| 1 | Vehicle-position subscribers + last-known-position cache | `backend/app/services/live_ops.py:174` (`_FleetBroadcaster`), singleton at `:236` | A dashboard on `WS /v1/fleet/live` served by worker A never sees positions published to worker B. The live map freezes for a share of users. |
| 2 | Job-offer subscribers, keyed by `driver_id` | `backend/app/services/jobs.py:91` (`JobOfferBroadcaster`), singleton at `:150` | A driver's tablet on `WS /v1/jobs/live` never receives an offer dispatched through another worker. The offer row still exists over HTTP, so it looks like "push is flaky". |
| 3 | Driver message-thread subscribers, keyed by `tenant_id:driver_id` | `backend/app/services/messages.py:212` (`MessageBroadcaster`), singleton at `:273` | Operator↔driver messages do not arrive live. |
| 4 | Duress live-GPS subscribers, keyed by duress `event_id` | `backend/app/services/duress.py:575` (`GPSBroadcaster`), singleton at `:632` | **The most consequential.** The duress desk watching `WS /v1/duress/{id}/live` sees a frozen position for a driver in a panic event. |

Plus a fifth, different in kind and already half-solved:

| # | State | File | Symptom |
|---|---|---|---|
| 5 | JWT `jti` revocation set | `backend/app/core/security.py:147` (`_RevocationStore`) | Already Redis-backed, with a documented per-process in-memory fallback. Above one worker *with Redis reachable* it is correct. Above one worker *without* Redis it is not: a token revoked on worker A stays valid on every other worker. This needs no code change — it needs Redis to be genuinely required in production, which `GET /health` now enforces (`app/core/health.py`: `redis` is a fatal check when `ENV=production`). |

## The seams — each class already documents its own

Every one of the four broadcasters was written with this swap in mind and says
so in its own docstring. The public interface of all four is the same three
methods, and **nothing outside these classes touches `self._subscribers`**:

- `subscribe(<key…>) -> asyncio.Queue`
- `unsubscribe(<key…>, queue) -> None`
- `publish(<key…>, payload) -> int` (returns the number of subscribers reached)

The only asymmetries to reconcile:

- `_FleetBroadcaster` and `JobOfferBroadcaster` are `async` on all three methods
  and guard `_subscribers` with an `asyncio.Lock`; `MessageBroadcaster` and
  `GPSBroadcaster` have **synchronous** `subscribe`/`unsubscribe`. Making the
  latter two async is a signature change at their four call sites (the WebSocket
  handlers listed below) and nowhere else.
- `_FleetBroadcaster` additionally owns a `_latest` last-known-position cache
  (`live_ops.py:236` region) that is read by `GET /v1/vehicles` even when nobody
  is subscribed. That cache is **not** a pub/sub concern: it must become a Redis
  hash (`fleet:latest:{tenant_id}` → `vehicle_id` → JSON) in the same change,
  otherwise the vehicle list silently varies by which worker answers it.
- Keys differ per broadcaster and must be preserved verbatim as channel names,
  because they are the tenancy boundary: `tenant_id` (fleet),
  `driver_id` (job offers), `tenant_id:driver_id` (messages), `event_id`
  (duress GPS). Program plan §1 rule 10 applies — a channel name that drops the
  tenant qualifier is a cross-tenant leak.

The four consumer sites, which are the only places the signature change lands:

- `backend/app/api/v1/live_ops.py:282` — `WS /v1/fleet/live`
- `backend/app/api/v1/jobs.py:269` — `WS /v1/jobs/live`
- `backend/app/api/v1/messages.py:244` — `WS /v1/messages/live`
- `backend/app/api/v1/duress.py:613` — `WS /v1/duress/{event_id}/live`

## Proposed shape

One new module, `backend/app/services/broadcast.py`, holding a
`RedisBroadcaster` base with exactly the interface above:

- `publish(channel, payload)` → `PUBLISH` the JSON to Redis **and** fan out to
  this process's own local queues (so a single-worker deploy with Redis down
  behaves exactly as today).
- `subscribe(channel)` → register a local `asyncio.Queue` and, on the first
  subscriber for a channel, `SUBSCRIBE` on one shared long-lived connection; a
  single reader task per process pushes inbound messages onto the matching local
  queues. `unsubscribe` reverses it, `UNSUBSCRIBE`-ing when the last local
  subscriber for a channel goes away.
- Follow `app/core/ratelimit.py`'s fallback discipline exactly: try Redis, and
  on failure fall back to in-process fan-out, log **one** warning, never crash.
  Unlike ratelimit, the fallback here is only *safe* at one worker — so the
  fallback must also fire `assert_single_worker()`-style loud logging.

The four existing classes then become thin key-shaping wrappers over it, keeping
their current names, singletons and docstrings so no call site outside the four
WS handlers changes.

## Definition of done

1. All four broadcasters route through `RedisBroadcaster`; `_subscribers` no
   longer appears outside `broadcast.py`.
2. `_FleetBroadcaster._latest` is a Redis hash, with the in-memory dict as the
   Redis-down fallback only.
3. A test that runs **two** app instances against one Redis (a real Redis, gated
   the way `TEST_DATABASE_URL` gates the Postgres suite in `tests/conftest.py`),
   publishes on instance A and asserts instance B's subscriber receives it — for
   each of the four channels, and asserting a subscriber on a *different* tenant
   key receives nothing.
4. `assert_single_worker()` in `app/main.py` is relaxed to "warn unless the
   Redis broadcaster is active", and its message here is updated.
5. `entrypoint.sh`'s `--workers 1` becomes `--workers ${WEB_CONCURRENCY:-1}`,
   and `docker-compose.yml`'s SINGLE WORKER comment is rewritten rather than
   deleted — the reason it existed should stay legible.
6. `docs/DEPLOY_UBUNTU.md` gains the worker-count guidance the audit says it
   lacks (§6 gap 9).

## Not in scope

Reliable delivery. Redis pub/sub is fire-and-forget: a subscriber that is not
connected at publish time misses the message, which is the same best-effort
contract all four classes already document and all four consumers already
tolerate (offers, messages and positions are all re-readable over HTTP). Moving
to Redis Streams for at-least-once delivery is a separate, larger decision.
