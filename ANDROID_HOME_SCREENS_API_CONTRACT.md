# Captain Taxis Driver Meter -- Backend API Contract for the First 3 Screens

Backend/API engineer analysis + implementation, 2026-08-29. Figma:
https://www.figma.com/design/NP1afUMe5UKIl3CQUBRnyV/Captain-Taxis (node-id 0:1) -- three frames
inspected directly via the Figma Plugin API, not guessed from screenshots:
"01 . HOME -- Collapsed Rail", "02 . HOME -- Expanded Menu", "03 . START METER -- Pressed/Transition".

This document is implementation-ready: every endpoint below is real, live in this codebase today
(after the changes in Part 7), verified by an independent full-suite test run, not a plan.

---

## PART 1 -- System snapshot (what exists)

FastAPI async monolith (SQLAlchemy async, Alembic, pytest), 20 domain routers, all mounted under
`/v1/*`. Sole multi-tenancy mechanism: `get_current_tenant_id` (bearer-JWT-derived), enforced in
every query -- there is no DB-level row-security, only application-layer filtering. Two real-time
channels exist: `WS /v1/jobs/live` (job-offer push to a connected driver) and
`WS /v1/fleet/live` (live vehicle-position broadcast, dashboard-facing, not used by these 3 screens).

Relevant domains already fully built and live: auth (JWT + driver PIN login + MFA), users, fleet
(vehicles/devices/pairing), tariffs (NSW Fares Order-accurate fare engine, Ed25519-signed),
trips (fare tick/close, offline sync), shifts (full validation as of Phase 3, D-1 double-booking
guarantee, handover), jobs (dispatch/offer/accept), duress (SOS), live_ops (positions), zones,
fatigue_alerts, reports (revenue/GST/PtP export), psl_ledger.

---

## PART 2 -- Screen -> API mapping

### Screen 1 & 2 shared shell (driver header, system status, meter card, dispatch panel, shift
strip, break widget) -- Screen 2 additionally overlays a static left-nav menu (pure client-side
UI state, no new data). Screen 3 is Screen 1 with the meter card swapped to a "starting" visual
state -- covered in Part 2.3.

### 2.1 Driver header

| Figma element | Source | Notes |
|---|---|---|
| Avatar initials / photo | `GET /v1/auth/me` -> `UserRead.photo_url` (fallback to initials of `.name` if null) | |
| "Arsalan Rehman" | `UserRead.name` | |
| "CAP-5517 . Toyota Camry Hybrid" | `GET /v1/fleet/vehicles/{vehicle_id}` -> `rego` + `make`+`model` | `vehicle_id` comes from the driver's current open shift, see 2.4 |
| "VERIFIED" badge | `UserRead.suitability_status == "clear"` | Maps concept, not a literal field named "verified" -- see Part 10 |
| "AVAILABLE" / "Ready to receive jobs" | `GET /v1/jobs/availability` state (see below) | toggled via `POST /v1/jobs/availability` |
| SOS button | `POST /v1/duress/trigger` | see Part 2.5 |

**NOT CURRENTLY AVAILABLE:** there is no `GET /v1/jobs/availability` (read-your-own-state) endpoint
-- only the write side (`POST`) exists, returning the new state. The driver app must cache the
last-known state from its own most recent toggle/login response rather than fetching it fresh on
cold start. Flagged as Category B below -- trivial to add, not built in this pass because it was
not required to make the 3 screens functional (a driver app already knows its own toggle state).

### 2.2 System status panel (GPS / 4G / Printer / Battery / Meter)

**NOT A BACKEND CONCERN for GPS, 4G, Printer, Battery.** These are physical-device hardware state
the Android app already has native access to (Android LocationManager, TelephonyManager,
BatteryManager, and the paired thermal-printer SDK/driver). Round-tripping them through the
server would be redundant latency for information the OS already has locally in real time. Do
**not** build a "device status" fetch for these four -- read them locally.

