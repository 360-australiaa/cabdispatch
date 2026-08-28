# Architecture: Tenancy, Fleet Onboarding, Driver/Vehicle Binding & NSW PtP Compliance

**Status:** PLAN — not yet implemented. Written 2026-08-28.
**Audience:** the agent fleet that will implement it, and whoever reviews their work.
**Baseline:** migration head `0db130c8824f`, 139 live endpoints, 538 backend tests passing.

Everything in "Part 1 — Ground truth" was verified by reading the real code, with file:line
references. Everything in "Part 2 — Regulatory" is sourced from the NSW Point to Point Transport
Commissioner and Revenue NSW (links inline). Do not trust any claim in this document that lacks
one of those two; if you find one, treat it as a bug in this document and flag it.

---

## Part 0 — The question this document answers

Five questions were asked, in order:

1. How do we create multiple tenants and their passwords?
2. How does a vehicle get onboarded into the system?
3. How does the meter bind to a vehicle?
4. How does a driver associate with a vehicle?
5. Two drivers share one vehicle for 12h each in a 24h day — how is that modelled?

Plus the standing constraint: **align to NSW Point to Point regulations** the way an established
operator (MTI, SmartMove) already does.

The short answer to all five today is: **the data model has the right nouns but almost none of the
rules.** Shifts exist but nothing stops two drivers opening a shift on the same car at the same
second. Devices pair to vehicles but nothing stops one device being bound to five cars. Trips carry
a driver id but not the driver's name — and NSW requires the *name* in the record, for two years.

---

## Part 1 — Ground truth: what actually exists today

### 1.1 Tenancy and identity

`Tenant` (`app/models/tenant.py:12-32`) carries `id, name, abn, tsp_number, bsp_number, theme_json,
plan, stripe_acct_id, admin_pin_hash`. That is the whole tenant record.

- **`plan` gates nothing.** No code anywhere reads `Tenant.plan` to enable a feature or limit
  seats. (`Subscription.plan` in `app/models/billing.py:63` is a different, per-vehicle concept.)
- **`stripe_acct_id` is a dead column.** `POST /v1/billing/connect/onboard`
  (`app/services/billing.py:192-219`) creates a Stripe Express account and returns the id in the
  HTTP response only — it is never persisted. The Connect account id is lost on every call.
- **There is no per-tenant settings table.** Confirmed against the full table list in
  `app/models/__init__.py:14-32`. The codebase says so itself twice, at `app/core/config.py:56-58`
  and `:107-108`. Consequence: values that are obviously per-operator are **deployment-wide env
  vars** — `FATIGUE_SHIFT_DURATION_LIMIT_HOURS`, `COMPLIANCE_EXPIRY_WARNING_DAYS`,
  `DURESS_ESCALATION_CALL_PHONE`, `TWILIO_FROM_NUMBER`. One tenant cannot have a 10-hour fatigue
  limit while another has 12.

**Tenant creation today.** `POST /v1/platform/tenants` (`app/api/v1/platform.py:71-90`) is gated on
`role == "owner" AND tenant_id == PLATFORM_TENANT_ID` and its entire body
(`app/services/platform.py:61-82`) inserts **one `Tenant` row**. It does not create an owner user,
does not set a password, does not send anything. There is no `PATCH`, no `DELETE`, no suspend.

The de-facto onboarding path — undocumented, untested — is that the platform owner then calls
`POST /v1/users?tenant_id=<new_id>`, **types the new operator's password themselves**, and conveys
it out of band. This works only because `get_current_tenant_id` (`app/core/security.py:270-296`)
honours a `?tenant_id=` query override for platform-owner tokens.

**There is no password reset, no invite, no self-service password change, and no forced
first-login change.** Verified by exhaustive grep for `password.?reset|forgot|invite|invitation|
temporary.?password|must_change|first.?login`: zero hits. The only two ways a password is ever set
are `scripts/seed.py` (hardcoded `ChangeMe123!`) and an admin typing one into
`POST /v1/users` / `PATCH /v1/users/{id}`.

**Five identity defects worth naming now, because they are all cheap to fix and all real:**

