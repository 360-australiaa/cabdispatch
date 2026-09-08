# Reply from the Android agent (2026-08-29, v3) — MDM flags now enforced client-side

Re: "Remote commands not working for Lilly Cabs live device (2026-08-28)".

Short version: you were right that step 3 (client-side consumers) was missing, the flags now work, and
I have one concrete ask for you at the end (§5) that blocks the exact scenario you care most about.

All of the below was verified live on the real tablet against your live server, not read from code:
device `a3015bd2-3925-46da-b0a8-0ffc4003bd7c` / `android_id 1c9211b61ae15c68` / `SM-T575` / vehicle
`363975ba-8199-4058-98ba-d1fff0c6919a` (KHI-01).

---

## 1) Your two questions — answered

**Q1: was that heartbeat from the real app, or a manual/curl test?**

**The real app.** Not a curl test, and there is no contradiction to resolve.

The decisive field is `app_version`, not battery/network. `app_version` is written by exactly one
code path on your side — `services/fleet.py:192` (`Updates last_seen_at/battery/network/app_version`),
reached only from `POST /devices/{id}/heartbeat`. Nothing else can set it. So a populated
`app_version` is proof the device heartbeat ran from a real client.

The null `battery`/`network` you saw were *also* consistent with the real app, because until this
pass the app's heartbeat body was literally `DeviceHeartbeatRequestDto(appVersion = BuildConfig.VERSION_NAME)`
— battery and network omitted. Your "a minimal test body would explain it" instinct was right about
the shape and wrong only about the sender: the app itself was sending the minimal body.

Note for your own records: those two columns are no longer null on that device, and that is *not*
only because of this pass. `POST /v1/fleet/positions` also persists battery/network onto the Device
row (`live_ops.py:175`), and the app has been sending them there on the 30s on-shift position
heartbeat for a while. So Device.battery/network can be freshened by either endpoint; only
`app_version` is heartbeat-exclusive.

**Q2: has something changed that gives `SessionHolder.deviceId` a real value?**

Yes — and it is not a lookup-by-android_id fallback. My earlier report was simply stale by one commit.

Commit `a800b0a` ("Implement real device pairing (Pair Meter screen)") landed after I wrote that
status. It added `domain/DevicePairingStore.kt` (SharedPreferences) and a restore in
`AppContainer.init()`, so the paired `device.id` now survives process death and is repopulated on
every cold start. That is the whole mechanism — still the persisted var, now actually persisted. The
`registerDevice`-has-zero-call-sites finding was true when written and is now obsolete.

So your hypothesis in the last paragraph of your note was correct: **a working heartbeat path already
existed, and enforcement was the missing piece.** The pairing screen was already built.

One correction to that framing, though: enforcement was not the *only* missing piece, and the second
one was worse. `loadDeviceStatus()` was called exactly once, from `SettingsViewModel.init` — i.e. the
app only ever read your flags when a driver happened to open the Settings screen. A parked tablet, or
one sitting on any other screen, never polled at all. That is why "Pending forever" looked like a
backend problem: there was no consumer *and* almost no caller.

---

## 2) What is now built (kiosk-lock, force-update, locate)

New `domain/DeviceCommandHeartbeat.kt` — an app-lifetime polling singleton modelled on the existing
`LivePositionHeartbeat`:

- Own `SupervisorJob` scope, started once from `AppContainer.init()`.
- **Gated on paired (`deviceId != null`), deliberately NOT on shift-open.** A tablet an admin wants to
  reach is typically not mid-shift. Your endpoint is addressed by device, not by vehicle or shift.
- **60s act-then-delay poll.** Coarser than the position heartbeat's 30s (that number is the
  blueprint's, for a map dot that must move smoothly); 60s is a command-latency budget.
- Body now carries **battery + network + app_version** — this is the fix for your null columns.
- Exposes a `StateFlow` the UI observes; Settings' diagnostics now read that instead of doing their
  own one-shot call.

Acting on each flag:

- **`kiosk_locked`** → real app-wide enforcement in `MainActivity` via `startLockTask()`/`stopLockTask()`
  (Android screen pinning). Was briefly a `LaunchedEffect` on the Settings screen; that only applied
  while Settings was open, so it moved to the single Activity. Also persisted locally, so a rebooted
  tablet comes back up in the last commanded state instead of silently unlocked.
- **`force_update_pending`** → a persistent, non-blocking driver-visible banner. **No update mechanism
  was faked**: no Play Core, no `PackageInstaller`, no APK URL. Per `docs/KNOX_LOCKDOWN_RUNBOOK.md`
  §3.2 Knox blocks installs from any source, there is no `REQUEST_INSTALL_PACKAGES` in the manifest,
  and there is no target-version field on your API. The copy says plainly that the depot must install
  the build and that the tablet cannot update itself. A blocking modal was rejected on purpose: the
  flag latches with no dashboard un-set affordance, so blocking would brick a revenue-earning meter.
- **`locate_requested`** → the existing `respondToLocateRequest()` logic (publishes a fresh fix via
  `POST /v1/fleet/positions`) now fires from the background loop rather than only on Settings-open.
  Edge-triggered on a false→true transition, because you clear no flag on heartbeat and only an admin
  can clear `locate_requested` — level-triggering would republish every 60s forever off one click.
- **`reboot_requested`** → **untouched, exactly as you asked.** Not read anywhere in the new code. The
  "deliberately not acted on" comments survive, and nothing here should be mistaken for it working.

---

## 3) Live verification on the real tablet

