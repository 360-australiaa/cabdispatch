# Backend: NSW 2026 compliance mirror + vehicle/driver operational safety

Two related passes, done directly against `backend/` (the user explicitly authorized this — see
chat history; the earlier convention of leaving an `ANDROID_REPLY_vN.md` "here's what I need from
backend" note no longer applies to this work, since it *is* the backend work). Both verified with
the full backend test suite (`uv run pytest`, 533/533 passing) and live-exercised against a running
local server (`uv run uvicorn app.main:app`, SQLite dev DB, seeded via `scripts/seed.py`).

## Part 1 — NSW Point to Point Transport (Fares) Order 2026, mirrored from the Android pass

The backend's `app/services/fare_engine.py` was still on the 2025 (no.2) rate card and had the
identical maxi-multiplier-scope and rounding bugs the Android client had before this session's
earlier passes. Fixed to match exactly:

- **2026 rate card**: `URBAN_TARIFF`/`COUNTRY_TARIFF` updated (flag fall $5.17/$5.29, peak $2.65,
  distance $2.61/$2.37 and $2.49/$3.41, night $3.10/$2.82 and $2.97/$4.07, waiting 113.0c/108.1c per
  min). Old 2025 card kept as a comment only.
- **Cleaning fee cap** ($124.14): new `Tariff.cleaning_fee_cap` field, enforced (clamped, not just
  documented) in `FareEngine.close()`, and added to `Tariff._RATE_FIELDS` so a tenant setting a
  *higher* cap than the Order allows gets rejected by `validate_against_fares_order` at ingestion —
  the same protection the other rate fields already had.
- **Maxi multiplier scope fix**: the 150% rate now applies only to flag fall + peak + distance +
  waiting ("the fare"), never to tolls/PSL/extras/cleaning fee, which are added on top unmultiplied.
- **Round down, never up**: `fare_total` now uses a new `round_down()` (was `round_half_up`) — Act
  s76(5)/(6): a rank/hail fare must never exceed the regulated maximum, and rounding down is always
  lawful while rounding up isn't. The non-cash surcharge and GST-component figure keep
  `round_half_up` — that's the Order's own explicit rule for those two.
- **Maxi eligibility is now derived, not a raw trusted flag** — the single most important structural
  fix, and one step beyond what the Android side could do on its own:
  - `FareState` gained `is_maxi_vehicle`, `passenger_count`, `wheelchair_hiring`,
    `airport_rank_requested_maxi`, and a computed `maxi_applied` property
    (`is_maxi_vehicle and not wheelchair_hiring and (passenger_count >= 5 or airport_rank_requested_maxi)`).
  - **`is_maxi_vehicle` is resolved server-side from the vehicle's real `Vehicle.vehicle_class`**
    (`app.services.trips.resolve_is_maxi_vehicle`), looked up fresh at trip-creation time — a device
    sending `maxi: true` in its request body is accepted for backward compatibility but is now
    purely advisory and never trusted for billing. This closes a real overcharge/undercharge
    vulnerability: previously a device could set `maxi: true` on any trip regardless of what vehicle
    it actually was, and the 150% rate would apply. Confirmed via a new test
    (`test_close_airport_fixed_trip_ignores_raw_maxi_claim_without_a_real_maxi_vehicle`) that a raw
    `maxi=true` claim on a non-maxi vehicle now correctly bills the standard $60 fixed fare, not $80.
  - `passenger_count`/`wheelchair_hiring`/`airport_rank_requested_maxi` are still driver-supplied
    (only the person in the car knows the real passenger count) — new columns on `trips`
    (migration `2bc9163321d2`), new fields on `TripCreate`/`TripSyncItem`/`TripRead`.
- Golden test suite (`tests/test_fare_engine_golden.py`) rebuilt: all 9 original vectors
  hand-recomputed against 2026 rates, plus new vectors for the full maxi-eligibility matrix (5 pax
  eligible, 4 pax not, wheelchair overrides, airport-rank-request limb, non-maxi vehicle never
  eligible), the round-down-vs-half-up divergence, cleaning-fee-cap clamping, and negotiated
  (Set Price) fare billing.

**Ripple-effect fixes** (stale 2025 literals duplicated across the test suite, now either updated or
— better — changed to *derive* from `fe.URBAN_TARIFF`/`COUNTRY_TARIFF` so they can't drift out of
sync again next time the rate card changes):
- `tests/test_trips.py`'s `_seed_tariff()` helper — was hardcoding 2025 numbers independently
  despite its own docstring claiming to "mirror" the engine constants. Now genuinely derives from
  them.
- `tests/test_tariffs.py::test_fares_order_current_returns_global_row_once_seeded` — seeds a real,
  lastingly-committed global (tenant_id IS NULL) reference tariff row with hardcoded 2025 literals;
  since the shared test DB isn't wiped between tests in the same file, this row became the
  authoritative Fares Order reference for every *later* test in the file, and started rejecting
  perfectly valid 2026-rate presets as "exceeding the cap". Also now derives from `fe.URBAN_TARIFF`.
- A handful of individual tests (`test_tick_distance_mode_accrues_distance_charge`,
  `test_close_trip_computes_breakdown`, a split-fare sync test, etc.) had their own hand-copied 2025
  literals updated to 2026 values.

## Part 2 — Vehicle/driver operational safety: the "one vehicle, two drivers" question

**The question asked**: how does a tablet meter get assigned to a driver when one vehicle runs
back-to-back 12-hour shifts across two different drivers — and how would you actually see, as an
operator, which vehicle currently has which driver, and its full driver history?

**How the assignment model already worked (confirmed, not changed)**: the tablet (`Device` row)
pairs to the **vehicle**, permanently, via a QR pairing code (`POST /v1/fleet/vehicles/{id}/pairing-code`
→ `POST /v1/fleet/devices/register`) — never to a driver. Each driver logs into the *same* tablet
with their own credentials at the start of their shift. "Who is currently driving vehicle X" has
always been, and remains, a live-derived fact — *the driver with an open (`end_at IS NULL`) `Shift`
row for that vehicle* — never a cached pointer anywhere. This is the right design; the two real gaps
were (1) nothing enforced that only one such row could exist at a time, and (2) nothing in the
dashboard surfaced it.

### Gap 1 (backend, real loophole): no overlap protection at all

`start_shift()` had zero validation. Two concrete failure modes were live-reproducible:
- **A vehicle could be double-booked**: driver A forgets to end their shift (crashes the app, drives
  home without tapping "End Shift" — this happens constantly in real fleets); driver B could start a
  completely independent shift on the *same* vehicle at the *same* time. Now there are two "open"
  shifts on one car — genuinely ambiguous who's liable if there's an incident, and which shift a
  trip recorded during the overlap should even belong to.
- **A driver could be on shift twice at once** (same vehicle or different vehicles) — undermines the
  12-hour fatigue-limit tracking entirely, since a driver could just open a second shift rather than
  ever appearing to have worked more than one contiguous stretch.

**Fixed** in `app/services/shift.py`'s `start_shift()`:
1. If the requesting driver already has their *own* open shift (any vehicle) — auto-closed at the
   new shift's start time. Safe by construction: a person can't literally be driving two shifts at
   once, so a fresh start unambiguously means the old one is over. No dispatcher action needed —
   this is the automatic recovery from the "forgot to tap End Shift" case.
2. If the *vehicle* already has an open shift under a **different** driver — blocked with `409
   Conflict`, naming the current driver and when their shift started, unless the caller passes
   `force_handover: true` — the real changeover action. This is the one case that's never silent:
   two drivers can never simultaneously "have" the same vehicle on paper.
3. Live-verified end to end against the running server: driver A starts → driver B blocked with a
   clear 409 → driver B retries with `force_handover: true` → driver A's shift closes at the exact
   handover instant, driver B's opens → `GET /v1/shifts?vehicle_id=X` shows a clean two-row history,
   newest first → `GET /v1/vehicles/{id}` correctly reports the new current driver.
4. New tests in `tests/test_shifts.py` cover all three cases plus same-driver-same-vehicle restart
   (not a conflict, no `force_handover` needed).

### Gap 2 (dashboard, "not linked properly"): current driver and history were invisible

The data to answer "which driver does this vehicle have right now, and who's had it before" already
existed (`GET /v1/shifts?vehicle_id=X`, sortable, filterable) — but only reachable via the separate
Shifts page, with no link from Fleet → Vehicles, and no "current driver" visible at a glance on the
vehicle list at all.

Fixed:
- **`GET /v1/vehicles` (the live-ops rollup) now includes `current_driver_id`/`current_driver_name`/
  `current_shift_id`/`current_shift_start_at`** (`app/services/live_ops.py`, `app/schemas/live_ops.py`)
  — joined live from the shift domain the same way `on_shift`/`vehicle_id` were already joined onto
  the *drivers* rollup, just the missing reverse direction.
- **Dashboard Fleet → Vehicles table gained a "Current driver" column** (name + "since HH:MM") and a
  **"Shift history" row action** (a small icon button) that deep-links to `/shifts?vehicle_id=<id>`
  — the Shifts page now reads an initial `vehicle_id` from the URL on mount, so the link actually
  pre-filters instead of dumping you on an unfiltered list.
- **`StartShiftModal` now surfaces the 409 conflict properly**: instead of a generic "might already
  have a shift" message, it names the driver currently on the vehicle and since when, with an
  explicit "End their shift & start mine" confirmation button that resubmits with
  `force_handover: true` — the dashboard-side equivalent of the driver-app handover action.
- Verified with `tsc --noEmit` (zero errors) — no live browser check was possible from this session
  (sandboxed shell, separate from the user's own Chrome — confirmed with the user directly), so the
  user should give the Fleet and Shifts pages a visual pass.

## What operationally follows from this (for the fleet to actually use day to day)

- **Onboarding a vehicle for double-shifting**: pair the device to the vehicle once (QR code, as
  today); nothing further — any driver can log into that tablet and start a shift on it.
- **A clean handover** (driver A's 12 hours are up, driver B is taking over): driver A taps "End
  Shift" before handing over the keys. If that happens, driver B's "Start Shift" just works — no
  conflict, no dashboard intervention.
- **A messy handover** (driver A forgot, already left): driver B's "Start Shift" on the device (or a
  dispatcher doing it from the dashboard) gets the 409 naming driver A; tapping through
  "force_handover" closes driver A's shift at that moment and starts driver B's. This is now a
  *visible, deliberate* action with a timestamp, not a silent overwrite — the shift history shows
  exactly when the changeover happened and who was on the vehicle for which stretch, which is the
  actual audit trail an incident investigation or a driver pay dispute would need.
- **Checking a vehicle's driver history**: Fleet → Vehicles → the history icon on that vehicle's row
  → a pre-filtered, chronological (newest-first) list of every shift, every driver, every start/end
  time, trip count, and takings for that vehicle.

## Not done / explicitly out of scope for this pass

- `time_class`/`is_peak` are still accepted from the client at trip-open/sync and trusted as-is
  (not independently re-derived server-side from the real clock + a holiday calendar the way
  `is_maxi_vehicle` now is). This is a real, analogous authority gap — a device could under-report
  night/peak trips to avoid the surcharge — but re-deriving it safely touches a much larger surface
  (many existing tests construct trips with a deliberately-set `time_class` to test rate bands
  without needing to actually run the test at night) and was left alone to avoid a large, risky
  change under this pass's time budget. Flagging it as the next real gap to close.
- No FK constraints were added between `shifts`/`trips` and `vehicles`/`users` — this is a
  pre-existing, explicitly documented cross-domain decision in this codebase (each domain was built
  in an isolated slice), not something introduced or touched by this pass.