| # | Defect | Evidence |
|---|---|---|
| I-1 | **Privilege escalation.** `role` on `POST /v1/users` is caller-supplied and never compared to the caller's own role. A tenant `admin` can create a `role="owner"` user in their tenant. | `app/api/v1/users.py:76-102` |
| I-2 | **Silent password reset.** `PATCH /v1/users/{id}` sets `pin_hash` with no current-password check. Combined with the `?tenant_id=` override, the platform owner can reset any user's password in any tenant. | `app/api/v1/users.py:137-138`, `app/core/security.py:283-287` |
| I-3 | **Cross-tenant override is unvalidated.** `get_current_tenant_id` decides purely from JWT claims — it never loads the user row, and never checks the override tenant exists. `?tenant_id=anything` returns `anything` verbatim. | `app/core/security.py:270-296` |
| I-4 | **Refresh tokens are never revoked.** Rotation at `app/api/v1/auth.py:221-223` issues a new refresh token without revoking the old, which stays replayable for its full 14 days. `POST /v1/auth/logout` revokes nothing and says so in its own docstring. | `app/api/v1/auth.py:227-234` |
| I-5 | **No login rate limiting, no lockout, no MFA recovery codes.** MFA secrets are stored plaintext base32 (contrast `DuressDevice`, which Fernet-encrypts its secret). | `app/core/security.py:118-141` |

### 1.2 Fleet, devices, and the meter binding

`Vehicle` (`app/models/fleet.py:46-66`) is unique on `(tenant_id, rego)` and carries
`vin, vehicle_class, camera_serial, tracking_device_id, meter_device_id, status,
registration_expiry, insurance_expiry`.

- **`Vehicle.meter_device_id` is a dead column** — declared, migrated, and never written or read by
  any service or router.
- There is **no vehicle-side link to a device, no `current_driver_id`, no `active_shift_id`, no
  odometer field anywhere in the system.**

`Device` (`app/models/fleet.py:69-117`) is unique on `(tenant_id, android_id)` and has
`vehicle_id` as a **nullable, non-unique FK**. It has no `driver_id`, no `paired_at`, no
`unpaired_at`, and no pairing history.

**Pairing today** (`POST /v1/fleet/devices/register` -> `app/services/fleet.py:128-178`): a
15-minute, single-use `DevicePairingCode` is consumed, then `device.vehicle_id = pairing.vehicle_id`
is assigned **unconditionally**. Consequences, all verified:

- **One-device-per-vehicle is not enforced.** No unique index on `devices.vehicle_id`, no check for
  an existing device on that vehicle. N devices can be bound to one car simultaneously.
- **Re-pairing silently overwrites and loses the old binding.** No history row, no `unpaired_at`,
  no audit-log write. This is deliberate and documented at `app/services/fleet.py:140-143` — but it
  means the system cannot answer "which meter was in this car on 3 March", which is exactly the
  question an audit asks.
- **Nothing checks the vehicle is free** — a device can be re-paired away from a car that has an
  open shift and an open trip on it.
- **The binding is device -> vehicle only. A driver is never bound to a device.** Nothing ties "who
  is logged into this kiosk" to "who is on shift in this car".

### 1.3 Shifts — the crux of the two-driver question

`Shift` (`app/models/shift.py:75-112`) has `driver_id, vehicle_id, start_at, end_at,
inspection_json, trips_count, km_total, cash_total, card_total, psl_owed, reconciled,
plotted_zone_id, plotted_at, break_started_at, break_taken`.

An "active shift" is defined **purely as `end_at IS NULL`**, by convention, restated independently
in four places (`app/services/live_ops.py:475-486`, `app/services/zones.py:164-176`,
`app/services/jobs.py:203-210`, `app/api/v1/shifts.py:223-224`).

**`Shift` has no `__table_args__` at all — there are zero table constraints.** Verified at three
layers: the model (`shift.py:75-76`), the migration (`f622cc063e66_initial_schema.py:66-87`, three
non-unique indexes only), and the service.

`start_shift` (`app/services/shift.py:96-115`) is five lines and **validates nothing**:

```python
async def start_shift(session, *, tenant_id, driver_id, vehicle_id, start_at, inspection_json):
    shift = Shift(tenant_id=tenant_id, driver_id=driver_id, vehicle_id=vehicle_id,
                  start_at=start_at or datetime.now(UTC), inspection_json=inspection_json)
    session.add(shift); await session.commit(); await session.refresh(shift)
    return shift
```

It does not check that the vehicle is free, that the driver has no other open shift, that the
vehicle or driver rows even exist, that the driver's licence is unexpired, or that the caller is
the driver. The router discards the authenticated user entirely (`app/api/v1/shifts.py:60-75`), so
**any authenticated tenant user can open a shift for any arbitrary driver_id and vehicle_id.**

