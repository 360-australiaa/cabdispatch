# Pairing-code UX spec for Android (2026-08-28) -- backend/architecture agent

Addressed to the Android session. This is a spec, not a status note -- no reply strictly needed,
but questions are marked below if you want to answer them before building.

## Why this exists

Per your own finding (2026-08-28 status report): `ApiService.registerDevice` has zero real call
sites, `SessionHolder.deviceId` is always null, and device heartbeat is a structural no-op today.
Real device pairing (this tablet's meter <-> a specific vehicle, persisted server-side) has never
been exercised live. I am rewriting `register_device` server-side (Phase 2 of the architecture
plan, D-3 -- an assignment WITH HISTORY, not an overwrite) in parallel with drafting this. This
spec describes the target contract for a real "Pair Meter" screen so device pairing is finally a
real, working thing on both sides.

## The server contract (current + what is changing)

**Admin/dispatcher side (dashboard, not your concern, background only):** an admin generates a
short-lived code for a specific vehicle:
`POST /v1/fleet/vehicles/{vehicle_id}/pairing-code` -> `{code, vehicle_id, tenant_id, expires_at}`.
Code shape: 8 characters, uppercase letters + digits, deliberately excluding `0`/`1`/`O`/`I` to
avoid transcription ambiguity when a human reads it aloud or types it. TTL: 15 minutes from
generation.

**Device side (this is what you build against):**
`POST /v1/fleet/devices/register`, body `{android_id, pairing_code, model?, app_version?}`,
requires a normal authenticated bearer token (whoever is logged into the app doing the pairing --
this is NOT a separate device-credential scheme, it rides on the existing user session). Response
is a `Device` object: `{id, tenant_id, android_id, model, app_version, vehicle_id, kiosk_locked,
last_seen_at, battery, network, calibration_due, ...}`.

**What is changing under the hood (server-side, transparent to you except one new error case):**
re-pairing now closes the previous assignment and opens a new one (audit-logged both sides)
instead of silently overwriting a column. One NEW client-visible behaviour: **re-pairing a device
whose CURRENT vehicle has an open shift now returns 409**, with a clear message, instead of
silently letting the meter get pulled out from under a driver mid-shift. Handle this as a real,
expected error case, not a bug.

## What to build: a real "Pair Meter" screen

1. **Manual code entry** (primary path, works today with zero new backend dependency): a 8-character
   text field, auto-uppercase, ideally filtering out `0/1/O/I` as the user types (matches the
   server alphabet, catches typos before they hit the network) or at least normalizing case.
   Submit button calls `POST /v1/fleet/devices/register` with `android_id` = this device's stable
   identifier (whatever this app already uses elsewhere as its device id -- reuse it, do not
   invent a second one), `pairing_code` = the entered code, `model` = `Build.MODEL`,
   `app_version` = your existing `BuildConfig.VERSION_NAME` equivalent.

2. **QR scan** (secondary/faster path): reuse `RealQrScanner.scan()` (the ML Kit scanner from your
   #7 fix) with a **new, separate result handler** -- this is a different semantic target than the
   existing vehicle-bind scanner (that one decodes a rego string; this one decodes a pairing code
   string and goes straight into the same submit path as manual entry above). Do not repoint the
   existing vehicle-bind scan call; add a second entry point.
   - **Open question for you:** does the dashboard currently render the pairing code as an actual
     scannable QR image anywhere, or only as plain text (`PairingCodeRead.code`)? If it is
     plain-text-only today, the QR path is not usable yet from the admin side regardless of what
     you build -- worth a quick check before investing in the QR half; manual entry alone is a
     complete, working v1.

3. **Response handling:**
   - Success (200/201): persist the returned `device.id` as `SessionHolder.deviceId` -- **this
     needs to survive process death**, not stay a plain in-memory `var` (your own TODO already
     flags this as the reason heartbeat is a structural no-op) -- back it with SharedPreferences/
     DataStore or wherever this app already persists small durable session state. Show success +
     the paired vehicle's rego/id for driver confirmation. Heartbeat (already wired at
     `SettingsViewModel.kt:161`) should start firing for real the moment `deviceId` is non-null --
     no other change needed there per your own report.
   - Invalid/expired code (404/410-equivalent -- check the exact status the rewritten endpoint
     returns once it lands, may differ slightly from today): clear inline error, allow retry, do
     not clear the entered code so the user can just fix a typo.
   - **NEW: vehicle has an open shift (409):** show the server's message text directly (e.g.
     "Cannot pair: this vehicle currently has an open shift. Ask the current driver to end their
     shift first.") -- do not retry automatically, do not silently fall through to anything.
   - Network/5xx: standard retry affordance, nothing pairing-specific.

4. **Where this screen lives in the nav** is a product decision, not mine to dictate -- Settings
   seems the natural home given `SettingsViewModel.kt` already owns heartbeat, but flagging as an
   open question rather than prescribing.

## Explicitly not in scope for you

- BLE duress-device pairing -- unrelated, already correctly deferred per your report.
- The vehicle-bind rego scanner -- leave completely alone.
- Anything server-side -- this file describes the contract, not an implementation request on your
  end beyond the screen itself.

-- Backend/architecture agent