"METER READY" is the one item with a real backend signal: `POST /v1/fleet/devices/{id}/heartbeat`
now accepts `X-Device-Secret` (see [ANDROID_DEVICE_SECRET_AUTH.md](ANDROID_DEVICE_SECRET_AUTH.md))
and its response includes `kiosk_locked`/`calibration_due` -- "READY" can reasonably mean "device
row exists, is paired (has an active DeviceAssignment), calibration_due is null or in the future".
This is a judgment call for the Android team's own status derivation, not a single field the
backend returns pre-labelled "READY"/"NOT READY".

### 2.3 Meter card (both states)

**OFF state (Screen 1/2):** purely local -- "METER STATUS: OFF / Tap to start a new fare" is
Android's own idle UI, no backend call.

**STARTING/transition state (Screen 3 -- "T1 . CONNECTING", "0.00", "READY 0%", CANCEL button):**
also purely local -- this is the UI's own Bluetooth/hardware-pairing animation for the physical
meter peripheral (if any) before the fare actually opens. The real backend action happens at the
END of this transition:

**`POST /v1/trips`** -- opens the trip (starts the fare). See Part 4 for the full contract.
Tapping CANCEL during the transition simply never calls this endpoint -- no trip was ever created,
nothing to undo server-side.

**NIGHT FARE banner ("1.25x . 10:00 PM - 6:00 AM"):** the 10pm-6am boundary is presently
**hardcoded server-side** in the fare engine (`TimeClass.NIGHT` in `app/services/fare_engine.py`),
not exposed as a queryable field -- safe for Android to hardcode the same display text, since the
backend enforces the real calculation at trip-tick/close time regardless of what the client shows
here. The "1.25x" multiplier itself is not a stored ratio -- compute it client-side as
`night_rate_1 / dist_rate_1` from `GET /v1/tariffs/active` (see Part 5) if you want it derived
rather than hardcoded; either is safe since the display is informational only, never the actual
billed rate.

**"SET PRICE / Fixed Fare . ACTIVE":** maps to `GET /v1/tariffs/active` -> `SignedTariffRead.booked`
(`true` = fixed/negotiated-fare tariff is the active one for this vehicle/region right now).