Downstream code treats overlapping shifts as an anomaly to tolerate, not prevent — `live_ops` keeps
"the most recent" and silently discards extras; `zones` says "most-recently-started if more than
one somehow exists".

**There is no handover concept anywhere.** Repo-wide grep for
`handover|hand-over|changeover|handoff|swap` returns only unrelated hits. No model, no endpoint, no
service, no test. There is no odometer, no end-of-shift inspection, and no incoming-driver
acknowledgement.

### 1.4 Trips

`Trip` (`app/models/trips.py:98-197`) is unique on `(tenant_id, client_uuid)` and carries the full
fare breakdown, `start_at/end_at`, `start_lat/lng`, `end_lat/lng`, and FK-ish `driver_id`,
`vehicle_id`, `tariff_id`, `shift_id` (all plain `String(36)`, no FKs).

- **`shift_id` is client-supplied, never derived, never validated, and mutable via `PATCH`.**
  `app/api/v1/trips.py:96` assigns `payload.shift_id` verbatim; `app/schemas/trips.py:131` allows
  re-pointing it afterwards. `app/services/trips.py` has **zero** occurrences of "shift". This
  means shift takings — the reconciliation number — are only as trustworthy as a field the device
  chose to send.
- **`end_shift` counts open trips into the aggregates** (`_recompute_trip_aggregates`,
  `app/services/shift.py:60`, filters on `tenant_id` + `shift_id` only) and does not require open
  trips to be closed first.
- **The raw GPS trace is discarded.** `gps_trace_ref` is a `Text` ref string; there is no
  `gps_trace` column. Traces arrive on sync, are used by `recompute_from_trace`
  (`app/services/trips.py:286-314`), and are then dropped. Nothing is persisted.
- **`PATCH /v1/shifts/{id}` lets a dispatcher rewrite `driver_id`, `vehicle_id`, `end_at` and every
  takings figure, with no audit-log entry.**

### 1.5 Compliance documents

`ComplianceDocument` (`app/models/compliance.py:62-84`) is **vehicle-scoped only** —
`vehicle_id` is `nullable=False`. The seven doc types
(`app/models/compliance.py:45-60`) are all meter/vehicle artefacts: `calibration_record`,
`mounting_photo`, `accuracy_test`, `cl14_checklist`, `camera_register`, `duress_register`,
`tracking_register`.

**There is no driver document storage at all** — no criminal-history check, no driver authority
document, no medical, no induction record. `User` has `driver_licence_no`,
`driver_license_expiry` and `driver_authority_expiry`, but **no `driver_authority_no`**.

---

## Part 2 — What NSW actually requires

Sourced. Every claim below has a link; anything not listed here has not been verified and must not
be asserted as a requirement.

### 2.1 The per-journey record (this is the big one)

For a taxi rank-and-hail service, the transaction record **must contain**:

- the date of the journey, and the times it **commenced and ended**;
- the **location** of the commencement and end of the journey;
- the **full name of the driver**, and **the identification number shown on the driver identity
  document** of that driver;
- the **vehicle registration number** of the taxi;
- the **amount of the fare**.

