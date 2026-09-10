# Full-Stack Audit (2026-09-11) — Backend, Dashboard, Android refresh, Security/API

**Scope:** the whole product — `backend/` (FastAPI), `dashboard/` (React/TS, every one of 31 routes), `android/` (refresh of the 2026-09-08 architecture audit against 3 days of fixes), and a dedicated cross-cutting security/API-contract pass. Produced by four parallel review agents plus manual cross-checking; review-only, no files edited by the audit itself. Severity convention matches the prior Android audit: **blocker** = wrong money / lost data / unusable / life-safety, **major** = real defect with a workaround, **minor** = hygiene.

**Companion document:** `docs/plans/2026-09-11-gps-blackout-hardware-fallback-and-program-plan.md` — the GPS-blackout hardware-fallback design and the prioritized remediation plan built from this audit.

---

## Executive summary

The good news first, because it's true and because the 2026-09-08 Android audit and this pass both independently confirm it: **this codebase is unusually well-remediated for its age.** Of the 2026-09-08 Android audit's 12 fare-accuracy findings (F1-F12), 5 toll findings (T1-T5), 5 sync findings (S1-S5), and 6 security findings (X1-X6), the overwhelming majority are now fixed, most in two large, explicitly-titled remediation commits (`87a5615`, `7ff2792`) that cite the original finding IDs directly in their commit messages. The four hardware mocks (§5) are gone. The dead dashboard screens are deleted. Giant files got split. This is a team that reads its own audits and acts on them — the findings below should be read in that spirit: real, concrete, evidenced — not a verdict on the project's trajectory.

That said, four parallel passes over backend/dashboard/Android/security surfaced a genuinely large new list, because a codebase this size always has one. The most serious, cutting across all four reports:

- **Two independent driver-to-driver IDOR findings** (backend `S1` on duress events, security-audit `A1` on messages) — a driver's own bearer token can read or interfere with *another* driver's private data. Different agents, different endpoints, same root cause: a REST sibling of a correctly-gated WebSocket handler never got the same ownership check.
- **A live, unprotected voucher double-spend** (security `P1` / backend `M6`) — found by *both* independent passes, which is strong corroboration. Trivially scriptable, direct revenue loss.
- **The meter's core "STOPPED" state genuinely doesn't exist server-side** (backend `T1/F3`) — multi-hire payment pauses and driver breaks get billed as waiting time for their whole duration. This is a real compliance gap, not a rounding error, and it interacts directly with the GPS-blackout work (see the companion plan).
- **Airport Fixed Fare eligibility is a client-declared field with no server-side geofence check** (backend `T2`) — any client can claim the flat $60/$80 fare and skip every other charge.
- **The non-cash surcharge cap has no hard server-side ceiling** (backend `F1`) — a tariff edit can legally-cap-violatingly push card surcharges past 5%.
- **Dashboard: a closed/paid trip can be re-edited without recomputing the fare** (`TRP-1`), **a reconciled shift can be permanently deleted** (`SFT-1`), and **9 of 13 real NSW toll roads have no working repricing UI** (`TAR-1`) — three "blocker" dashboard findings that are all either wrong money or lost financial records.
- **The duress escalation cascade only advances when someone happens to have the dashboard open** (backend `M10`) — no scheduler exists; an unwatched real panic event can sit `open` forever with dispatch never notified.

None of these are architecture-level surprises requiring a rewrite — each has a concrete, scoped fix cited below. See the companion plan document for a single merged, prioritized fix list across all four reports.

---

# PART 1 — Backend (`backend/app/`)

*Six parallel focused passes: fare-engine/tariff compliance, security/tenant-isolation, tolls/payments/PSL/duress, data-integrity (models vs migrations), code-health/API-contract, trip/shift lifecycle.*

## Architecture

**ARCH1** | `app/services/fare_engine.py` (whole module) | **minor (positive finding)** | Unlike Android's audited "two `FareEngine` types, deliberately unconverged" (F8, 2026-09-08 audit), the backend has a single canonical fare-calculation module that both the online `/tick` path and the offline `/sync` recompute path route through. No internal divergence between a "display" engine and a "billed" engine. Keep any future toll/PSL/surcharge logic funneled through this one module.

**ARCH2** | `app/services/duress.py:575` (`GPSBroadcaster`) | **minor** | The live duress GPS relay is in-process (`dict[str, set[asyncio.Queue]]`), not the Redis pub/sub the spec calls for. In any multi-worker deployment, a GPS point POSTed against one worker never reaches a dispatcher websocket on a different worker — live tracking during a real panic event can silently go dark depending on load-balancer routing. Fix: implement the documented Redis pub/sub swap before any multi-worker production deployment.

## Correctness

**F2** | `app/schemas/trips.py:244-245,345-346` | **blocker** | `surcharge_pct` and `cleaning_fee` on `TripCloseRequest`/`TripSyncItem` accept negative values, which flow straight into billed totals — `FareEngine.close()` only clamps the *upper* bound. A client sending `cleaning_fee: -50` on close or sync reduces the persisted total/GST below the correctly-metered fare. Fix: `Field(ge=0)` on both schema fields, plus a defensive `max(value, 0)` clamp inside `FareEngine.close()` itself.

**M1** | `app/api/v1/trips.py:281` + `app/services/trips.py:761` | **blocker** | Offline-synced trips trust the client's toll figure completely — `recompute_from_trace` never calls `apply_toll_detection` against the replayed GPS trace, only the live `/tick` path independently detects tolls. The 1%-variance tamper check can never catch a fabricated/inflated toll on any offline trip. Fix: replay `gps_trace` through `apply_toll_detection` inside `recompute_from_trace` and flag/reject a mismatch against `item.tolls`.

**M7** | `app/api/v1/payments.py:424-455` | **blocker** | Stripe webhook handling has no event-id idempotency and no status-ordering guard — no Stripe `event["id"]` is stored/checked anywhere. A redelivered event re-stamps `captured_at`; a late `payment_intent.succeeded` arriving after a `charge.refunded` silently flips a refunded payment back to `succeeded`. Fix: persist processed Stripe event ids and skip replays; never let a webhook regress a terminal status back to non-terminal.