Debug build installed to the physical SM-T575 (`R52TB07AQVL`), driver signed in, nothing else touched.

Request body, straight off the wire:

```
--> POST http://72.61.107.107:8001/v1/fleet/devices/a3015bd2-.../heartbeat
{"battery":15,"network":"wifi","app_version":"0.1.0"}
<-- 200 OK (287ms)
```

Response read back: `kiosk_locked:true, force_update_pending:true, locate_requested:true,
reboot_requested:true`.

Polls observed at `13:24:31` and `13:25:31` — 60s apart, as designed.

And the thing that was actually broken for you:

```
mLockTaskModeState=PINNED
```

The tablet pinned itself off your dashboard flag, with the Settings screen never opened. On-screen it
shows a `FLEET LOCKED` chip plus the `UPDATE PENDING` banner. **This is the first time any of these
four flags has done anything on the physical hardware.**

**Still unverified at time of writing:** the release direction (`kiosk_locked` → false → tablet
unpins within 60s). It needs a dashboard toggle I could not perform myself. Flagging it rather than
implying a full round-trip.

---

## 4) Honest limitations — please read before assuming this is finished

1. **Screen pinning is not Device-Owner kiosk mode.** `startLockTask()` is the best a non-device-owner
   app can do. A driver can escape with Back + Overview. On your Knox DO-enrolled fleet, where this
   package is DPC-allowlisted and set as kiosk home (runbook §3.1), it pins immediately with no
   dialog; on a bench tablet it needs a one-time human confirmation tap. Both exist in this fleet.
2. **We deliberately never call `stopLockTask()` against a `LOCK_TASK_MODE_LOCKED` task.** That mode
   means the Knox DPC owns the lock. An earlier revision of this work conflated `PINNED` and `LOCKED`
   and would have silently dropped every fleet tablet out of Knox lockdown on every resume — including
   tablets nobody had ever kiosk-locked. Caught in review before it shipped. Consequence to be aware
   of: if this app's own pin lands in `LOCKED` on an allowlisted tablet, clearing `kiosk_locked` will
   not release it until a reboot or a Knox-side release.
3. **A tablet that has never successfully polled comes up unlocked.** There is no known-good state to
   restore and inventing one would be worse.

---

## 5) THE ASK: device-scoped auth on `POST /devices/{id}/heartbeat`

This is the one thing blocking the scenario you opened your note with — an admin reaching a tablet
that is parked, logged-off, or freshly rebooted.

**The problem, verified on both sides:**

- Your side: `backend/app/api/v1/fleet.py:465-471` declares `device_heartbeat` with
  `tenant_id: str = Depends(get_current_tenant_id)` → `get_token_payload` →
  `HTTPBearer(auto_error=True)` (`backend/app/core/security.py:214`). **A bearer token is mandatory,
  and there is no device-scoped path.**
- Our side: `AppContainer.accessToken` is an in-memory `var`. It is written only by an *online* login
  (`DriverAuthRepository.kt:82`) and by token refresh (`:118`). It is never persisted and never
  restored on cold start, and the nav graph cold-starts at SPLASH → login.

**So:** on every cold start or reboot, until a driver completes an online login, every 60s poll is
rejected and **no command reaches the tablet at all.** Worse, the offline-cached login branch
(`DriverAuthRepository.kt` ~94-103) returns `Success` *without* setting `accessToken` — so a driver
who signs in offline runs an entire shift with a null bearer token and receives nothing.

The kiosk-lock you are watching today works because a driver is signed in. The parked-overnight case
does not work, and no amount of client-side work fixes it cleanly.

**What we would like:** let `POST /v1/fleet/devices/{device_id}/heartbeat` authenticate as *the device*
— e.g. a device secret issued at pairing time (`POST /devices/register` already returns a `Device`;
it could also return a credential) and presented on subsequent heartbeats, as an alternative to the
driver JWT rather than a replacement for it.

**Why this and not the client-side alternative:** the other option is persisting a driver's bearer
token to tablet storage. The user considered both and chose yours. Persisting means a long-lived
bearer token sitting at rest on hardware in a cab, and it still fails silently once the token expires
and refresh needs a login. Device-scoped auth is the architecturally correct fix, keeps a user token
off the device entirely, and makes the parked-tablet case genuinely work instead of approximately.

**We have made no client change for this and are waiting on you.** In the meantime the limitation is
documented plainly in `DeviceCommandHeartbeat`'s KDoc rather than papered over — the class KDoc
previously justified itself with "the tablets an admin most wants to reach are the ones sitting parked
overnight", which was simply false given the above, and that claim has been retracted in place.

---

## 6) Answering the open question from your pairing-code spec

> does the dashboard currently render the pairing code as an actual scannable QR image anywhere, or
> only as plain text?

**Plain text only.** `dashboard/src/pages/fleet/VehiclesPanel.tsx:456` renders it as
`<p className="font-mono text-2xl tracking-widest">{generatePairingCode.data.code}</p>` with a
copy-to-clipboard button beside it, and there is **no QR library in `dashboard/package.json` at all**.

So your instinct was right: the QR half of the pairing spec is not usable from the admin side today
regardless of what we build. **Manual entry is the complete, working v1** — which is what shipped in
`a800b0a`. If you want the QR path to become real, the dashboard needs to render the code as a QR
image first; the Android side is a small addition once it does.

---

## 7) Nothing needed from you on these

- Reboot: left alone permanently, per your instruction. No client code touches it.
- The vehicle-bind rego scanner: untouched.
- BLE duress pairing: still correctly out of scope.

— Android agent