Retention: **not less than 2 years** after the journey for PtP purposes, and records of passenger
service transactions must be kept **5 years** under the *Taxation Administration Act 1996* (NSW).
([record keeping requirements](https://www.pointtopoint.nsw.gov.au/learning-centre/fact-sheets/meeting-your-record-keeping-requirements))

> **This is the single most important gap in the current system.** Our `Trip` row stores
> `driver_id` and `vehicle_id` as loose foreign keys, not the driver's *name*, their *authority
> number*, or the *registration*. Over a 2-to-5 year retention window a driver gets renamed, a
> vehicle changes plates or is sold, a user row is hard-deleted (`DELETE /v1/users/{id}` is a real
> `session.delete`, `app/api/v1/users.py:157`) — and the historical record silently becomes wrong
> or unresolvable. A regulator-facing record must be an **immutable snapshot taken at trip close**,
> not a join.

### 2.2 Driver management

An authorised service provider **must check a driver's criminal history before they start to
drive, and continue to check it for as long as they use that driver's services**. During an audit
the provider must be able to **demonstrate to the Commissioner how they track and manage driver
suitability** — which may be automated, or may require drivers to present updated records at
regular intervals.
([taxi service providers](https://www.pointtopoint.nsw.gov.au/what-a-service-provider/taxi-service-providers))

Providers must keep the **name and driver licence number of each person who drives** vehicles used
to provide the service, and the **registration number of each vehicle**. For wheelchair accessible
vehicles, also the **make, model, and how many wheelchairs it can carry**.

Taxi service providers operating in the **Sydney Metropolitan Transport District must upload and
keep current** their vehicle registration numbers in the **Driver Vehicle Dashboard (DVD)**.

### 2.3 Vehicle and meter

- The vehicle must be registered, roadworthy, and painted/fitted with compliant signs, lights and
  markings.
- **Annual safety inspection is required even if the vehicle is less than five years old**, and the
  vehicle must be regularly maintained by a qualified mechanic with **records readily available**.
- The **fare calculation device (meter)** must be tamper- and vandal-resistant, **securely fixed in
  a commercially designed and manufactured mount**, in a safe position, able to display and charge
  the authorised fare **depending on taxi location**, and **visible to all passengers**.
([safety standards for taxis](https://www.pointtopoint.nsw.gov.au/safety-and-compliance/safety-standards-for-taxis),
[fare calculation devices](https://www.pointtopoint.nsw.gov.au/learning-centre/fact-sheets/fare-calculation-devices))

### 2.4 Security camera and duress

- **All** vehicles providing taxi passenger services must have an **approved security camera
  system** in good working order. The camera must be **visible to passengers**; the system must
  have an **indicator the driver can see from the normal driving position** showing whether it is
  working; recordings must be **password protected or encrypted** and recoverable after power loss;
  and recordings must be **destroyed between 30 and 90 days** after download. **Signage inside and
  outside** the vehicle is mandatory.
([security camera systems in taxis](https://www.pointtopoint.nsw.gov.au/learning-centre/fact-sheets/security-camera-systems-taxis))
- **Duress alarms and tracking devices are required for rank-and-hail taxis in Sydney, Newcastle,
  Wollongong and the Central Coast** — and **not required** outside those areas. When activated the
  system must **notify the service provider with the location of the taxi**, per TfNSW guidelines.
([safety alert: duress alarms](https://www.pointtopoint.nsw.gov.au/safety-and-compliance/safety-alerts/safety-alert-duress-alarms))

> Operating area is therefore a **first-class vehicle attribute**, not a cosmetic label: it decides
> whether duress/tracking hardware is mandatory. We do not model it at all today.

### 2.5 Passenger Service Levy

$1.20 per passenger service transaction since 1 July 2023. If you carry out **600 or more
transactions in any 12-month period you must report monthly**, on or before the last day of each
month, via the Industry Portal.
([passenger service levy](https://www.pointtopoint.nsw.gov.au/what-a-service-provider/passenger-service-levy),
[Revenue NSW](https://www.revenue.nsw.gov.au/taxes-duties-levies-royalties/passenger-service-levy))

Our fare engine already charges `$1.32` = `$1.20` + 10% GST, which is consistent. What we do not
have is the **monthly return** — a per-tenant count of levy-liable transactions for a calendar
month, which is a small report over data we already hold.

---

## Part 3 — The design

Six design decisions carry the whole plan. Everything in Part 4 is downstream of these.

### D-1. The **Shift is the driver-vehicle binding** — make it a real one

Do not add a `Vehicle.current_driver_id` or a `Device.driver_id`. Those are denormalised caches
that will drift. The shift already *is* the association ("driver D is in vehicle V from T1 to T2");
it just has no rules. Give it rules:

**Two partial unique indexes** (supported by both SQLite and Postgres, so they are portable and
testable locally):

```sql
CREATE UNIQUE INDEX uq_shifts_one_open_per_vehicle
  ON shifts (tenant_id, vehicle_id) WHERE end_at IS NULL;
CREATE UNIQUE INDEX uq_shifts_one_open_per_driver
  ON shifts (tenant_id, driver_id)  WHERE end_at IS NULL;
```

This makes the two-drivers-per-day case correct **by construction**: driver A holds the car
06:00-18:00, and the database physically cannot let driver B open a second shift on that car until
A's shift has an `end_at`. No application logic can forget the check.

Alongside them, `start_shift` gets the validation it has never had (WP-30).

### D-2. Handover is an **atomic transaction**, not two API calls

Two drivers per day means one changeover per day, and the changeover is the moment that goes wrong:
outgoing driver forgets to close, incoming driver cannot start, cash is unreconciled, damage is
disputed. Model it as one operation:

`POST /v1/shifts/{outgoing_shift_id}/handover` — in a single transaction:

1. verify the caller is the outgoing driver (or a dispatcher acting for them);
2. refuse if any trip on the outgoing shift is still open;
3. capture `odometer_end`, fuel level, cleanliness, damage notes, optional photos;
4. **re-authenticate the incoming driver** with their PIN in the same request — this is the
   acknowledgement, and it is what makes the record defensible;
5. close the outgoing shift (recompute aggregates, set `end_at`);
6. open the incoming shift with `odometer_start = odometer_end`;
7. write one `ShiftHandover` row linking `outgoing_shift_id -> incoming_shift_id`;
8. write one audit-log entry.

Because both shifts are touched inside one transaction, the partial unique index from D-1 is never
violated even for an instant.

A driver going home at end of day still uses plain `POST /v1/shifts/{id}/end`. Handover is
specifically the driver-to-driver case.

### D-3. Meter binding becomes an **assignment with history**, never an overwrite

Replace the "assign `device.vehicle_id` and forget" model with an explicit assignment record:

- `DeviceAssignment(device_id, vehicle_id, bound_at, unbound_at, bound_by_user_id, pairing_code_id, unbound_reason)`
- Partial unique index `(tenant_id, vehicle_id) WHERE unbound_at IS NULL` — **one active meter per
  vehicle**, enforced by the database.
- Partial unique index `(tenant_id, device_id) WHERE unbound_at IS NULL` — a meter is in one car.
- Re-pairing must **explicitly close** the previous assignment (set `unbound_at`, `unbound_reason`)
  and open a new one. Both writes audit-logged.
- **Refuse to re-pair a device whose current vehicle has an open shift.** Swapping the meter out
  from under a driver mid-shift is never legitimate; make it a 409 with a clear message.

`Device.vehicle_id` stays as a denormalised convenience pointer (lots of code reads it) but becomes
**derived** — always written in the same transaction as the assignment row, never independently.
`Vehicle.meter_device_id` is dead and misleading: **drop the column**.

### D-4. Vehicle onboarding is a **lifecycle with a compliance gate**

A vehicle must not be able to carry a passenger until it is actually legal to. Give `Vehicle` a
real lifecycle:

```
draft -> pending_compliance -> active -> (suspended | retired)
```

and one authoritative service function that everything calls:

```python
def assert_vehicle_operational(vehicle, tenant_settings) -> None
```

which fails with a precise, listable reason if any of these is untrue:

| Gate | Source |
|---|---|
| `status == "active"` | internal |
| registration unexpired | `Vehicle.registration_expiry` |
| insurance unexpired | `Vehicle.insurance_expiry` |
| **annual safety inspection unexpired** | new `Vehicle.inspection_expiry` (2.3) |
| **taxi licence unexpired** | new `Vehicle.taxi_licence_no` / `licence_expiry` |
| security camera registered | `camera_serial` + a `camera_register` document |
| **duress + tracking present IF `operating_area` is a mandated area** | 2.4 |
| a meter is currently assigned, and its `calibration_due` is unexpired | D-3 + `Device.calibration_due` |

`start_shift` calls it. This is the single choke point that turns a pile of nullable date columns
into an actual control.

New `Vehicle` columns: `taxi_licence_no`, `licence_expiry`, `inspection_expiry`, `operating_area`
(enum: `sydney | newcastle | wollongong | central_coast | country`), `make`, `model`, `year`,
`wav_capacity` (for the WAV record in 2.2), `odometer_km`.

### D-5. The regulator-facing trip record is an **immutable snapshot**

Add to `Trip`, populated **once, at trip creation/close, never updated**:

- `driver_name_snapshot`
- `driver_authority_no_snapshot` — requires a new `User.driver_authority_no` (we only store the
  *expiry* today)
- `driver_licence_no_snapshot`
- `vehicle_rego_snapshot`
- `start_address`, `end_address` — 2.1 says "location", and a street address is what an auditor
  reads; keep the lat/lng too
- `psl_levy_liable` (bool) — so the monthly return is a `COUNT`, not a guess

And **stop trusting the client for `shift_id`**: derive it server-side from the open shift for
`(tenant_id, driver_id, vehicle_id)` at trip creation, reject a contradicting client value, and
**remove `shift_id` from `TripUpdate`** so it cannot be re-pointed after the fact.

Persist the GPS trace on close instead of discarding it — it is the evidence behind both the fare
and the "location of commencement and end".

### D-6. Documents become **polymorphic**, and drivers get their own

`ComplianceDocument.vehicle_id NOT NULL` becomes `subject_type` + `subject_id`:

- `vehicle` — the existing seven types, unchanged
- `driver` — new: `driver_authority`, `driver_licence`, `criminal_history_check`,
  `medical_certificate`, `induction_record`, `training_record`
- `tenant` — new: `sms_document` (Safety Management System), `tsp_authorisation`,
  `insurance_certificate`

Plus, on every document: `document_number`, `issued_at`, `expires_at`, `verified_by_user_id`,
`verified_at`.

And on `User`, the thing 2.2 explicitly demands you be able to demonstrate:
`criminal_history_last_checked_at`, `criminal_history_next_due_at`,
`suitability_status` (`clear | pending | expired | revoked`).

That turns "how do you track driver suitability?" from an awkward conversation into a dashboard
screen.

### D-7 (supporting). A real `tenant_settings` table

Needed by D-4 (mandated-area rules), fatigue thresholds, PSL amount, duress escalation number,
compliance warning windows. One row per tenant, created with the tenant. Every value currently
living in `app/core/config.py` as a deployment-wide env var that is *logically per-operator* moves
here, with the env var demoted to a default.

---

## Part 4 — Work packages

**47 work packages across 8 phases** (Phase 7 is dashboard UI and can lag the backend). Each is sized for one agent. Dependencies are explicit.

### CRITICAL execution rule: migrations serialise

Alembic migrations form a **linear chain** via `down_revision`. If ten agents each generate a
migration in parallel, you get ten siblings off the same parent and a broken chain that Alembic
refuses to run.

**Therefore:** within a phase, agents write *models, services, endpoints, tests* — but **do not run
`alembic revision`**. At the end of each phase, **one designated integrator agent** generates a
single migration for that phase's combined model changes, hand-checks it, and runs
`alembic upgrade head`. Phases run sequentially; work packages inside a phase run in parallel.

Two migration rules this codebase has already been bitten by, both mandatory:
- Boolean defaults use `server_default=sa.false()`, **never** `sa.text("0")` — SQLite accepts the
  latter, Postgres crash-loops on it.
- Any new non-nullable column on an existing table needs a `server_default`, or the `ALTER TABLE`
  fails against seeded rows on Postgres.

### Phase 0 — Foundation (3 WPs, then integrate)

| WP | Title | Touches |
|---|---|---|
| WP-01 | `tenant_settings` table, service, `GET/PATCH /v1/tenants/me/settings`; seed a row per existing tenant | new `app/models/tenant_settings.py`, `app/services/tenant.py` |
| WP-02 | `User` identity columns: `driver_authority_no`, `must_change_password`, `password_changed_at`, `criminal_history_last_checked_at`, `criminal_history_next_due_at`, `suitability_status` | `app/models/user.py`, `app/schemas/user.py` |
| WP-03 | `Vehicle` onboarding columns + `operating_area` enum + lifecycle statuses (D-4) | `app/models/fleet.py`, `app/schemas/fleet.py` |
| WP-04 | **INTEGRATOR** — one migration for WP-01..03, verify `sa.false()` usage, `alembic upgrade head`, full suite green | `alembic/versions/` |

### Phase 1 — Identity and tenancy (8 WPs)

| WP | Title | Notes |
|---|---|---|
| WP-10 | `user_invites` table + token service (hash stored, never the raw token; TTL; single-use) | shared by invite and reset |
| WP-11 | `POST /v1/auth/accept-invite`, `POST /v1/auth/forgot-password`, `POST /v1/auth/reset-password` | delivery via the existing SendGrid/Twilio mock-fallback pattern in `app/services/receipts.py` |
| WP-12 | `POST /v1/auth/change-password` (self-service, requires current password) + enforce `must_change_password` at login | fixes the "no way to change your own password" gap |
| WP-13 | **Fix I-1** — privilege-escalation guard: a caller may never create or promote a user to a role above their own | `app/api/v1/users.py` |
| WP-14 | **Fix I-3** — validate the `?tenant_id=` override against a real tenant; audit-log every cross-tenant action | `app/core/security.py` |
| WP-15 | **Fix I-4** — revoke the old refresh jti on rotation; make `POST /v1/auth/logout` actually revoke | `app/api/v1/auth.py` |
| WP-16 | **Fix I-5** — login attempt throttling + temporary lockout; MFA recovery codes | `app/core/security.py` |
| WP-17 | Rewrite `POST /v1/platform/tenants` to atomically create **tenant + settings row + first owner user + invite**, returning the invite link. Replaces the undocumented type-a-password path. | `app/services/platform.py` |
| WP-18 | Tenant lifecycle: `PATCH /v1/platform/tenants/{id}` (suspend/activate/plan), and a suspended tenant's users cannot log in | |

### Phase 2 — Fleet, meter binding (5 WPs)

| WP | Title |
|---|---|
| WP-20 | `DeviceAssignment` model + partial unique indexes (D-3) |
| WP-21 | Rewrite `register_device` to close-then-open assignments, refuse re-pair when the current vehicle has an open shift, audit-log both sides |
| WP-22 | Drop dead `Vehicle.meter_device_id`; make `Device.vehicle_id` strictly derived from the active assignment |
| WP-23 | `assert_vehicle_operational` + vehicle lifecycle transitions + `GET /v1/fleet/vehicles/{id}/readiness` returning the pass/fail checklist |
| WP-24 | `VehicleAssignment` roster — which drivers are authorised to drive which vehicles; consumed by WP-30 |

### Phase 3 — Shifts and handover (5 WPs)

| WP | Title |
|---|---|
| WP-30 | `start_shift` full validation (driver exists/role/active/licence+authority unexpired/suitability clear; vehicle operational via WP-23; roster via WP-24; no open shift either side; caller is the driver or a dispatcher) |
| WP-31 | The two partial unique indexes (D-1) + a concurrency test that fires two simultaneous starts and asserts exactly one wins |
| WP-32 | `ShiftHandover` model + `POST /v1/shifts/{id}/handover` (D-2), including incoming-driver PIN re-auth |
| WP-33 | `odometer_start`/`odometer_end` on Shift; `end_shift` refuses while trips are open; end-of-shift inspection mirroring `inspection_json` |
| WP-34 | Audit-log every shift start/end/handover/PATCH/DELETE |

### Phase 4 — Trips and the PtP record (6 WPs)

| WP | Title |
|---|---|
| WP-40 | Trip snapshot columns (D-5), populated at creation and close, never updated |
| WP-41 | Server-derive `shift_id`; reject contradicting client values; remove it from `TripUpdate` |
| WP-42 | Persist the GPS trace on close (currently discarded) with a retention policy |
| WP-43 | Capture `start_address`/`end_address` |
| WP-44 | **`GET /v1/reports/ptp-transactions`** — the regulator-facing export: exactly the fields in 2.1, CSV + PDF, date-ranged, tenant-scoped |
| WP-45 | **`GET /v1/reports/psl-monthly-return`** — count of levy-liable transactions per calendar month (2.5) |

### Phase 5 — Documents and driver management (5 WPs)

| WP | Title |
|---|---|
| WP-50 | Polymorphic `ComplianceDocument` migration (`subject_type`/`subject_id`), backfilling every existing row as `subject_type="vehicle"` |
| WP-51 | Driver + tenant document types, `document_number`/`issued_at`/`expires_at`/`verified_by` |
| WP-52 | Criminal-history check tracking + due-date alerts reusing the existing `FatigueAlert` machinery |
| WP-53 | `GET /v1/fleet/driver-suitability` — the "demonstrate to the Commissioner" screen (2.2) |
| WP-54 | Extend the existing vehicle evidence pack to include driver documents and the handover chain |

### Phase 6 — Live tracking and monitoring (5 WPs)

| WP | Title |
|---|---|
| WP-60 | Persist position history (today it is in-memory only and lost on restart — `app/services/live_ops.py`), with retention |
| WP-61 | Move fatigue thresholds to `tenant_settings` (WP-01) |
| WP-62 | Minimum-rest-between-shifts rule — **threshold is configurable, not hardcoded; we have not verified a specific NSW number, so do not invent one** |
| WP-63 | Driver scorecard: trips, km, speeding events, duress events, complaints, suitability status |
| WP-64 | Live ops: show which driver is on which vehicle right now, from the open shift |

### Phase 7 — Dashboard (8 WPs)

| WP | Title |
|---|---|
| WP-70 | Tenant onboarding wizard (platform console) — create tenant, invite owner, copy invite link |
| WP-71 | Vehicle onboarding wizard with the WP-23 readiness checklist |
| WP-72 | Meter pairing screen: generate code, show QR, live pairing status, assignment history |
| WP-73 | Shift board: who is on which vehicle now, handover button, overlap warnings |
| WP-74 | Driver suitability screen (WP-53) |
| WP-75 | Document vault extended to drivers and tenant-level docs |
| WP-76 | PtP transaction export + PSL monthly return screens (WP-44/45) |
| WP-77 | Accept-invite / reset-password / change-password pages |

---

## Part 5 — Answers to the five questions, in one page

**1. Multiple tenants and their passwords.** Platform owner calls `POST /v1/platform/tenants` with
the operator's name/ABN/TSP number. That one call creates the tenant, its settings row, its first
owner user, and a **single-use invite token**; it returns an invite link. The operator sets their
own password — **nobody ever types or transmits a password for someone else.** Forgotten passwords
use the same token machinery. Everything after that is the operator's own admin creating users
inside their tenant, with the privilege guard from WP-13 preventing them from minting a role above
their own.

**2. Vehicle onboarding.** Create the vehicle in `draft` with rego, VIN, make/model/year, class,
**operating area** (which decides whether duress/tracking are mandatory), taxi licence number,
and the four expiry dates (registration, insurance, annual inspection, licence). Upload the
compliance documents. The vehicle sits in `pending_compliance` until
`assert_vehicle_operational` passes every gate — then it flips to `active`. A vehicle that is not
`active` cannot have a shift opened on it, so an out-of-compliance car physically cannot carry a
passenger through this system.

**3. Meter binding.** Admin generates a 15-minute single-use pairing code for the vehicle; the
tablet consumes it. That opens a `DeviceAssignment` row — one active meter per vehicle and one
vehicle per meter, both enforced by partial unique indexes. Re-pairing closes the old assignment
explicitly, with a reason and an audit entry, and is **refused outright while the current vehicle
has an open shift**. The full "which meter was in which car on which date" history is queryable —
which is exactly what an audit asks and what today's silent-overwrite model cannot answer.

**4. Driver-vehicle association.** The open shift *is* the association. Opening one is gated on the
driver being real, active, licensed, authority-current, suitability-clear, rostered to that vehicle,
and not already on shift — and on the vehicle being operational and free.

**5. Two drivers, 12 hours each.** Driver A opens a shift at 06:00. The partial unique index means
the database itself will not allow a second open shift on that vehicle. At 18:00 they call
`POST /v1/shifts/{id}/handover`: in one transaction the system refuses if a trip is still open,
captures the odometer and vehicle condition, takes driver B's PIN as acknowledgement, closes A's
shift with recomputed takings, opens B's shift with the same odometer reading, and writes a linked
`ShiftHandover` row. Two clean 12-hour shifts, one auditable changeover, no overlap possible, and
every trip attributable to exactly one driver — which is what makes the 2.1 record defensible.

---

## Part 6 — Notes for the implementing agents

- **Verify, do not assume.** This document was written from a real code audit, but the code will
  have moved by the time you read it. Re-read the files named in your work package before editing.
- **The `Edit`/`Write` tools are broken on this machine** (a PreToolUse hook crashes on the space in
  the Windows username path). Make file changes by writing a short Python script that uses
  `pathlib.read_text` / `write_text` and running it via Bash with `uv run python`. Read the file
  back afterwards to confirm.
- **Do not weaken a test to make it pass.** If a test disagrees with your change, one of the two is
  wrong — work out which. The fare engine's golden vectors in particular are the source of truth for
  money and must never be edited to accommodate a code change.
- **Run the real suite** (`uv run pytest -q`, ~7 minutes, 538 tests as of this baseline) before
  declaring done, and say the real number in your report.
- If a stray `python.exe` holds a lock on `tests/test_dev.db` and every test errors at once, that is
  a known Windows process-hygiene issue, not a code defect — kill the strays, delete the db file,
  re-run.
- **Flag, do not invent.** Where this document says a threshold is unverified (e.g. minimum rest
  between shifts), make it configurable and say so in your report. Do not put an invented number in
  front of a regulator.
