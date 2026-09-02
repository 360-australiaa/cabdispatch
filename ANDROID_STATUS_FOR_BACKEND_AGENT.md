# Android agent status → backend agent (2026-08-28)

Separate Claude Code conversation, Android-only scope, no live channel to you — the user is
relaying this by paste. Working branch: `android/battery-network-heartbeat-and-map-fixes`, all
pushed. Everything below was verified live on a real Samsung SM-T575 tablet against your live
server (`72.61.107.107:8001`, tenant "Lilly Cabs"), not just read from code.

## Commits this session (oldest → newest)

1. **Meter blocked outside Sydney** — `RegionResolver` classified any GPS fix >50km from Sydney CBD
   as `"country"`, which this tenant has no tariff for (404) → meter silently refused to start
   off-continent. Fixed: >2000km from CBD now falls back to `urban`.
2. **Real permission prompts** — app declared runtime permissions but never requested them. Added
   an actual request flow + launch-time gate + battery-optimization/background-location intents.
3. **Right-side nav carousel** — moved the dashboard nav rail from left to a draggable right-side
   wheel per product request (cosmetic, not backend-relevant).
4. **Quick Login (Demo Driver) fixed** — was using `driver@lillycabs.test`/a fake password that
   401s against `driver-login` (it wants `driver_code`, not email). Swapped in `GL2HY`/`123456`,
   confirmed live against your DB.
5. **Mapbox SDK + offline maps** — re-enabled real SDK, fixed missing `MapboxOptions.accessToken`
   wiring, added a Karachi offline region (field test location) alongside Sydney.
6. **Real data-loss bug found + fixed**: an app kill/crash mid-fare left a `Trip` row stuck
   `status=open` forever — `SyncWorker` reported "success" without ever draining it
   (`readyToSync=0` rows are silently skipped, not retried), and the existing "OPEN ACTIVE TRIP"
   recovery button navigated to a disconnected $0.00 meter screen. Fixed to route through
   Close & Pay, which correctly reconstructs the fare from Room's persisted counters. Live-verified:
   recovered a real stuck trip, closed it, confirmed `POST /v1/trips/sync → 200 OK`.
7. **Real QR scanning** — replaced the always-null stub with ML Kit's Google code scanner. Verified
   live: real camera scan UI launches, cancels cleanly. Couldn't verify actual decode (no second
   display available to present a QR code to the tablet's camera).
8. **`vehicle_id` mismatch found** (see `ANDROID_NOTE_FOR_BACKEND_AGENT.md`, already dropped in this
   repo) — `shifts/start` was sending the rego, `fleet/positions` sends the UUID, for the same car.
   Fixed Android to send the resolved UUID when available. **This needs a matching backend fix**
   (canonicalize in `start_shift`) to fully close — see that file for the exact diff I sketched and
   reverted so I don't collide with your edits.

## Current live state (as of last test)

Logged in as driver `GL2HY` (real `driver_code`, not the offline demo path), bound to vehicle
`KHI-01` (real UUID `363975ba-8199-4058-98ba-d1fff0c6919a`), shift open, tariff signed
(Ed25519, `Lilly Cabs urban rank/hail`), GPS live in Karachi, dashboard on OFF DUTY.

## What I verified is already fully wired (contradicts stale docs/HANDOFF.md — trust the code)

MFA screen, admin-PIN gate, availability broadcast (`POST /v1/jobs/availability` — live-tested,
`200 OK`), Offline & Sync screen + outbox drain (live-tested), duress cabin-camera capture
(real CameraX, code-reviewed not live-fired — see below).

## What I deliberately did NOT touch, and why

- **Did not fire a real duress trigger.** Even a cancelled trigger calls your live
  `POST /v1/duress/trigger` and could page dispatch/fire Twilio escalation. Code-reviewed only.
- **Did not attempt BLE duress-device pairing** — no physical hardware to test against.
- **Did not touch backend files** — reverted my one `shift.py` sketch; that's your file.

## Ask

If `start_shift` gets the rego→UUID canonicalization, tell me and I'll drop the Android-side
fallback-to-rego branch entirely (right now it's a safety net for the case your fix doesn't exist
yet). Also: if `POST /v1/fleet/devices/register` semantics change (D-3's assignment history), flag
it — Android's pairing flow currently goes through manual rego + `listVehicles()` lookup, not
`register_device`, so device-pairing has never actually been exercised against your live server.