**T1 / F3** | `app/services/trips.py:290-410,741-793`; `app/schemas/trips.py:81-88`; `app/models/trips.py:143-145` | **blocker** | The spec's `HIRED ⇄ STOPPED` state (multi-hire payment stops, driver breaks — no accrual while stopped) has **no representation anywhere on the wire or in the model**. `TelemetryPoint` carries only `{lat, lng, speed_kmh, ts}`; `FareState.hired` is hardcoded `True` in both `build_fare_state` and `recompute_from_trace`. Even the new GPS-blackout branch in `recompute_from_trace` still routes the entire gap duration into `engine.tick()`, only substituting distance — a genuine pause resumed at low speed gets billed as waiting time for its whole duration. Fix: add an explicit accrual-state signal to the telemetry/sync wire schema and thread it through into `FareState.hired`, excluding declared pauses from both distance and waiting accrual. **See the companion plan — this should be designed alongside the hardware-fallback work, not separately.**

**T2** | `app/api/v1/trips.py:175,353`; `app/services/trips.py:210-211,478,733-734` | **blocker** | Sydney Airport Fixed Fare eligibility is a pure client-declared `type` field with **no server-side geofence check** — `GEOFENCE_KIND_AIRPORT` is used only for the separate access fee at pickup, never to gate the fixed-fare branch. Any client can declare `type="airport_fixed"` on an arbitrary trip and skip all normal distance/time/tolls/PSL/peak charges. Fix: verify `start_lat/start_lng` lies inside a real `kind="airport"` geofence before honoring `type == "airport_fixed"` at create/close/sync.

**T3 / M4** | `app/api/v1/trips.py:711-763,734`; `app/services/trips.py:290-419` | **major** | No row lock or optimistic-concurrency check guards `Trip` during `/tick` (`grep -rn "with_for_update"` returns nothing, no version column). Two genuinely concurrent tick batches (a mobile-network retry racing the original) both read the same baseline and the later commit silently discards the earlier one's distance/fare — `tick_seq`/`last_ts` only dedupes replays of *identical* content, not divergent concurrent batches. Fix: `SELECT ... FOR UPDATE` the trip row (or an optimistic version column) before applying a tick batch.

**T5** | `app/services/fare_engine.py:545-561`; `app/services/trips.py:369,784` | **major** | The distance-vs-waiting tariff switch trusts client-reported `speed_kmh` with no cross-check against the GPS-implied speed the server already computes for the plausibility clamp. A miscalibrated/malicious client can misreport `speed_kmh >= 26` while GPS shows near-zero movement, forcing distance mode (near-zero charge). Since `speed_kmh` also drives the "independent" `/sync` recompute used for the ±1% tamper check, a calibrated manipulation can stay within tolerance on the grand total while skewing the distance/waiting split underneath it. Fix: derive accrual mode from measured implied speed as the authoritative signal; treat client `speed_kmh` as advisory only.

**T4** | `app/services/shift.py:339-348,67-83`; `app/models/shift.py:69-71` | **major** | The guard against two drivers simultaneously holding the same vehicle is app-level TOCTOU, not DB-enforced — no partial unique index on `(tenant_id, vehicle_id) WHERE end_at IS NULL`. Two concurrent `POST /v1/shifts/start` for the same vehicle by two different drivers can both pass `_find_open_shift` before either commits. The fix pattern already exists in this codebase (`c774793b6619_fatigue_alerts_shift_dedup_unique.py`, same shape, different table) — just wasn't applied here.

**F5** | `app/services/tariffs.py:69-87` | **major** | No protection against two tariffs being simultaneously "active" for the same `(tenant_id, region)` — `_resolve_effective_row` picks by `ORDER BY effective_from DESC LIMIT 1` with no overlap check at write time. Which rates a trip bills depends on insertion order, not operator intent. Untested. Fix: enforce non-overlap at write time, or a Postgres `EXCLUDE USING gist` constraint.

**M2** | `app/services/tolls.py:403-419`; `app/models/toll.py:445` | **major** | `TollGantry.direction` — real recorded ground truth for ~a third of the 141 gantries — is never read; charge/no-charge on a directional road (including the Harbour Bridge/Tunnel the spec calls out by name) is gated purely by coarse 45°-bucket GPS bearing classification even where ground truth exists. Fix: prefer `gantry.direction` when recorded, fall back to bearing only when NULL.

**F9** | `app/services/fare_engine.py:562-566` | **minor** | `tick()` silently drops a tick's elapsed time entirely when `speed_kmh >= threshold` but `distance_delta_km <= 0` (GPS jitter) — neither distance nor waiting accrues, violating the module's own "exactly one always accrues" invariant. Under-charges. Fix: route that elapsed time through the waiting-rate branch instead of discarding it.

**M3** | `app/services/tolls.py:815` | **minor** | Network-cap absorption order is nondeterministic within one tick (bare Python `set` iteration order). Fix: iterate a stably-ordered sequence.

**M5** | `app/services/psl_ledger.py:75-100` | **minor** | `get_or_create_ledger_entry` is a check-then-insert race despite a real backing unique constraint — the second concurrent first-top-up for a driver/period 500s on an uncaught `IntegrityError` after the Stripe charge already succeeded. Fix: catch and re-select, or upsert.

**M6** | `app/services/payments.py:314-341` (`redeem_voucher`) | **minor (backend classification) — see security `P1` below, blocker in combined view** | Check-then-set race with no lock on voucher redemption. **Independently found by the security/API-contract audit as `P1` (classified there as blocker, with the concrete concurrent-`close`/`sync` exploit path spelled out)** — see Part 4. Fix: `SELECT ... FOR UPDATE` the voucher row, or a conditional `UPDATE ... WHERE redeemed_at IS NULL`.

## Security (backend-local findings; see Part 4 for the dedicated cross-cutting pass)

**S1** | `app/api/v1/duress.py:129,143,162,317,345,381,452,656` | **blocker** | Duress lifecycle/read endpoints check only `tenant_id`, never `driver_id` — any authenticated driver in a tenant can view, silence, and eavesdrop on another driver's panic event. `_get_owned_event` filters by `tenant_id` alone and backs `cancel`, `post_gps`, `upload_audio`/`get_audio`, and the snapshot routes. Concretely: driver A can `GET /v1/duress` to find driver B's open event, `POST /v1/duress/{id}/cancel` to silence B's SOS inside its 10s window, or fetch B's captured panic audio/camera frames — contradicting the module's own docstring that these are "the actions a driver's own device takes." No test covers this. Fix: add an ownership check (`event.driver_id == caller.id`) for driver-role callers on trigger/cancel/gps/audio/snapshot; scope `list_events`/`get_event` to the caller's own driver_id for driver-role callers.

**M8** | `app/services/payments.py:257-266` (`verify_and_parse_webhook`) | **major** | Webhook signature verification fails open — it only checks the Stripe signature when `STRIPE_WEBHOOK_SECRET` is set ("dev-only fallback"). If unset in production, the endpoint silently accepts unsigned JSON from anyone who can reach it. Fix: refuse to start / 503 when the secret is unset, unless an explicit dev/test flag is also set.