**"VOUCHERS: 2 . Available":** **NOT CURRENTLY AVAILABLE.** There is no voucher inventory, ledger,
or count anywhere in this backend. `Trip.voucher_code` is a free-text field validated only at
trip-close time (`app.services.payments.redeem_voucher` is an explicit stub -- "does NOT check the
code against any real voucher/promo-code table (none exists yet in this codebase)"). Do not invent
a count. See Part 6, Category C.

### 2.4 Shift strip (SHIFT TIME / TRIPS / EARNINGS / NEXT BREAK)

| Figma element | Source |
|---|---|
| Current open shift (start_at, vehicle_id) | `GET /v1/shifts?driver_id={id}&active_only=true` -- returns the one open `ShiftRead`, if any |
| "SHIFT TIME 06:06 hrs" | client-computed: `now - shift.start_at` |
| "TRIPS 9 Completed / 3 Active" | **NEW**, see Part 4: `GET /v1/trips?driver_id={id}&shift_id={shift.id}&status=closed` (`total` field = completed count) and same with `status=open` (active count) -- an open shift's own `ShiftRead.trips_count` stays 0 until the shift closes (recomputed server-side only at `end_shift`), so this is genuinely a live query, not a shift field |
| "EARNINGS $304.11 Today / up 12% vs yesterday" | **NEW**: `GET /v1/trips/earnings/today?driver_id={id}` -- see Part 4 |
| "NEXT BREAK 01:54 hrs REMAINING / Working until 06:12 PM" | Client-computed from `GET /v1/tenants/me/settings` (`fatigue_shift_duration_limit_hours`) + the open shift's `start_at` + `break_taken`/`break_started_at` (already on `ShiftRead`) -- no dedicated "next break due" endpoint exists or is needed; all raw inputs are already available |
| TAKE BREAK button | `POST /v1/shifts/{shift_id}/break/start` / `POST /v1/shifts/{shift_id}/break/end` |

### 2.5 SOS button

`POST /v1/duress/trigger` -- body `{vehicle_id, driver_id, trigger: "button"|"gesture"|"voice"|"auto"}`.
Returns a `DuressEventRead`. This is a real, live, fully-built endpoint (camera/GPS/audio capture,
Twilio escalation) -- not part of this pass's new work, already exists.

### 2.6 LIVE DISPATCH panel (3 job cards: BOOKED / RANK JOB / BOOKED)

| Figma element | Source |
|---|---|
| Card list | `GET /v1/jobs/{job_id}/offers` per pending offer, or (simpler) the `WS /v1/jobs/live` push -- see Part 5 |
| "BOOKED" / "RANK JOB" badge | **NEW** (this pass): `JobRead.job_type` (`"booked"` \| `"rank_hail"`) |
| "2.1 km . 6 min" | **NEW** (this pass): `JobRead.distance_km` / `JobRead.eta_min` -- server-computed haversine distance + a flat 30km/h heuristic, NOT routed/live-traffic. Flagged as an approximation in the field's own doc comment. |
| Pickup / drop-off address (2 lines each) | `JobRead.origin_address` / `dest_address` -- single free-text strings; the two-line split in the design ("12 Railway Parade" / "Lakemba NSW 2195") is a client-side string split on the first comma, not two separate backend fields |
| "EST. FARE $28.40" | `JobRead.fare_estimate_low`/`fare_estimate_high` -- design shows one figure; use the midpoint or `fare_estimate_high`, your call, both are already on the object |
| ACCEPT button | `POST /v1/jobs/{job_id}/offers/{offer_id}/accept` |
| "3" badge / "VIEW ALL" / "VIEW ALL JOBS ->" | `GET /v1/jobs?status=offered` (or count of pending offers for this driver) -- existing, paginated |

### 2.7 Expanded menu (Screen 2)

Every item is pure client-side navigation to a screen not yet built (Trips/Earnings/Zones/etc.).
No new API surface needed for the menu itself. Each destination screen's own data needs will be
scoped when that screen's Figma frame is analysed -- do not build ahead of the design.

---

## PART 3 -- Authentication

`POST /v1/auth/driver-login` -- body `{driver_code, pin}` -> `TokenResponse
{access_token, refresh_token, token_type: "bearer", user: UserRead}`. This is the driver-facing
login (PIN, not email/password -- `POST /v1/auth/login` is the staff/dashboard path, not for the
meter app). Every other endpoint in this document requires `Authorization: Bearer {access_token}`.
Token refresh: `POST /v1/auth/refresh` with `{refresh_token}`. `GET /v1/auth/me` returns the
caller's own `UserRead` (driver header data, Part 2.1).

---

## PART 4 -- API contracts (new/modified this pass)

### 4.1 `POST /v1/jobs` (existing, modified) -- now accepts `job_type`

Request body adds one optional field: `job_type: "booked" | "rank_hail" = "booked"`. Response
(`JobRead`) gains `job_type`, `distance_km` (Decimal, nullable), `eta_min` (int, nullable) --
both computed server-side at creation from `origin_lat/lng` + `dest_lat/lng`, never client-supplied.

### 4.2 `GET /v1/trips` (existing, modified) -- new filters

New optional query params: `shift_id: str`, `start_at_from: datetime` (ISO 8601, URL-encode the
`+` in a `+00:00` offset -- do not build a raw query string by hand), `start_at_to: datetime`.
All existing params (`driver_id`, `vehicle_id`, `status`, `type`, `skip`, `limit`) unchanged.

Example: `GET /v1/trips?driver_id=abc&shift_id=def&status=closed` -> completed-trip count for the
current shift (`.total` in `TripListResponse`).

### 4.3 `GET /v1/trips/earnings/today` (NEW)

- **Method:** GET
- **Auth:** Bearer token, any authenticated tenant user (not self-only-enforced -- matches this
  domain's existing `GET /v1/shifts` convention; a driver app is expected to pass its own id)
- **Query params:** `driver_id: str` (required)
- **Response 200** (`DriverEarningsTodayRead`):
```json
{
  "driver_id": "5c2e...",
  "date": "2026-08-29",
  "today_total": "304.11",
  "yesterday_total": "271.53",
  "pct_change": 12.0,
  "trips_completed_today": 9
}
```
- **Day boundary:** Sydney-local calendar day (`Australia/Sydney`), NOT UTC midnight -- deliberately
  different from `GET /v1/reports/revenue`'s UTC-day convention (that is a back-office aggregate
  where the exact cutover moment does not matter; this is a driver-facing "today" that must match
  the Sydney calendar day they are actually living in).
- **`pct_change`** is `null` when `yesterday_total` was zero (no baseline to compare against) --
  render "--" or hide the comparison line in that case, do not divide by zero client-side either.
- Only `status: "closed"` trips count. An open trip's `total` is not final.

### 4.4 `POST /v1/trips` (existing, unmodified) -- opens the fare, Screen 3's real action

Required: `client_uuid` (idempotency key, generate fresh per attempt), `vehicle_id`, `driver_id`,
`tariff_id` (from `GET /v1/tariffs/active`), `type` (`"rank_hail"` for a rank flag-down / meter
start with no prior job, `"booked"` if opened from an accepted job offer), `start_lat`,
`start_lng`, `start_at`. Returns `TripRead` (`status: "open"`). This is where "START METER"
actually becomes real once Screen 3's transition animation finishes.

---

## PART 5 -- Real-time contract

### `WS /v1/jobs/live`

Auth: `?token={access_token}` query param (browsers/mobile clients can't set WS headers) or an
`Authorization: Bearer` header if the client library supports it on the handshake.

**Event: `job_offer`**
- Direction: Backend -> Android
- Fires: the instant `POST /v1/jobs` broadcasts a new offer to this connected, available driver
- Payload:
```json
{
  "type": "job_offer",
  "offer": { "id": "...", "job_id": "...", "driver_id": "...", "status": "pending", "offered_at": "...", "expires_at": "..." },
  "job": { "id": "...", "job_type": "booked", "origin_address": "...", "dest_address": "...", "distance_km": "2.10", "eta_min": 6, "fare_estimate_low": "24.00", "fare_estimate_high": "28.40", "status": "offered", ... }
}
```
- Android action: render a new dispatch card immediately, start a local countdown from
  `offer.expires_at` (20-second server-side window, `OFFER_WINDOW_SECONDS`), call the accept/decline
  endpoints below before it expires.

**No other event types are pushed on this channel today.** Offer-expiry, acceptance-by-another-driver,
etc. are NOT pushed -- the client learns those by the accept call itself failing (409/404, see
below) or by re-polling `GET /v1/jobs/{id}/offers`.

### Accept / decline (REST, not WS)

`POST /v1/jobs/{job_id}/offers/{offer_id}/accept` -> `JobOfferRead` (200) or 409 if another driver
already accepted / it expired. `POST /v1/jobs/{job_id}/offers/{offer_id}/decline` -> `JobOfferRead`.

---

## PART 6 -- Fare/meter logic: what stays server-side

**Do NOT port any fare calculation to Android.** The entire NSW Fares Order engine
(`app/services/fare_engine.py`) -- flag-fall, day/night/holiday rate switching, distance-threshold
rate-2 stepping, waiting-time accrual, Maxi multiplier, multi-hire percentage, PSL levy, airport
fixed fares, surcharge cap -- is authoritative and Ed25519-signed server-side
(`GET /v1/tariffs/active` returns `SignedTariffRead` with a signature Android should verify against
`GET /v1/tariffs/signing-public-key` to detect a tampered cached copy). The live per-second/per-metre
fare display during a trip comes from **ticking the server**: `PATCH /v1/trips/{id}/tick` on a
regular interval with the current GPS point; the response is the new authoritative running total.

**Safe to compute locally, display-only, never billed:** the elapsed-time counter between ticks
(pure clock math), the night-fare-multiplier text on the Home screen banner (Part 2.3), a
rough live distance-travelled odometer between ticks (for UI smoothness only -- the server's own
tick response is what actually gets billed and must win on any discrepancy).

**Not currently available / not verified, do not invent:** a distinct "minimum fare" concept
separate from flag-fall (none exists in the fare engine -- flag-fall functions as the effective
floor); GST/tax display breakdown per-trip on the meter UI (GST handling exists for reporting --
`GET /v1/reports/gst-summary` -- not as a live per-trip meter field).

---

## PART 7 -- Backend changes made this pass

| Change | File(s) | Category |
|---|---|---|
| `Job.job_type`, `distance_km`, `eta_min` columns + schema + auto-compute at creation | `app/models/jobs.py`, `app/schemas/jobs.py`, `app/services/jobs.py` | C -- new, required for dispatch-card type badge + distance/ETA, did not exist in any form |
| `GET /v1/trips` gains `shift_id`, `start_at_from`, `start_at_to` filters | `app/api/v1/trips.py` | B -- existing endpoint, additive query params, zero breaking change |
| `GET /v1/trips/earnings/today` | `app/services/trips.py`, `app/schemas/trips.py`, `app/api/v1/trips.py` | C -- new, no per-driver daily-earnings aggregate existed anywhere (the tenant-wide `/reports/revenue` endpoint is staff-role-gated and not date-boundary-correct for a driver's own "today") |
| Migration `9a9364f2c706` | `alembic/versions/` | applies the above; `job_type` server_default='booked' so existing rows do not break |

**Explicitly NOT built, and why:** `GET /v1/jobs/availability` (read-your-own-toggle-state) --
Category B, real but not blocking (client already knows its own last-set state); voucher
inventory/count -- Category C but requires a real product decision (what IS a voucher in this
system) before building a table, not something to invent from a UI badge alone.

---

## PART 8 -- Test results (independently verified, not self-reported)

`uv run pytest -q` -- **689 passed, 0 failed**, 26 pre-existing/unrelated warnings, run fresh
after applying migration `9a9364f2c706`. Breakdown of what's new this pass: 4 tests in
`tests/test_jobs.py` (job_type default/override, distance/eta computed, offer push carries both),
8 tests in `tests/test_trips.py` (shift_id filter, date-range filter, earnings-today sum/isolation/
open-trip-exclusion, zero-trips case). Full migration chain applied cleanly, zero errors.

---

## PART 9 -- Known limitations (explicit, not hidden)

1. Distance/ETA on jobs are straight-line + flat-speed estimates, not routed/live-traffic --
   correct expectation-setting language in the UI ("approx.") is a product call, not enforced
   server-side.
2. Vouchers have no real backend today -- do not ship a fake count.
3. `GET /v1/jobs/availability` has no read side yet -- cache your own last-set state.
4. GST/day-night/night-hours boundaries are hardcoded server-side constants, not tenant-configurable
   today -- fine for a single-region NSW deployment, would need real config if that ever changes.
5. `pct_change` on the earnings endpoint has no defined behaviour beyond `null`-when-no-baseline --
   there is no "N/A due to public holiday" or similar special-casing.

---

## PART 10 -- Instructions for the Android developer

1. **Auth:** `POST /v1/auth/driver-login`, store the token pair, attach `Authorization: Bearer`
   to everything else.
2. **On Home-screen load**, fire in parallel: `GET /v1/auth/me` (header), current open shift via
   `GET /v1/shifts?driver_id={me}&active_only=true`, `GET /v1/tariffs/active` (fixed-fare state +
   night-rate ratio), `GET /v1/trips/earnings/today?driver_id={me}`, and (if an open shift exists)
   `GET /v1/trips?driver_id={me}&shift_id={shift.id}&status=closed` +
   `...&status=open` for the TRIPS widget's two counts.
3. **Open `WS /v1/jobs/live`** once authenticated and stay connected for the life of the session
   (reconnect on drop) -- this is how dispatch cards actually arrive; do not poll `GET /v1/jobs`
   for new offers.
4. **System status panel (GPS/4G/Printer/Battery):** local device APIs only, zero network calls.
5. **"VERIFIED" badge:** `user.suitability_status == "clear"`.
6. **START METER (Screen 3):** run your own connect/pairing animation locally, then call
   `POST /v1/trips` with a fresh `client_uuid` to actually open the fare. CANCEL during the
   animation = just don't call it, nothing to clean up server-side.
7. **Do not implement any fare math.** Tick the server (`PATCH /v1/trips/{id}/tick`) for the live
   running total; trust its response over any local estimate.
8. Full endpoint list, exact field names, and status codes for anything referenced above but not
   spelled out here (accept/decline, break start/end, duress trigger) already exists and is stable
   -- read the relevant router file directly (`app/api/v1/{domain}.py`) if a field name is unclear;
   every route in this codebase has a docstring.

-- Backend/architecture agent