**S2** | `app/api/v1/users.py:50,115` | **major** | `GET /v1/users` and `GET /v1/users/{id}` have no role dependency — any authenticated tenant user, including a `driver`, can list/read every coworker's full PII (email, phone, driver licence no., licence/authority expiry dates). Every mutating route in the same file has an admin guard; the two GET routes don't. Fix: gate both to staff roles, or scope a driver caller's view.

**F6** | `app/services/tariff_signing.py:93-109` | **major** | The Ed25519 anti-tamper signature over the on-device-cached tariff omits `cleaning_fee_cap`, a regulated money field, unlike the two other places this field list is defined elsewhere in the codebase. A tampered/relayed cap on the device's cached tariff isn't caught by the on-device verifier (server recompute would flag it after the fact, but the passenger is already shown/charged the wrong amount). Fix: add `cleaning_fee_cap` to `RATE_FIELDS` (and the Android-side verifier).

## Data Integrity

**D1** | `app/services/duress.py:366`, `app/services/duress_device.py:144` | **blocker** | Duress-event escalation state is read-modified-written with **no row lock or version column anywhere** (`grep -rn "with_for_update"` over `app/` returns nothing). `advance_escalation_if_due` runs on every `GET /v1/duress` (the ops dashboard polls ~5s), and a second path mutates the same column independently. Two racing sessions can silently drop a cascade stage or — worse — both independently observe the "fire the 000 call" stage and both place the real Twilio call. The right pattern already exists in this codebase (`audit_log.py`'s `_acquire_chain_lock`) but was never applied here. Fix: wrap the escalation read-modify-write in a per-event lock.

**D2** | `app/models/trips.py:162-165`, `payment.py:55`, `shift.py:105-106`, `duress.py:110-111`, `duress_device.py:54` | **major** | Cross-domain FKs (`trips.vehicle_id/driver_id/shift_id/tariff_id`, `payments.trip_id`, `shifts.driver_id/vehicle_id`, `duress_events.vehicle_id/driver_id`, `duress_devices.vehicle_id`) are plain indexed strings with no FK constraint — deliberately, "until all domains are registered together." That's now true (`app/models/__init__.py` registers all 12 domains on one `Base.metadata`), and the two domains built *after* that integration (`vouchers.py`, `driver_engagement.py`) already use real `ForeignKey(...)`. Fix: add real FK constraints now.

**D3** | `app/models/trips.py:206`, `shift.py:108` | **major** | `Trip.start_at`/`Shift.start_at` carry no index despite being the primary date-range filter/sort column for every report and list endpoint. No migration ever indexes either column — a full scan once a tenant accumulates real trip volume. Fix: `index=True` (ideally composite `(tenant_id, start_at)`) plus a migration.

**D4** | (informational) | **minor** | Confirmed no current model/migration drift anywhere else in the schema; the one historical drift instance is fully migrated and now guarded by a dedicated test. Keep that test green.

## Compliance

**F1** | `app/schemas/tariffs.py:38,84`; `app/services/fare_engine.py:129-140,785-788` | **blocker** | Non-cash surcharge cap has **no hard server-side ceiling** — `surcharge_pct_cap` on the tariff schema has no upper bound, and `Tariff._RATE_FIELDS` (used by Fares Order validation) omits it, so a tariff can be PATCHed to e.g. 15% and `FareEngine.close()` clamps every card trip to *that tariff's own* cap rather than a fixed 5% — the exact scenario the spec explicitly prohibits. Untested. Fix: a hard code-level ceiling independent of the DB value (`min(pct, tariff.cap, Decimal("5.0"))`), plus `le=5.0` on both schemas.

**F4** | `app/services/fare_engine.py:623-658` | **blocker** | The Sydney Airport Fixed Fare Trial absorbs the non-cash surcharge instead of adding it on top — a "2026-09 consistency call" comment extends a ruling given for *unregulated negotiated* fares onto the *regulated* fixed-fare trial, where the spec explicitly lists surcharge as a permitted addition on top of $60/$80. Fix: confirm against the actual trial terms; if additive, restore it and update the golden test that currently asserts the absorbing behavior.

**M9** | `app/services/psl_ledger.py` (whole module) | **major** | PSL accrual is entirely manual, not the spec's "automated collection" — the $1.32 `trip.psl` computed and billed at close is never written into any ledger entry; the only writers are admin CRUD endpoints taking raw operator-typed numbers with no link back to any trip. No auto-topup threshold exists anywhere, despite the spec's explicit SmartMove-parity requirement. Fix: accrue automatically on trip close; implement the auto-topup threshold.

**M10** | `app/services/duress.py:490-548` | **major** | The duress escalation cascade **only advances when `GET /v1/duress` happens to be called** — there is deliberately no scheduler/cron/lifespan worker anywhere in the backend. If no dispatcher has the duress dashboard open, a real panic trigger stays `open` forever: dispatch never notified, emergency contacts never SMSed, the 000 call script never fires — functionally defeating the mandated escalation cascade for a life-safety control, especially after hours. Fix: a minimal periodic sweep (ASGI lifespan background task) calling `advance_escalation_if_due` over open events.

**F7** | `app/services/fare_engine.py:20,127` | **minor** | `cleaning_fee_cap = Decimal("124.14")` doesn't reconcile with the spec's "max $120.00 + GST" (= $132.00), nor with the ~3.4-3.6% scaling applied to every other 2026 rate constant. Fix: verify against the actual gazetted clause; if it should be higher, the current constant under-caps what tenants may legally charge.

**F8** | `app/services/tariffs.py:48-55` | **minor** | Stale comment: `_HARDCODED_REFERENCE` is documented as "2025 (no.2) fallback" but actually holds the 2026 constants. Harmless, but misleading for the next audit.

## Code Health

**C1** | `app/api/v1/fleet.py` (1,136 lines) | **major** | Bundles vehicle CRUD, device/tablet lifecycle, fleet reporting, and a destructive test-data-wipe endpoint. Fix: split into `fleet_vehicles.py`, `fleet_devices.py`, `fleet_reports.py`; move the wipe endpoint to an explicit admin/testing router.

**C2** | `app/services/trips.py` (1,019 lines) | **major** | Spans fare-state assembly, raw tick processing, trip-close/lifecycle, and driver-earnings aggregation; its haversine/plausibility helpers duplicate similar geometry math in `app/services/tolls.py`. Fix: extract a shared `geo.py`; split lifecycle from tick processing and from earnings aggregation.

**C3** | `app/services/duress.py` (773 lines) | **minor** | Mixes the state machine, on-disk evidence storage, Twilio telephony, and a websocket broadcaster in one module.

**C4** | `app/services/shift.py:613-781` | **minor** | Mixes shift business logic with FPDF/CSV report rendering.

**C5** | `app/api/v1/trips.py` (974 lines) | **minor** | Combines CRUD/lifecycle with receipt delivery — a notifications concern; `app/services/receipts.py` already exists separately.

**C6** | `app/services/fare_engine.py:282,302` | **minor** | Two open TODOs: 2027 NSW public-holiday dates are computed from standard rules, not the official gazette (the government has occasionally varied) — directly affects peak/holiday classification once 2027 trips are metered.

**C7** | codebase-wide | **minor (informational)** | Traced every apparent "untested module" candidate; this backend organizes tests by feature/workflow, not 1:1 by filename. No genuinely untested/orphaned service module was actually found once traced.

## API Contract

**A1** | `app/schemas/trips.py:236-269,296-361` | **major** | `device_total` is validated inconsistently between `TripCloseRequest` (`ge=0`) and `TripSyncItem` (no constraint), and `tolls`/`extras`/`cleaning_fee` have zero constraints anywhere, unlike `passenger_count`/`tip_amount`/`negotiated_total` in the same schemas. Fix: `Field(ge=0)` everywhere money/quantity fields appear.

**A2** | `app/schemas/auth.py:19-41` | **minor** | `DriverLoginRequest.pin` has no length/pattern constraint, unlike `VerifyAdminPinRequest.pin`/`AdminPinSetRequest.pin` (both `min_length=4, max_length=8, pattern=r"^\d{4,8}$"`). Fix: apply the same constraint.

**A3** | `app/api/v1/shifts.py:113-124` | **minor** | The 409 shift-conflict handler raises a structured-dict `detail`, the sole exception to the codebase's otherwise consistent string-`detail` convention (930+ other call sites).

**A4** | (positive finding) | — | Pagination is consistently applied across every list-returning endpoint checked; no genuinely unpaginated endpoint found beyond static reference data.

---

# PART 2 — Dashboard (`dashboard/src/`, all 31 routes)

## Screen inventory

| Route | Verdict |
|---|---|
| `/` Overview | OK — minor findings only |
| `/getting-started` | OK — minor finding |
| `/live-map` | Findings below — **LM-1 blocker** |
| `/dispatch` | Findings below |
| `/messages` | Findings below |
| `/duress` | Findings below — well-engineered but two spec deliverables missing |
| `/trips`, `/trips/:tripId` | Findings below — **TRP-1 blocker (money)** |
| `/shifts`, `/shifts/:shiftId` | Findings below — **SFT-1 blocker (data loss)** |
| `/tariffs` | Findings below — **TAR-1 blocker (money, 9/13 toll roads unrepriceable)** |
| `/zones` | OK — minor design drift only |
| `/psl` | Findings below — spec's auto top-up feature missing entirely |
| `/fleet` | Findings below — major client-side role-gating gaps |
| `/drivers/:driverId` | OK — solid, well-tested |
| `/vehicles/:vehicleId` | Findings below (delete-permission mismatch, pagination) |
| `/devices/:deviceId` | OK — 2 of 5 tabs are honest placeholders, rest solid |
| `/compliance` | OK — no bugs found, role gates match backend exactly |
| `/billing` | Findings below — **BIL-1 major (invoice truncation)** |
| `/payment-recon` | OK |
| `/vouchers` | OK — correctness/auth clean; zero test coverage |
| `/announcements`, `/incentives`, `/wallet`, `/ratings` | OK — role gating verified correct; zero test coverage on all four |
| `/audit-log` | Findings below — **AUD-1 major (permission-gate doc/code mismatch)** |
| `/settings/white-label` | OK |
| `/settings/security` | OK — good test coverage |
| `/platform` | OK — double-gated correctly, verified no cross-tenant leak |
| `/login`, `/forgot-password`, `/reset-password` | Findings below — minor |

No dead top-level route was found — every directory under `src/pages/` is reachable. Dead code exists only at the individual export/helper level (see below).

## Correctness / UX bugs

**TRP-1** | `pages/trips/TripFormModal.tsx:322-351`, `backend/app/api/v1/trips.py:656-682` | **blocker** | Editing a *closed* trip never recomputes the fare — fare-affecting fields aren't gated by `trip.status` (unlike Delete, two lines below, whose tooltip says closed trips are financial records). Backend `update_trip` does a raw `setattr` with no status check and no engine recompute. An owner/admin can change a paid trip's tariff/tolls and the receipt, GST line, and earnings reports keep showing the old total. Fix: disable fare-affecting fields once closed client-side; reject/recompute those fields server-side.

**SFT-1** | `pages/shifts/index.tsx:192-199,310-355`, `backend/app/api/v1/shifts.py:351-359` | **blocker** | A reconciled/ended shift — a closed financial record — can be permanently deleted with the same generic confirm dialog as an unreconciled one. `delete_shift` has no restriction on `reconciled`/`end_at`, unlike `delete_trip`'s 409 for the same class of record. Fix: block/warn deletion of a reconciled shift server-side.

**TAR-1** | `backend/app/schemas/toll.py:114-131`, `pages/tariffs/NswTollRoadsPanel.tsx:437-543` | **blocker** | The only write surface for NSW toll pricing cannot reprice 9 of 13 real toll roads. The create schema has no fields for `rate_per_km_class_a`/`flagfall_class_a`/network caps/`time_of_day_rates_class_a` (present on the read schema/DB model, no write path) — affects 6 roads; the other 3 `per_point` roads (M2, CCT, LCT) offer "Add price revision" with no endpoint behind it at all. Since tolls pass through to passengers at cost, a stale rate here is wrong money on every affected trip. Fix: add the missing fields per pricing model; add a toll-point price-revision endpoint + UI; hide the button for kinds the form can't actually price.

**LM-1** | `pages/live-map/PublishPositionModal.tsx:36-43` | **blocker** | The manual "Publish position" form resets itself while typing: its reset effect depends on `[open, vehicles]`, and `vehicles` gets a new reference roughly every 5s from live WS ticks, wiping mid-entry input. Fix: reset only on `open` changing.

**BIL-1** | `pages/billing/index.tsx:374-430` | **major** | Invoice list silently truncates at 200 rows with no pagination UI or indicator, unlike `SubscriptionsView` in the same file which wires real pagination. Fix: wire `skip`/`limit` + `Pagination` the same way.

**VEH-002** | `pages/vehicles/tabs/TripsTab.tsx:64`, `TollsTab.tsx:49` | **major** | Vehicle Trips/Tolls tabs cap at 100 with no total UI; Tolls' totals are derived from that same capped fetch, silently undercounting for any vehicle with >100 trips.

**FLT-002** | `pages/fleet/DriversPanel.tsx:249-260` | **major** | "Add driver" PIN field allows non-numeric input despite becoming the meter's numeric-keypad PIN. Fix: `inputMode="numeric"` + digits-only validation.

**FLT-003** | `pages/fleet/DriversPanel.tsx:144-153`, `backend/app/services/live_ops.py:965` | **major** | Drivers list "Status" filter is free text but the backend does an exact match — wrong case silently returns zero rows. Fix: replace with a `Select`.

**LM-2** | `pages/live-map/FleetLocateList.tsx:49-54,75` | **major** | Search claims to match "the tablet's Android ID" but filters the internal `device_id` UUID — typing the real Android ID printed on a tablet returns zero matches.

**LM-3** | `pages/live-map/index.tsx:111-118` | **major** | Vehicle history trail freezes at the absolute time the hour button was clicked and doesn't reset on vehicle switch — a stale window can be silently relabeled onto a newly-selected vehicle.

**LM-4** | `pages/live-map/VehicleDetailModal.tsx:178-225` | **major** | "Restart app" never confirms completion, unlike Locate on the same panel and Fleet ▸ Devices' equivalent.

**DSP-1** | `pages/dispatch/CreateJobModal.tsx:110-157`, `DispatchMapPicker.tsx:41-132` | **major** | A job can be submitted with no real coordinates (free text without a Mapbox suggestion → `NaN` → 422); placement-mode toggle never auto-advances, letting a dispatcher place two pickup pins.

**DSP-2** | `pages/dispatch/CreateJobModal.tsx:189-193` | **major** | Backend validation detail is never surfaced — a generic message shown for every failure.

**MSG-1** | `pages/messages/useMessagesLive.ts:44-59` | **major** | Live WebSocket has no reconnect/backoff, despite `useLiveMap.ts` implementing exactly this for the same kind of feed in the same codebase.

**MSG-2** | `pages/messages/api.ts:51-58` | **major** | `listLatestThread`'s probe-then-fetch isn't atomic — a message sent between the two calls can be silently dropped.

**MSG-3** | `pages/messages/index.tsx:48-55` | **major** | Per-driver unread badges fan out one call per driver — up to ~200 requests every 30s for a 100-driver roster.

**MSG-4** | `pages/messages/api.ts:27` | **major** | Driver picker hard-capped at 100 with no pagination.

**DUR-1** | `pages/duress/GpsTracePanel.tsx:158` | **major** | GPS trace tooltip formatter has no null-guard, can throw on hover for a mixed-source incident.

**DUR-2** | `pages/duress/useDuressLiveGps.ts:75-76,94-98` | **major** | No auto-reconnect once the live GPS socket closes/errors mid-incident.

**DUR-3** | `pages/duress/DevicesPanel.tsx:70-119` | **major** | Devices table never renders `gnss_fix`/`signal_csq`/`firmware_version` — dispatch has no visibility into whether a panic button has GPS/signal until an incident is underway.

**DUR-4** | `pages/duress/DuressAudioPlayer.tsx:21-24` | **major** | The physical panic device's own audio has no backend stream route, renders as raw text — against the spec's "audio listen-in player" deliverable.

**DUR-5** | module-wide | **major** | The spec's branded PDF incident export is entirely unimplemented.

**PSL-3** | `pages/psl/*` | **major** | "Auto top-up settings" doesn't exist anywhere — a spec-listed feature simply missing (matches backend `M9`).

Minor findings (TRP-2/3, OVW-1, LGN-1, TAR-2, PSL-2, ZON-2, FLT-004, VEH-003, DEV-001, DSP-3/4/5, MSG-5/6, DUR-7/8/9/10, LM-6/7/8, AUD-2/3/4): pagination-cap inconsistencies, formatting/locale nits, dead exports, stale comments, test-only-covers-fallback-path gaps — full detail preserved in the source agent report; grouped into the "silent cap, no pagination" and "cleanup pass" buckets in the companion plan's prioritized list rather than repeated here line-by-line.

## Auth / permission gaps

**LM-5** | `backend/app/api/v1/live_ops.py:192-233` vs `pages/live-map/index.tsx:65` | **major** | `POST /v1/fleet/positions` (Publish Position) is gated client-side to owner/admin/dispatcher, but the backend enforces only tenant membership — no role check, no ownership check. Any authenticated tenant user, **including a driver**, can spoof another vehicle's position/status; the dashboard's role gate is purely cosmetic. Fix: add server-side role/ownership enforcement.

**AUD-1** | `pages/audit-log/index.tsx` vs `components/layout/Sidebar.tsx:142-146` | **major** | The sidebar's own comment claims the "Verify chain" action is owner/admin gated in `index.tsx` — it isn't; no role check exists at all, unlike the sibling gated screens (`WalletPage`, `RatingsPage`) which have a real check. Fix: add the gate the comment already claims exists.

**FLT-001** | `pages/fleet/DevicesPanel.tsx:362-427`, `VehiclesPanel.tsx:219-284` | **major** | List-page destructive/admin actions (kiosk lock/unlock, restart, force update, locate, delete, pair) have zero client-side role gating — fully enabled for any authenticated user including `dispatcher`, who only learns it's blocked after a 403. The correct pattern (`ActionButton`/`canManage`) already exists on the detail pages and just wasn't applied to the two list panels.

**VEH-001** | `pages/vehicles/VehiclePage.tsx:70,207-211,256` vs `backend/app/api/v1/fleet.py:247-256` | **major** | Delete gate disagrees with the backend and tells the user something false: client says "Only an owner can delete," backend actually allows owner OR admin.

**TRP-4** | `pages/trips/index.tsx:268-270` | **minor** | "New trip" shown to every role including `driver`, whose Driver picker lets them pick any driver — backend correctly 403s a mismatch, so it's data-safe, but a driver hits an unexplained dead end.

**Verified correct (no gap):** Wallet/Ratings/Audit-log-read role gates match backend exactly; Platform console is correctly double-gated with no cross-tenant leak possible even bypassing the client guard; Tariff Studio's Fares Order cap is enforced both sides; every write surface in Tariffs/Zones/PSL/Vouchers/Compliance/White-label/Security has a 1:1 role-gate match; tenant scoping (guessed foreign UUIDs) is sound everywhere checked, including the Duress websocket.

## Design/consistency, dead code, API drift, test coverage

Full per-item detail is preserved in the source agent transcript (available on request) — headline items folded into the companion plan's cleanup-pass bucket: two incompatible form-label styles across ~34 occurrences (`GEN-1`), Zones' pre-copy-pass ASCII typography (`ZON-1`), several stale comments describing code that's since changed (`FLT-005/005b/005c/006`), a handful of dead exports (`MSG-8/9`, `DUR-9`, `AUD-3/4`, `PSL-1`, `LM-utils`), and one real type-shape drift (`DSP-6`: dashboard `Job` type missing `distance_km`, which `JobRead` always returns).

**Test coverage headline:** every money-handling modal/tab in Trips/Shifts is untested (a test on "fare fields disabled once closed" would have caught `TRP-1` directly); PSL and Vouchers have **zero tests anywhere**; Fleet's list panels have **zero tests** (exactly where `FLT-001/002/003` were found); Messages has **zero tests anywhere**; Duress Desk — the safety-critical module — has **19 of 20 files untested**, including the full incident state machine.

---

# PART 3 — Android (refresh of the 2026-09-08 audit)

## Finding-by-finding status

**Headline:** three large remediation passes fixed nearly everything. Commit `87a5615` ("Fare engine: the meter stops dying, stops under-billing, and stops inventing kilometres") fixed F1, F2, F3, F4, F6, F7, F8, F9, F11, F12, T1, T2 together, explicitly citing this audit's finding IDs. Commit `7ff2792` ("A2: close the offline-login authorisation bypass, and stop losing offline shifts") fixed X1, X2, X3, X6, S1, S2, S3. Commit `b624ef6` ("A5: hardware honesty") fixed the §5 mocks.

| ID | severity | status | evidence |
|---|---|---|---|
| F1 (fixed 1s-tick assumption) | blocker | **FIXED** | `FareEngine.kt:920-926` — measured `System.nanoTime()` delta, clamped |
| F2 (time-integrated distance) | blocker | **FIXED** | `FareEngine.kt:1005-1013` — real `GeoMath.distanceKm` between fixes |
| F3 (no fix-staleness timeout) | blocker | **FIXED** | `MAX_FIX_AGE_MS=5_000L`, `FareState.gpsLost` published. Fix lives in `FareEngine.kt`'s consumer logic — `RealLocationProvider` itself still just freezes on a stale fix; the billing layer refuses to trust it. |
| F4 (no foreground service) | blocker | **FIXED** | New `MeterForegroundService.kt`, fare engine process-scoped via `AppContainer`/`MeterController` |
| F5 | minor | unchanged (informational) | — |
| F6 (GPS jitter) | major | **FIXED** (Kalman filter explicitly out of scope) | accuracy floor + stationary clamp |
| F7 (hardcoded threshold ×3) | minor | **FIXED** | single `DEFAULT_SPEED_THRESHOLD_KMH` |
| F8 (two FareEngine types disagree) | major | **FIXED** | `runningTotal` now sourced from `calcEngine.close().grandTotal` every tick |
| F9 (int-metres precision loss) | major | **FIXED** | decimal-string `accruedDistanceCharge`/`accruedWaitingCharge` columns |
| F10 | minor | reclassified as intentional | — |
| F11 (Close & Pay hard-fails, no fallback tariff) | major | **FIXED** | falls back to `URBAN_TARIFF`/`COUNTRY_TARIFF`, surfaced as a banner |
| F12 (day-boundary zone mismatch) | major | **FIXED** | `NSW_FARE_ZONE` used consistently; backend twin fixed separately in `8ff877b` |
| T1 (manual/auto toll double-count) | major | **FIXED** | `addToll` suppresses the matching auto-toll and vice versa |
| T2 (toll registry refreshed once, ever) | major | **FIXED** | reconnect trigger + 15-min worker both refresh |
| T3 | minor | unchanged (informational) | — |
| T4 (hardcoded SHB/SHT bands) | note | unchanged, expected (i18n backlog) | — |
| T5 (toll audit trail never synced) | minor | **STILL OPEN**, now explicitly documented | `autoTolledRoadsJson` still never reaches `TripSyncItemDto`; a toll dispute has no server-side per-road evidence if the device is lost |
| S1 (no attempts cap/backoff) | major | **FIXED** | `getReadyBatch` filters `attempts < maxAttempts AND nextAttemptAt <= now`; dead-letters |
| S2 (malformed rows skipped silently) | minor | **FIXED** | dead-lettered with a reason |
| S3 (shift-start fabricates synthetic success) | blocker | **FIXED**, one residual footgun | `OutboxBackedShiftRepository` queues through the outbox; the still-offline synthetic `ShiftDto` returned before drain carries `tenantId=""` (N3 below) |
| S4 | minor | unchanged, safe | — |
| S5 (tariff refresh single call site) | major | **FIXED** | new `sync/TariffRefresh.kt`, three call sites |
| X1 (offline login accepts server rejection) | blocker | **FIXED** | only `IOException`/`SocketTimeoutException` falls back offline; `HttpException` 401/403 clears the cache and fails |
| X2 (unsalted PIN hash) | major | **FIXED** | PBKDF2-HMAC-SHA256, 120k iterations, per-device salt |
| X3 (plaintext tokens/secrets) | major | **FIXED** | `EncryptedSharedPreferences` throughout, one-time migration deletes the old plaintext file |
| X4 (fabricated demo driver in prod-facing build) | major | **FIXED (by deletion)** | `seedOfflineDemoDriver` and the quick-login button removed entirely |
| X5 (cleartext base URL) | major | **PARTIALLY FIXED** | release builds gate on `https://`, but field tablets still run debug builds against the real production IP over cleartext — root cause (no TLS on the backend) is outside this repo |
| X6 (full request/response bodies to logcat) | minor | **FIXED** | `Level.HEADERS`, `Authorization`/`X-Device-Secret` redacted |

**Related architecture items:** dead dashboard screens deleted (P0.3/P0.4); god-files split (`DeckHomeScreen` 2,704→857 lines, `HiredScreen` 2,594→930); kapt→KSP migrated; `DeviceReadinessViewModel`'s hardcoded `"urban"` fixed; heartbeat vehicle-rebind self-heal wired; kiosk wording corrected to "screen pinned"; OTA endpoints now send `X-Device-Secret`; a `JurisdictionConfig`/`FareRegion` seam exists but is scaffolded-not-activated (still NSW everywhere in practice); `AppContainer` remains a global singleton and `synchronized(this)` was fixed to a private lock but the singleton pattern itself is unchanged by design; `TripRepositoryOfflineTest.kt` now exists (closing the blocker test gap); `RoomMigrationTest.kt` exists as a JVM unit test (not instrumented `androidTest`); the pre-shift inspection checklist remains explicitly, deliberately unimplemented.

**Still genuinely open (blocker-adjacent):** `TariffSignatureVerifier`/`TariffCanonicalPayload`/`TariffCache` — **zero tests exist for the Ed25519 verify or canonical byte layout**, and the `TariffSignatureVerifier.kt:121` TODO is unresolved.

## New findings

**N1** | `ui/screens/settings/SettingsViewModel.kt:131` | **major** | The GPS-simulator admin-PIN gate the original audit recommended was built and wired (`AdminPinGateScreen` in `SettingsScreen.kt:216-224`) but is **currently switched off**: `const val SIMULATOR_REQUIRES_ADMIN_PIN = false`, opened to all users by an explicit, documented owner decision ("fine for field-test tablets, not for a real fleet"). Any driver can still inject synthetic GPS speed into the live `SpeedSource` the fare engine bills from, with no PIN, producing a fare for a trip that never happened. **Fix: flip the constant before any real-fleet rollout — the mechanism is a one-line change, already built.**

**N2** | `ui/screens/zones/PlotZoneScreen.kt` (327 lines) + `ZoneStatisticsScreen.kt` (227 lines) | **major** | New dead code: the `PLOT_ZONE`/`ZONE_STATISTICS` route constants were fully removed when zones UI was consolidated into `ZonesPaneContent()` embedded in `DeckHomeScreen`, but the two standalone screen files (554 lines) are left behind, unreferenced. Fix: delete both.

**N3** | `domain/ShiftRepository.kt` (`OutboxBackedShiftRepository.startShift`) | **minor** | The offline-queued synthetic `ShiftDto` returned while a shift-start is still in the outbox carries `tenantId = ""`, carried forward from the old approach. No current consumer reads it before drain, but it's a latent footgun for a future call site.

**N4** | `data/local/entity/TripEntity.kt:190-192` (T5, restated) | **minor** | The stated rationale for not syncing the per-road toll audit trail ("the server runs no toll detection on the sync path, so it stays empty anyway") argues for why the server doesn't need to *recompute* tolls — not for why the *evidence* shouldn't be persisted for dispute resolution. Worth an explicit product decision rather than an implicit trade-off.

**N5** | `domain/LivePositionHeartbeat.kt:272` | **major** | Confirmed still unaddressed: `HEARTBEAT_INTERVAL_MS` is a flat 5s with no screen-off/Doze-aware backoff. At 17,280 requests/day/tablet fleet-wide, this remains the largest unaddressed network-cost item from the original audit.

**N6** | `ui/navigation/CabDispatchNavHost.kt:202-204` | **minor** | `RATE_PASSENGER`'s `onDone` still does `popUpTo(IDLE)` not `popUpTo(0)` — never revisited.

**No outright regressions were found.** Every recently-touched area either fixed the cited issue or left it provably unchanged; the one place code moved backward from the original recommendation (N1) was a deliberate, documented, reversible product decision, not an accident.

## GPS-blackout / hardware survey

*(Full detail — this is the foundation for the companion plan's centerpiece design.)*

**`domain/fare/KnownCorridor.kt`** solves GPS blackout specifically inside mapped toll-road tunnels. It matches the blackout's entry/exit GPS fixes against the nearest gantries on each cached toll road (within 250m), reconstructs the intra-road distance via a greedy nearest-neighbour walk over the road's own gantry chain, and returns the shortest qualifying corridor's distance, or `null` if none matches. It **never** estimates distance from device sensors — the code's own doc records that accelerometer/gyroscope dead-reckoning was explicitly considered and rejected in the same design pass, to avoid reopening the fabricated-distance bug class (F2). Confirmed wired and live in `FareEngine.kt`'s `tick()`.

**`domain/location/RealLocationProvider.kt`** has no fix-staleness timeout of its own — `_speedKmh`/`_locationFix` simply freeze at the last accepted value if fixes stop arriving. The 5s staleness cutoff lives one layer up, in `FareEngine.tick()`'s consumer logic. Any new hardware speed source would need to either replicate that convention itself or continue relying on `FareEngine` gating on `receivedAtNanos` the same way.

**Hardware/sensor sweep — zero real hits.** A full-tree grep for OBD/OBD-II/Bluetooth\*/BLE/SensorManager/wheel-or-vehicle-speed/pulse/CAN-bus/ELM327/GATT/externalSpeed/speedSensor/odometer found nothing real: every `OBD` hit is inside `JobDto`/`getJob`/`cancelJob` unrelated Retrofit code; every `bluetooth` hit describes the **unimplemented** Bluetooth thermal receipt printer (explicitly stubbed, `isReal=false`, no `BLUETOOTH*` manifest permission); the one `Accelerometer` hit is `KnownCorridor.kt`'s own doc *rejecting* sensor estimation; `pulse` hits are all Compose UI animation effects. `build.gradle.kts` has no OBD-II/BLE/car-diagnostics dependency of any kind.

**Verdict: no live path for any non-GPS distance signal exists today.** Fare distance comes exclusively from `FusedLocationProviderClient` GPS fixes, with `KnownCorridor.kt`'s static, retroactive, mapped-toll-road-only geometry lookup as the sole exception. **The good news for a hardware-fallback feature: a provider-agnostic `SpeedSource` interface already exists** (implemented today by `RealLocationProvider` and a `StubSpeedSource`/`SwitchableSpeedSource` in `AppContainer.kt`) — this is the natural, already-built seam for a third implementation. There is no existing permission plumbing, dependency, UI surface, or staleness contract beyond that interface — all of it would need to be built from scratch, replicating `FareEngine`'s `receivedAtNanos`/5s convention so a new source plugs cleanly into the existing blackout-detection logic.

---

# PART 4 — Security & API-contract (dedicated cross-cutting pass)

*This pass independently re-verified the prior audits' security findings (all fixed — see Parts 2/3 above) before hunting new ground: multi-tenant isolation, AuthZ, payment/billing fraud, auth/session mechanics, API-contract consistency, input validation, across backend + dashboard + Android together.*

## Multi-tenant isolation

**M1** (reports) | `backend/app/services/reports.py:91,100` | **minor** | Driver-name/tariff-name lookups in the P2P export omit an explicit `tenant_id` filter — not currently exploitable (ids are derived from an already-tenant-filtered trip query), but worth adding as defense-in-depth.

**M2** (duress webhook) | `backend/app/api/v1/duress.py:284-297` | **minor** | The Twilio status-callback scans every tenant's `DuressEvent` rows to match a `CallSid` — deliberate and signature-verified, not a leak, but an unbounded O(table) scan on every inbound call-status webhook. Fix: add an indexed `twilio_call_sid` column.

## AuthZ

**A1** | `backend/app/api/v1/messages.py:137-170` | **major** | `GET /v1/messages?driver_id=<id>` and `POST /v1/messages/{id}/read` have **no ownership check** — any authenticated tenant user, including a `driver`-role token, can read (or mark-read, hiding it from the real recipient) any *other* driver's private dispatch message thread just by supplying a different `driver_id`. The WebSocket sibling for the same feature already checks this (`if role == driver and sub != driver_id: close()`) — the REST endpoints never got the equivalent check. **Same root-cause pattern as backend `S1` on duress** — two different agents, two different features, same class of gap: a correctly-gated WS handler with an ungated REST sibling. Fix: port the WS check into `list_thread`/`mark_read`.

**A2** | `backend/app/api/v1/billing.py:65-107,181-227` | **major** | `GET /v1/billing/subscriptions[/{id}]` and `GET /v1/billing/invoices` have **no role dependency at all** — not even `get_current_user`. Every mutating billing route in the same file is correctly gated; the three read routes aren't. A driver can pull the operator's Stripe plan, subscription status, price, and full invoice history. Contrast `corporate_accounts.py`'s identical-shaped endpoints, which explicitly document the open-read as an intentional decision — `billing.py` has no such rationale, suggesting oversight. Fix: add the same admin gate to the three read routes.

## Payment/billing fraud

**P1** | `backend/app/services/payments.py:265-286` (`redeem_voucher`) | **blocker** | Classic TOCTOU: selects the voucher, checks `redeemed_at is None` in Python, then sets it on the same ORM object — **no `SELECT ... FOR UPDATE`, no DB constraint on "already redeemed," no version column** (the model's only unique constraint is on `(tenant_id, code)`, the voucher's identity, not its redemption state). Two concurrent requests carrying the same `voucher_code` for two different trips (two sync batches, or sync racing close, or simply a client retry racing its own original in-flight request) can both read "not yet redeemed" before either commits — **the same voucher ends up backing two separate trips, and the operator eats both fares for the value of one voucher.** Trivially scriptable: fire two close/sync requests with the same code within a few hundred milliseconds. **Independently found by the backend audit as `M6`** — two separate adversarial passes landed on the same bug, strong corroboration of real severity. Fix: row-level lock, or a conditional `UPDATE ... WHERE redeemed_at IS NULL` that fails the second writer.

**P2** | `backend/app/services/fare_engine.py:227-236`, `backend/app/schemas/trips.py:67-78` | **minor** | Negotiated ("Set Price") fares replace the entire computed fare, bounded only by `[$1.00, $500.00]` with no relationship to distance/time actually driven and **no variance/plausibility check against the GPS trace** the way the metered-fare path has its 1% check. A driver can open any trip as negotiated and close at $1.00 while collecting the real cash fare off the books, evading commission/PSL entirely, and nothing ever flags it. May be an accepted trade-off (NSW doesn't rate-regulate negotiated fares) but is an easy, low-effort skimming vector as shipped. Fix: consider a non-blocking plausibility flag against the GPS-implied fare, mirroring the existing metered-fare pattern.

## Auth/session

No new blocker/major findings — logout revocation, refresh-token rotation, WS token-type/revocation checks, tenant-suspension enforcement, the three-secret production guard, and driver-login tenant scoping + rate limiting were all spot-verified as correctly implemented in this pass (these were prior findings, now fixed).

**S1** (deployment caveat) | `backend/app/core/security.py:210-263`, `backend/app/core/ratelimit.py` | **minor** | Both the JWT revocation store and rate limiter fall back to per-process in-memory state when Redis is unreachable, silently, after one log line. In any multi-worker deploy without reachable Redis, every rate limit is effectively multiplied by worker count and a revoked token can still validate against a worker that never saw the revocation. Documented as a known trade-off in both modules — flagging as a live *operational* dependency (Redis must actually be reachable in production), not a missing defense.

## API contract

**C1** | `dashboard/src/hooks/useTrips.ts:35-125` vs `backend/app/schemas/trips.py`/`shared/openapi.json` | **major** | The backend's `TripRead` (and the checked-in OpenAPI spec, which correctly includes both fields) return `tip_amount` and `negotiated_total` on every trip — real, populated money columns. The dashboard's hand-maintained `Trip` TypeScript interface declares **neither field**, and zero dashboard source references either. There is no OpenAPI-to-TS codegen step. Effect: every driver tip and every negotiated-fare trip collected through the meter is silently invisible on the dashboard's trip list/detail/reconciliation views — a dispatcher or finance user cannot see a trip was negotiated, or that a tip was recorded, even though the data exists server-side. **This directly compounds `P2` above** — the one surface that would let an operator spot a pattern of suspiciously-low negotiated fares can't currently render the field at all. Fix: add both fields to the `Trip` interface and surface them in the detail view; longer-term, generate dashboard types from `shared/openapi.json`.

## Input validation

No findings. Every `text()`/`execute()` call in the backend uses parameterized queries with bound params; no `dangerouslySetInnerHTML` anywhere in the dashboard; upload handling strips path components, UUID-prefixes stored filenames, and rejects any resolved path outside the backend root.

---

## Consolidated severity counts

| Severity | Backend | Dashboard | Android (new) | Security/API | Total |
|---|---|---|---|---|---|
| Blocker | 7 (F1,F2,F3,F4,M1,M7,T1/F3,T2 — 8 counted individually) | 4 (TRP-1, SFT-1, TAR-1, LM-1) | 0 new (all prior blockers fixed except 1 test gap) | 1 (P1, corroborates backend M6) | ~13 |
| Major | 12 | ~16 | 4 new (N1, N2, N5, +prior partial X5) | 4 (A1, A2, C1, M8/S2 combined) | ~36 |
| Minor | 8 | ~20 | 3 new (N3, N4, N6) + 2 unresolved (T5, TariffSignatureVerifier tests) | 3 | ~36 |

See the companion plan document for the single merged, cross-referenced prioritized fix list and workstream plan built from all of the above.
</content>
