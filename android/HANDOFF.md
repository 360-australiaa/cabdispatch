# Android meter — finish-it checklist (read this first)


## 2026-08-27 (newest) -- Duress cabin-camera snapshot gallery: backend + dashboard DONE, Android capture NOT started

New feature, explicitly scoped by the owner: cabin-camera visibility on the dashboard during a
duress incident. Scope decision already made (owner's own call, do not re-litigate it): frames
ONLY while a duress event is open (same governing principle as
docs/DURESS_DEVICE_INTEGRATION.md's "camera/mic active ONLY during an active duress event, never a
standby listen mode") -- NOT continuous/always-on monitoring -- and periodic JPEG STILL FRAMES
(every ~2-5s), NOT true WebRTC video streaming. That second choice means zero new streaming
infrastructure (no signaling server, no STUN/TURN) -- it reuses the exact same local-disk-upload
convention already proven for duress audio.

### Backend + dashboard: done, tested, verified live (this pass)

- `app/models/duress_snapshot.py` -- new `DuressSnapshot` table (own table, not a single column on
  `DuressEvent`, because an incident accumulates MANY frames and the Duress Desk needs to browse
  them, not just see the latest one).
- `app/services/duress.py` -- `save_duress_snapshot` / `duress_snapshot_dir` (own file per upload,
  unlike audio's one-file-overwritten convention).
- `app/api/v1/duress.py` -- new routes, same role policy as audio (any authenticated tenant user):
    - `POST /v1/duress/{event_id}/snapshot` -- multipart `file` field, optional `?captured_at=`
      query param (ISO 8601; defaults to server receipt time). Broadcasts a
      `{"kind": "snapshot", "event_id", "snapshot_id", "captured_at"}` notification over the SAME
      `WS /v1/duress/{event_id}/live` feed the GPS relay already uses (GPS points never carry a
      `kind` key, so a listener can tell them apart on one socket).
    - `GET /v1/duress/{event_id}/snapshots` -- list, newest first.
    - `GET /v1/duress/{event_id}/snapshot/latest` -- streams the JPEG bytes of the most recent frame.
    - `GET /v1/duress/{event_id}/snapshot/{snapshot_id}` -- streams one specific frame.
- Migration `0201776e9446_duress_camera_snapshots.py` -- applied and verified.
- Tests: `tests/test_duress.py` -- 9 new tests (upload/list/latest/specific-by-id/empty-file-400/
  unknown-event-404/tenant-isolation/explicit-captured_at/the WS broadcast itself via a real
  `TestClient` websocket connection, same pattern as the existing GPS-broadcast test). Full suite:
  516+ passed, 0 failed.
- **Independently live-verified beyond the test suite** (per this project's standing "verify
  honestly" rule): ran a real local `uvicorn` instance against a fresh SQLite DB, seeded it, logged
  in over real HTTP, triggered a real duress event, uploaded a real (fake-bytes) JPEG via `curl`
  multipart, confirmed the row appeared in the list endpoint, confirmed `GET .../snapshot/latest`
  streamed back byte-identical content with `content-type: image/jpeg`, confirmed fetch-by-id also
  worked. Not just TestClient -- a real running server, real bytes over the wire.
- Dashboard: `src/pages/duress/CameraSnapshotPanel.tsx` (new) -- renders the latest frame as an
  `<img>` (fetched via `apiClient` as a blob + object URL, since `<img src>` can't carry the
  bearer-auth header the endpoint requires), wired into `EventDetailPanel.tsx` right below the
  live-GPS panel. Auto-refreshes off the `kind: "snapshot"` websocket notification
  (`useDuressLiveGps.ts` was extended to route that notification kind separately from GPS points,
  returned as `latestSnapshot`) for dispatch-role viewers who hold that socket open; other
  authenticated roles (who can still read the endpoint, matching audio's role policy) get a 4s
  polling fallback instead, since they don't have the websocket permission. `npm run build` clean
  (`tsc -b && vite build`), `npm run lint` (`tsc --noEmit`) clean.

### Android: NOT STARTED -- this is the actual next Ryzen task on this feature

Nothing has been written on the Android side yet. The prompt below is ready to paste into the
Ryzen session once it is free (finish the Fable Figma redesign work first, or run this in parallel
if you'd rather -- they touch different files: this is `domain/duress/*` + `data/remote/*`, the
Fable work is screens under `ui/screens/*`).

---

**PROMPT FOR RYZEN (paste as-is):**

Add cabin-camera still-frame capture to the duress flow, mirroring the existing duress-audio
capture exactly (same file shapes, same graceful-degradation permission pattern, same
wiring point in `DuressController`). Read these three files FIRST, in this order, before writing
anything -- they are the pattern to copy, not just references:

1. `domain/duress/DuressAudioRecorder.kt` -- the class shape to mirror (permission check that
   returns `false` on denial rather than throwing, `withContext(Dispatchers.IO)`, a
   `start(eventId)` / `stop(): File?` pair, a `companion object` constant for its cadence).
2. `domain/DuressRepository.kt` -- see `uploadAudio` for the exact multipart-upload shape to copy
   for the new `uploadSnapshot` method.
3. `domain/DuressController.kt` -- see how `audioRecorder`/`runActivePhase` starts the recorder the
   moment `DuressUiState.Active` is reached with a real event id, and stops it on the 60s cap or a
   terminal status, then calls `uploadAudio` -- this is where the new capture loop plugs in.

**What to build:**

1. `domain/duress/DuressCameraCapture.kt` -- new class, same shape as `DuressAudioRecorder`:
   - Uses CameraX (`androidx.camera.core.ImageCapture` + `ProcessCameraProvider`) against the
     FRONT (cabin-facing) camera -- this is capturing the driver's cabin, not the road ahead.
     `implementation("androidx.camera:camera-camera2:...")` /
     `androidx.camera:camera-lifecycle` / `androidx.camera:camera-core` will need adding to
     `app/build.gradle.kts` if not already present -- check first.
   - `start(eventId)`: checks `Manifest.permission.CAMERA` via `ContextCompat.checkSelfPermission`
     -- returns `false` (silent no-op, exactly like `DuressAudioRecorder.start`) if not granted.
     Binds a headless `ImageCapture` use case (no `PreviewView` needed -- this never shows a
     viewfinder to the driver, it just captures in the background) to a `ProcessLifecycleOwner`
     or an app-scoped lifecycle, then starts a repeating capture loop (`SNAPSHOT_INTERVAL_MS`,
     suggest 3000L to land in the middle of the already-agreed 2-5s range) that calls
     `imageCapture.takePicture(...)` into a JPEG byte array (use the `OutputFileOptions`-free
     in-memory `ImageCapture.OnImageCapturedCallback` + `ImageProxy` -> `ByteArray` path, not a
     file-based capture -- these frames get uploaded immediately, not kept on disk).
   - `stop()`: cancels the repeating loop and unbinds the camera use case. No file cleanup needed
     (nothing was written to disk) -- this is the one place this class differs from
     `DuressAudioRecorder`, which does own a file.
   - Expose captured frames however fits the coroutine style already in this codebase -- a
     `Flow<ByteArray>` the controller collects, or a callback passed into `start(eventId, onFrame)`
     -- match whichever pattern reads more naturally against `DuressController`'s existing
     coroutine scope, your call.

2. `data/remote/DuressGpsPointDto.kt`-adjacent DTOs / `ApiService.kt` -- add the multipart endpoint:
   `POST /v1/duress/{eventId}/snapshot` (optional `?captured_at=` query param, ISO 8601 -- send it
   if you have a real capture timestamp, omit it and let the server default if not). Response body
   is `{id, event_id, captured_at, created_at}` -- doesn't need to be consumed for anything, the
   upload firing is what matters.

3. `domain/DuressRepository.kt` -- add
   `suspend fun uploadSnapshot(eventId: String, jpegBytes: ByteArray): Result<Unit>`, same
   `runCatching { ... }` shape as `uploadAudio`, `"image/jpeg".toMediaType()`,
   `MultipartBody.Part.createFormData("file", "frame.jpg", body)`. Best-effort -- a single dropped
   frame is not worth surfacing an error for; `DuressController` should just fire-and-forget each
   frame the same way it fires GPS points, not block anything else in the active-phase loop
   waiting on the upload.

4. `domain/DuressController.kt` -- start `DuressCameraCapture` alongside `audioRecorder` the moment
   `DuressUiState.Active` is reached with a real event id (same `runActivePhase` spot), stop it on
   the SAME triggers that already stop the audio recorder (60s cap is audio-specific -- camera
   capture should instead just run for as long as the event stays open, stopping only on terminal
   status, since a duress incident is exactly the scenario where MORE frames over a longer window
   is the point, unlike the audio 60s cap which was an explicit blueprint-mandated ring-buffer
   simplification that has no camera equivalent here). Each captured frame calls
   `repository.uploadSnapshot(eventId, bytes)` fire-and-forget as it arrives.

5. `AndroidManifest.xml` -- confirm `<uses-permission android:name="android.permission.CAMERA" />`
   is present (grep for it first -- the Profile-photo-capture feature already added it for
   `ProfileScreen.kt`'s camera intent, so it likely already is -- if so, nothing to add here).

6. `ui/screens/permissions/PermissionsChecklistScreen.kt` -- confirm CAMERA is already listed
   (again, likely already true given the profile-photo feature) -- if the checklist text is
   audio-recording-specific right now, broaden its copy to also mention the cabin camera so a
   driver isn't confused about why the app wants it.

**What NOT to build:** no in-app viewfinder/preview UI for the driver (this is a silent background
capture, the driver never sees what's being captured -- matches the already-agreed "event-scoped
only" design), no local frame storage/gallery on the device (frames upload and are done, nothing
persists locally), no continuous/always-on capture outside an open duress event.

**Verification once it compiles:** trigger duress on a real device/emulator, confirm the app
requests CAMERA permission if not already granted, confirm
`GET /v1/duress/{event_id}/snapshots` on the deployed server shows new rows appearing every
~3s while the event stays open, confirm the Duress Desk's new camera panel on the dashboard shows
the frame updating live. Report back with the actual verification output, not just "should work."

---

## SYSTEM REFERENCE (2026-08-27) -- read this before writing any v2 UI code

This section exists because nobody working on this app has full context across backend + Figma +
deployment at once. Read it fully before touching screens. It answers: which of the 33 Figma v2
screens are actually built, which backend APIs are proven working against the real deployed
server (not just unit-tested), and the exact end-to-end flow this app is supposed to drive.

### The live server

- API: `http://72.61.107.107:8001` (plain HTTP, no domain/TLS yet -- see
  `docs/DEPLOY_UBUNTU.md`'s "Adding HTTPS later"). `android/local.properties`'s `API_BASE_URL` key
  should already point here (see `app/build.gradle.kts`'s `apiBaseUrlOverride`).
- `android/app/src/main/res/xml/network_security_config.xml` permits cleartext to this ONE IP only
  -- temporary, delete it (and the `AndroidManifest.xml` reference) the day this server gets HTTPS.
- Demo driver: Driver ID `GL2HY`, PIN `123456` (numeric -- the app's PIN keypad is numeric-only,
  do not reuse alphanumeric passwords for driver PINs anywhere, including test fixtures you write).
- Demo staff: `owner@lillycabs.test` / `ChangeMe123!` (dashboard login, not the meter app).

### Figma v2 prototype -> Android reality, screen by screen

Figma file: `https://www.figma.com/design/JhEhok3n9bntRNS5Y1u3Yc`, page "Home v2 -- Map-First".
Every screen below was fully designed, wired, and QA'd in Figma. **The column that matters is the
last one** -- do not assume a polished Figma screen means the matching Android screen exists or
matches its layout; most exist as different, older UI (the pre-map-first wheel-navigation design),
not the Figma v2 design.

| Figma screen | Android file | Status |
|---|---|---|
| 01 Splash | `ui/screens/splash/SplashScreen.kt` | Built, v1-styled |
| 02 Terms | `ui/screens/terms/TermsDisclaimerScreen.kt` | Built, v1-styled |
| 03 Permissions | `ui/screens/permissions/PermissionsChecklistScreen.kt` | Built, v1-styled |
| 04 Driver Login | `ui/screens/login/LoginVehicleBindScreen.kt` | Built -- combined with Vehicle Bind (06) into one screen/flow, not two |
| 05 MFA | `ui/screens/login/LoginVehicleBindScreen.kt` (`MfaStep`) + `LoginVehicleBindViewModel.kt`/`domain/DriverAuthRepository.kt` | **STALE — now BUILT** (2026-09-04 audit pass corrected this row; see the log entry near line 1766). Real two-step TOTP exchange (`mfa_required`/`mfa_token` -> `POST /v1/auth/mfa/login`), inline on the driver-login card, real backend call only |
| 06 Vehicle Bind | `ui/screens/login/LoginVehicleBindScreen.kt` | Built (see 04) |
| 07 Pre-Shift Inspection | `ui/screens/login/LoginVehicleBindScreen.kt` (`InspectionStep`) + `LoginVehicleBindViewModel.kt` | **STALE — now BUILT** (2026-09-04 audit pass corrected this row). Real 9-item, 3x3-grid checklist matching Figma v2 node `10:111` (`PRE_SHIFT_CHECKLIST_ITEMS`), gates shift start (`allChecklistItemsChecked`), posts `inspection_json` on `POST /v1/shifts/start` |
| 08 Shift Start Confirm | `ui/screens/shiftstart/ShiftStartScreen.kt` | Built, v1-styled |
| Home (expanded/collapsed) | `ui/screens/dashboard/WheelDashboardScreen.kt` | **Built, but a DIFFERENT design entirely** -- this is the old rotating 6-slot wheel-navigation paradigm, not the Figma v2 map-first + collapsible bottom-dock design. This is the single biggest visual gap between Figma and the real app. Reskinning this to match Figma v2 is real UI-architecture work, not a token/color pass |
| My Trips (menu) | `ui/screens/trips/TripsWheelViewModel.kt` + `TripsWheelContent.kt` | Built as a wheel-slot, not the Figma v2 dock-menu layout |
| Plot (menu) | `ui/screens/zones/PlotZoneScreen.kt` + ViewModel | Built |
| Available Trips (menu) | `ui/wheel/content/AvailableTripsWheelContent.kt` + ViewModel | Built as a wheel-slot |
| Statistics (menu) | `ui/screens/zones/ZoneStatisticsScreen.kt` + ViewModel | Built |
| Messages (menu) | `ui/screens/messages/MessagesWheelContent.kt` + `MessageThreadScreen.kt` | Built |
| Trip History (menu) | -- | Not found as a distinct screen -- may be folded into Trips/TripDetail, needs verification |
| Navigate (menu) | `ui/screens/navigate/NavigatePlaceholderScreen.kt` (dead/unreachable placeholder) vs. `ui/screens/hired/MeterNavViewModel.kt` (real) | **PARTIALLY STALE** (2026-09-04 audit pass). The dock-menu "Navigate" tile/placeholder screen is unchanged and still fake illustrative data — but it's also confirmed dead code: nothing in the live app (`DeckHomeScreen`) navigates to it any more. Separately, a REAL turn-by-turn feature (Mapbox search/route/live ETA/voice/reroute, `MeterNavViewModel`) shipped 2026-09-04 and is wired into the meter screen (`HiredScreen`) for in-trip navigation to a drop-off — real, reachable, no product decision needed there. Whether a *standalone* pre-trip "navigate to pickup" or trip-independent nav tool should also exist is still an open product question (see `NavigatePlaceholderScreen.kt`'s doc) |
| 16 Start Meter Confirm | Overlay inside `WheelDashboardScreen.kt` | Built as an in-file overlay, not a separate screen (matches Figma's intent, different implementation shape) |
| 17 Set Price | `SetPriceEntryScreen` overlay inside `WheelDashboardScreen.kt` | Built, real end-to-end wire to `TripSyncItem.negotiated_total` |
| 18 Meter -- Hired | `ui/screens/hired/HiredScreen.kt` + `HiredViewModel.kt` | Built, v1-styled |
| 19 Close & Pay | `ui/screens/closepay/CloseAndPayScreen.kt` + ViewModel | Built |
| 20 Voucher / 21 Split Fare | Sub-states inside `CloseAndPayScreen.kt`'s `MethodPickerScreen` | Built as sub-states, not separate Figma-matching screens |
| 22 Receipt | Inside `CloseAndPayScreen.kt` | Built inline, not a separate screen file |
| 23 Job Offer | `ui/screens/availabletrips/AvailableTripOfferScreen.kt` + ViewModel | Built |
| 24 Trip Detail & Dispute | `ui/screens/tripdetail/TripDetailScreen.kt` + ViewModel | Built |
| 25 Shift Report | `ui/screens/shiftreport/ShiftReportScreen.kt` + ViewModel + `ShiftWheelContent.kt` | Built |
| 26 Shift Submitted | `ui/screens/shiftsubmitted/ShiftSubmittedScreen.kt` | Built |
| 27 Profile | `ui/screens/profile/ProfileScreen.kt` + ViewModel | Built |
| 28 Settings & Diagnostics | `ui/screens/settings/SettingsScreen.kt` + ViewModel | Built |
| 29 Admin PIN Gate | `ui/screens/adminpin/AdminPinGateScreen.kt` + `ui/screens/settings/SettingsViewModel.kt` (`attemptFactoryReset`) | **STALE — now BUILT** (2026-09-04 audit pass corrected this row; see the log entry near line 1324). Real server-verified PIN screen (`POST /v1/fleet/devices/{id}/verify-admin-pin`), reached inline from Settings' factory-reset flow — matches `shared/API_SUMMARY.md`'s spec of the admin PIN gating "destructive on-device actions, e.g. the Android app's factory-reset flow"; no other admin-gated action exists in this app that would need a second gate |
| 30 Duress Triggered / 31 Duress Stealth | `domain/DuressController.kt`'s `DuressUiState`, rendered as contextual overlays inside Hired/Dashboard/TripDetail/CloseAndPay/ShiftSubmitted/ShiftStart/Settings screens | Built, **different shape than Figma** -- an overlay system, not the two dedicated full-screen designs Figma shows. Functionally should be equivalent; visually will not match Figma 1:1 without deliberate work |
| 32 Offline & Sync | `ui/screens/offlinesync/OfflineSyncScreen.kt` + `OfflineSyncViewModel.kt` | **STALE — now BUILT.** Real screen surfacing the real outbox count (`SyncOutboxDao.observeOutboxSize`), real cached-tariff read, real `SyncWorker.enqueueOneTime` force-sync, and real `ConnectivityManager` network status; honestly labels the two rows with no backing feature ("driver login cache", "duress SMS fallback") as not-on-this-build rather than fabricating them. Reached from Settings (`onOpenOfflineSync`) |
| 33 Log Off | Not confirmed as a distinct screen | Needs verification -- likely a menu action/dialog, not checked in this pass |

**Bottom line:** roughly two-thirds of the Figma v2 screens have SOME Android equivalent, but almost
all of them are still the OLD v1 wheel-navigation visual language, not reskinned to Figma v2 yet.
~~Five screens (MFA, Pre-Shift Inspection, Navigate, Admin PIN Gate, Offline & Sync) do not exist in
Android at all.~~ **STALE as of 2026-09-04 (see the corrected rows above).** Of those five, four are
now genuinely built and reachable through real navigation: MFA (05), Pre-Shift Inspection (07),
Admin PIN Gate (29), and Offline & Sync (32) — each backed by a real API call, not fabricated. Only
"Navigate" remains a real gap, and even that is narrower than this line implies: real in-trip
turn-by-turn navigation (search/route/ETA/voice) exists and is reachable via the meter screen; what's
still missing is a standalone/pre-trip navigate tool, which is a product-scope decision (see the
"Navigate (menu)" row above), not a straightforward build. The Home dashboard itself is a
fundamentally different navigation paradigm (wheel vs. map+dock) -- that is the biggest single piece
of work if the goal is to match Figma v2 visually.

### API readiness -- what is actually proven to work against THIS live server

Legend: VERIFIED = hit for real against `72.61.107.107` this session, with a real response confirmed.
UNIT-TESTED ONLY = passes `pytest`, never exercised by a real device against the live deployment.
NOT IMPLEMENTED = does not exist yet on either side.

| Area | Endpoint(s) | Status |
|---|---|---|
| Health | `GET /health` | VERIFIED |
| Auth (staff) | `POST /v1/auth/login` | VERIFIED (owner, platform admin) |
| Auth (driver) | `POST /v1/auth/driver-login` | VERIFIED (just fixed live -- PIN was stale on the server, patched via `PATCH /v1/users/{id}`) |
| Users CRUD | `POST/GET/PATCH /v1/users` | VERIFIED (used to create/inspect/fix the demo driver directly) |
| Tariffs | `GET /v1/tariffs/signing-key`, `GET /v1/tariffs/active` | UNIT-TESTED ONLY -- the app has never successfully completed this fetch against the live server yet (blocked until the cleartext fix + PIN fix just landed). This is the very next thing to confirm -- Start Meter depends on it |
| Trips | `PATCH /v1/trips/{id}/tick`, `POST /v1/trips/{id}/close`, `POST /v1/trips/sync` | UNIT-TESTED ONLY -- no real trip has been driven through the live server yet |
| Fleet / device pairing | `POST /v1/fleet/vehicles/{id}/pairing-code`, `POST /v1/fleet/devices/register` | UNIT-TESTED ONLY -- this tablet has never been through the real QR-pairing flow against this server |
| Live position | `POST /v1/fleet/positions` (heartbeat, 30s) | UNIT-TESTED ONLY |
| Duress (tablet path) | `POST /v1/duress/trigger`, `/gps`, `/audio`, `/escalate`, `/close`, `WS /{id}/live` | UNIT-TESTED ONLY against this deployment (was live-verified in an earlier local pass, not against this server) |
| Duress (physical device path) | `POST /v1/devices/auth`, `/duress/device/alarm`, `/duress/device/{id}/gps`, `/duress/device/{id}/audio` | NOT IMPLEMENTED on the Android side at all -- no BLE client exists yet. Backend is real and tested; nothing on this app talks to it |
| Jobs / dispatch | `POST /v1/jobs`, `WS /v1/jobs/live`, accept/decline | UNIT-TESTED ONLY against this deployment |
| Messages | `WS /v1/messages/live`, quick-tap templates | UNIT-TESTED ONLY against this deployment |

**Read this plainly: almost nothing has been proven against the real server yet except login.**
That is not a criticism of the app -- it is simply the honest current state, and it is exactly what
the current five-step verification pass (tariff fetch, Start Meter, offline maps, a real trip) exists
to close, one item at a time, with real evidence each time (logcat, screenshots), not assumptions.

### The full operational flow, end to end

1. **Setup (once per tablet):** admin generates a pairing code on the dashboard
   (`POST /v1/fleet/vehicles/{id}/pairing-code`) -> tablet scans/enters it ->
   `POST /v1/fleet/devices/register` binds this Android install to that vehicle permanently.
2. **Driver login:** `POST /v1/auth/driver-login` with `{driver_code, pin}` -> JWT stored, session
   held in `SessionHolder`.
3. **Tariff sync:** app fetches `GET /v1/tariffs/signing-key` (Ed25519 public key, cached), then
   `GET /v1/tariffs/active?region=...` -- verifies the Ed25519 signature locally before writing it
   to Room. Only a verified, cached tariff enables Start Meter (`uiState.tariff != null`).
4. **On shift:** `LivePositionHeartbeat` posts `POST /v1/fleet/positions` every 30s -- this is what
   feeds the dashboard's Live Map.
5. **Start Meter:** requires session + verified tariff (see #3). Opens a local `Trip` row
   (Room, tagged with a client-generated UUID).
6. **During the trip:** `PATCH /v1/trips/{id}/tick` sends telemetry; fare computes live from the
   cached, signed tariff (no network needed mid-trip).
7. **Close:** `POST /v1/trips/{id}/close` finalizes server-side via the fare engine. If offline at
   any point, everything queues in a local outbox and flushes via `POST /v1/trips/sync`
   (idempotent on `client_uuid`) the moment connectivity returns.
8. **Dashboard visibility:** the instant a trip syncs, `GET /v1/trips` on the dashboard shows it --
   same table, no separate pipeline.
9. **Duress:** trigger opens an event, streams GPS every 5s, dashboard's Duress Desk watches
   `WS /v1/duress/{id}/live` in real time; unresolved escalation fires a real Twilio call.

### Priority order for this session

1. Finish the five-step verification already in progress (tariff fetch -> Start Meter enabled ->
   offline map download -> a real trip synced) -- do not skip ahead of this.
2. Only once all five are confirmed working: begin reskinning `WheelDashboardScreen.kt` toward the
   Figma v2 map-first design -- this is the biggest visual gap and the highest-value next step.
3. ~~Flag (do not silently skip) any of the five NOT BUILT screens above if a real product decision
   is needed on them (Navigate, Admin PIN Gate, Offline & Sync, MFA, Pre-Shift Inspection) -- these
   are real product gaps, not implementation details you should invent alone.~~ **STALE as of
   2026-09-04**: only "Navigate" (a standalone/pre-trip nav tool, as opposed to the real in-trip
   turn-by-turn that already exists) is still an open product-decision gap — see the corrected rows
   above. The other four are built.


You (Claude Code, running via the JetBrains plugin inside Android Studio) have **no memory of
the session that generated this code**. This file is written to be fully self-contained so you
can pick the work up cold. Read it fully before touching code.

## What this is

Cab Dispatch is an NSW taxi-meter SaaS: FastAPI backend + React fleet dashboard + this Android
meter app (Kotlin/Compose, offline-first). Full product spec: `../docs/TCT-METER-01-spec.md`
(sections B5/B6/B7 are the ones that matter for this module — screens, fare engine, offline
behaviour). Repo: https://github.com/360-australiaa/cabdispatch

**Honest current status:** every file in this module has been written and *read carefully* by a
prior agent pass, including three reconciliation passes (2026-08-03's GPS-core/4-sibling pass —
see immediately below — 2026-08-01's wheel-redesign pass, and an earlier one that fixed S3 not
persisting trip ticks to Room, see `ui/screens/hired/HiredViewModel.kt`'s doc comment if you want
that history). But **it has never been compiled**. The environment that wrote it had no Android
SDK. This machine (yours) is the first place this code will ever hit a real compiler (a real
device build only just started elsewhere and has already found one real bug — a Mapbox `MapView`
lifecycle issue, now fixed, see the 2026-08-02 "solid black map" entry below). Expect and budget
time for real compile errors — signature mismatches between sibling files that were written in
parallel without ever type-checking against each other are the most likely failure mode, not
conceptual bugs.

## 2026-08-10 (meter-polish pass) — Set Price, start-meter confirmation, boot-time terms screen, permissions checklist, shift-limit countdown

Direct request: five small meter-flow polish items matching real competitor taxi-meter UX
patterns from reference screenshots. Read `ui/screens/dashboard/WheelDashboardScreen.kt`'s
`StartMeterButton`/`onStartMeterTapped` flow fully first, per the brief. Scoped to avoid
`domain/fare/FareEngine.kt` entirely (see "Known gap" below for the one real consequence of that
choice) and to avoid re-guessing the backend's Set Price contract — used the exact fields the
same-session fare-set-price backend agent reported: `TripCreate`/`TripSyncItem.negotiated_total`
(`Decimal | None`, `[1.00, 500.00]`). `FareEngine.close()`'s negotiated-total branch is
backend-only, nothing on this side computes a fare — this app just captures and forwards the
number.

**1. "Set Price" (negotiated/fixed fare):**
- `domain/Session.kt` — `TripContext` gained `negotiatedTotal: String? = null` (decimal-as-string,
  this project's money-field convention). `DriverSession` also gained `shiftStartAt: String?`
  (see item 5 below — bundled into the same file edit since both are small additive fields on the
  same two data classes, not because they are related features).
- `ui/screens/dashboard/WheelDashboardScreen.kt` — a small "Set Price" `TextButton` above
  `StartMeterButton` (enabled/disabled by the same `startMeterEnabled` gate), opening a new
  full-screen `SetPriceEntryScreen` overlay composable in the same file (same pattern as the
  existing "Starting meter..." transition overlay — a same-composable overlay, not a new
  `CabDispatchNavHost` route, so this stays inside the one file this task was scoped to touch).
  The note text is the exact wording the task brief specified: "This price doesn't include levies
  and/or tolls." Confirming calls `WheelDashboardViewModel.startMeter(negotiatedTotal)` — the
  method's existing `Boolean` return / no-tariff/no-session guard is completely unchanged, it just
  gained one new optional, defaulted parameter threaded into `TripContext`.
- `WheelDashboardViewModel.startMeter()` -> `TripContext.negotiatedTotal` ->
  `HiredViewModel.openTripInRoom` -> `TripRepository.openTrip(negotiatedTotal = ...)` ->
  `TripEntity.negotiatedTotal` (new nullable column, `AppDatabase` bumped 4 to 5, no Migration,
  same "still pre-release" shortcut every prior version bump in this file used) ->
  `toSyncItemDto()` -> `TripSyncItemDto.negotiatedTotal` (`negotiated_total` on the wire). This is
  a real, working end-to-end wire, not just a capture-and-drop: unlike
  `voucherCode`/`accountReference` (which a prior pass found were dropped by an older
  `TripSyncItem` backend schema — see the 2026-08-03 entry below), the fare-set-price backend
  agent's own contract notes confirm `TripSyncItem.negotiated_total` was declared from the start
  this same session, with the same validation as `TripCreate`'s.
- `data/remote/ApiService.kt` — `negotiated_total` added to `TripCreateDto`, `TripSyncItemDto`,
  and `TripDto` (nullable-defaulted on all three, per this file's own "old cached payload still
  decodes" convention). **One flagged, unverified assumption:** the backend agent's contract only
  explicitly lists `negotiated_total` on `Trip`/`TripCreate`/`TripSyncItem`, not `TripRead` by
  name — added to `TripDto` anyway since every other model field this project's `TripRead`-backed
  DTOs expose so far has been 1:1 with the model, but this one specific field was not confirmed
  against a real `TripRead` response body.

- **Known gap, flagged loudly rather than silently assumed handled:** per the task's explicit
  instruction, `domain/fare/FareEngine.kt` (the golden-vector-proven engine) and
  `domain/fare/TripFareReconstruction.kt` were **not** touched. The backend's real
  `FareEngine.close()` zeroes flag/peak/distance/waiting and charges `negotiated_total + tolls +
  psl + extras` when `negotiated_total` is set — this Android module's on-device reconstruction
  (used by Trip Detail / the on-device receipt / S5 shift report) has no equivalent branch, so a
  Set Price trip's on-device-displayed total will not match what the backend actually bills until
  someone ports that branch here too. The live ticking meter display (`domain/FareEngineImpl`,
  S3/HIRED) also keeps showing the normal running metered fare after a Set Price start — it is
  never told about the negotiated total at all. Neither of these was in this task's explicit scope
  ("must ONLY add a new optional parameter, never change existing behaviour when it is absent" —
  extending the `close()`/reconstruction math is a materially bigger change than that), so this is
  a real, live discrepancy between what the driver sees on-device and what actually settles
  server-side for a Set Price trip, not a hypothetical one.

**2. Start-meter confirmation dialog:** none existed before this pass (confirmed by reading the
whole `onStartMeterTapped` flow first, per the brief). `StartMeterButton`'s `onClick` now sets a
`showStartConfirm` flag instead of calling `onStartMeterTapped()` directly; a single Compose
`AlertDialog` ("Are you sure you want to start the meter?") gates the real call. Kept as a plain
`AlertDialog` overlay, not a new screen/route, per the brief. Set Price deliberately does **not**
route through this same dialog — its own numeric-entry screen already ends in an explicit "start
meter at $X" confirm action, so stacking a second confirmation on top would be pure friction, not
an extra safety check. `onStartMeterTapped` itself is otherwise unchanged (same animation
sequence, same `viewModel.startMeter()` call, same HIRED navigation) — it just gained the one new
optional `negotiatedTotal` parameter both callers (the dialog's confirm button, and Set Price's
confirm) now pass through.

**3. Boot-time terms/disclaimer screen:** new `ui/screens/terms/TermsDisclaimerScreen.kt` +
`domain/TermsAcceptance.kt` (SharedPreferences-backed, same real, already-established pattern
`SharedPreferencesDriverAuthRepository` uses elsewhere in this codebase for a small persisted
flag not worth a new Room entity/DAO/migration). **Gating choice, per the task's own "your call,
document the choice" instruction:** gated per app **version code**, not per install — a plain
once-per-install flag would never re-surface the disclaimer if a later release genuinely changes
the terms; re-prompting once per version bump was judged the safer default for a
compliance-adjacent screen. New `CabDispatchRoutes.TERMS_DISCLAIMER` route, registered ahead of
S1/S2. `SplashScreen.kt`'s existing "does a session already exist" branch is now factored into a
shared `postAuthDestination()` top-level function in `CabDispatchNavHost.kt`, since both Splash's
normal path and the new Terms screen's Accept action need the exact same decision — Splash now
checks `TermsAcceptance.isAccepted` first and routes to `TERMS_DISCLAIMER` instead of S1/S2 when
it is false; the Terms screen's Accept button marks acceptance then navigates to
`postAuthDestination()` with `popUpTo(TERMS_DISCLAIMER){inclusive=true}`. Copy matches the task
brief's reference tone ("Please read and accept... your continued use of this app will mean you
have accepted these terms") — **not real legal text**, no legal-reviewed Terms & Conditions /
Privacy Policy document exists anywhere in this repo to source from; flagged here rather than
implied to be production copy. Decline finishes the hosting Activity outright (no meaningful
"use the app without accepting" fallback state exists past this screen) — **genuinely untested**,
same as everything else in this module.

**4. Permissions-checklist screen:** new `ui/screens/permissions/PermissionsChecklistScreen.kt`,
reachable from Settings (S6) via a new "App permissions" button. Read `AndroidManifest.xml` for
the real permission list rather than guessing: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
`CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` (the runtime/dangerous ones, added across several
prior passes) — `INTERNET`/`ACCESS_NETWORK_STATE` deliberately excluded, they are
normal-protection-level permissions granted automatically at install and would only add confusing
noise next to real dangerous-permission entries. Read-only status display, per the brief — no
request flow, since (confirmed by grep) this codebase has no existing runtime-permission-request
wiring anywhere to hook into; every existing `ContextCompat.checkSelfPermission` call site in this
module only ever checks, never requests.

**5. Live shift-duration countdown:** new `domain/ShiftDurationLimit.kt` + a `ShiftCountdownChip`
composable in `WheelDashboardScreen.kt`'s `TopStatusStrip` (reuses that composable's existing 30s
clock ticker as its own recompute cadence, rather than adding a second timer/coroutine). Checked
`backend/app/core/config.py` first, per the brief: `FATIGUE_SHIFT_DURATION_LIMIT_HOURS` (default
`12.0`) is real and consumed server-side by `app/services/fatigue.py`, but no endpoint exposes it
to this client (grepped the whole `app/api/v1/` tree — nothing outside `config.py`/`fatigue.py`
references the constant). Rather than block this item on a new backend endpoint, this hardcodes
the same default value client-side and computes remaining time from `DriverSession.shiftStartAt`
(new field, populated from `ShiftDto.startAt` at `LoginVehicleBindViewModel.startShift` — the one
real construction site of `DriverSession` in this codebase) minus `now`. **Flagged, live risk of
this simplification, not hypothetical:** if a tenant is ever configured with a non-default
`FATIGUE_SHIFT_DURATION_LIMIT_HOURS` server-side, this on-device countdown will silently disagree
with the backend's real fatigue enforcement. The chip shows "Shift: Xh Ym left" normally and an
explicit "Shift limit exceeded" state once past the limit (a negative `Duration`, deliberately not
clamped to zero, so the two states are visibly different) — reuses `StatusChip`'s existing
dot+label visual language, so it is only ever a binary ok/not-ok color today; a three-state
"approaching the limit" amber warning is a real, deliberately deferred follow-up.

**Files changed/added:** `domain/Session.kt`, `domain/ShiftDurationLimit.kt` (new),
`domain/TermsAcceptance.kt` (new), `ui/screens/login/LoginVehicleBindViewModel.kt`,
`data/remote/ApiService.kt`, `data/local/entity/TripEntity.kt`, `data/local/AppDatabase.kt`,
`data/repository/TripRepository.kt`, `ui/screens/hired/HiredViewModel.kt`,
`ui/screens/dashboard/WheelDashboardViewModel.kt`, `ui/screens/dashboard/WheelDashboardScreen.kt`,
`ui/navigation/CabDispatchNavHost.kt`, `ui/screens/splash/SplashScreen.kt`,
`ui/screens/terms/TermsDisclaimerScreen.kt` (new),
`ui/screens/permissions/PermissionsChecklistScreen.kt` (new),
`ui/screens/settings/SettingsScreen.kt`.

**Concurrency note:** several sibling agents were editing `AppContainer.kt`/`ApiService.kt`/
`HANDOFF.md`/`WheelDashboardScreen.kt`/`CabDispatchNavHost.kt` in this same pass (a "Zones"
statistics feature and a driver-photo feature both landed mid-session, visible as new content in
`WheelDashboardScreen.kt`'s `TopStatusStrip` and this file's other 2026-08-10 entries that were
not there when this task started). Every edit in this entry was applied via exact-anchor string
replacement against a **freshly re-read** copy of each file immediately before editing it, not a
stale copy from the start of this session, specifically to merge on top of those concurrent
changes instead of clobbering them — confirmed afterward by re-reading
`WheelDashboardScreen.kt`/`CabDispatchNavHost.kt` end-to-end and checking brace/paren balance
across every touched file. This pass did **not** touch `AppContainer.kt` at all (nothing in this
task needed a new singleton), so there was no direct collision surface with sibling agents' own
`AppContainer.kt` edits.

**Genuinely unverified, same standing caveat as every entry in this file — this machine has no
Android SDK either:** none of this has ever been run through `kotlinc`. Reasoned through by eye,
not compiler-checked. Highest-risk spots to check first on a real build, in order:
1. `ShiftCountdownChip`'s `remember(session?.shiftStartAt, now) { ... } ?: return` early-return
   pattern inside a `@Composable` — early-return-from-composable is used elsewhere in this
   codebase's ViewModels (e.g. `WheelDashboardViewModel.startMeter`'s `?: return false`) but this
   is the first place in this pass doing it directly inside a `@Composable` function body; believed
   valid Kotlin (a bare `return` from a `Unit`-returning function is always legal) but not
   cross-checked against another example already in this file.
2. `SetPriceEntryScreen`'s `BigDecimal` + string-concatenation currency formatting
   (`"START METER AT " + CURRENCY_SIGN + displayAmount`) — deliberately avoided Kotlin string
   templates for the literal dollar sign to sidestep an unrelated tooling issue this session (see
   below), using plain `+` concatenation instead; valid Kotlin either way (`String.plus(Any?)` on
   a `BigDecimal`/`String` right-hand side calls `toString()`) but a different code shape than
   this file's other money-formatting call sites, worth a second look.
3. `TermsDisclaimerScreen.kt`'s `(context as? ComponentActivity)?.finishAffinity()` — reasoned
   through (this screen is always hosted by `MainActivity`, a real `ComponentActivity`, per
   `AndroidManifest.xml`) but the Decline path has never been exercised on a device or emulator.
4. The `AppDatabase` 4 to 5 bump (new nullable `TripEntity.negotiatedTotal` column) — same
   no-Migration shortcut every prior bump in this file used, same caveat: fine pre-release, would
   need a real `Migration` the moment this ships.

**Unrelated environment note, in case a future pass hits the same thing:** this session's
`Edit`/`Write` tools were broken the whole time (a `PreToolUse` hook crashes on the
`C:\Users\Surface Pro 7\...` path with a space) — every file change in this entry was made via
`Bash` + Python instead, same workaround already documented in this session's own backend-agent
reports. One extra wrinkle worth flagging for whoever hits it next: very large single `Bash`
heredoc payloads (roughly 90+ lines in one call) intermittently failed with a bash-level
"unexpected EOF while looking for matching" quote error before Python ever ran, even with a
quoted heredoc delimiter and no unbalanced quotes in the actual content — splitting the same
content across two or three smaller `Bash` calls (writing to a scratch file, then combining)
reliably worked around it. Not fully root-caused; noted here rather than left a silent mystery.

## 2026-08-10 — Driver photo capture/upload (Profile screen)

Direct request, scoped to `ui/screens/profile/` (the Profile screen, reachable from the
dashboard's identity card) plus `ui/screens/login/LoginVehicleBindScreen.kt` — both read fully
first, per the brief. Checked whether a camera/photo-picker pattern already existed anywhere in
this module before writing anything: it does not. `domain/QrScanner.kt` (`StubQrScanner`) is a
manual-entry stub with zero real camera wiring (see HANDOFF's own "Medium priority" gap list —
"real camera scanning (CameraX + ML Kit) isn't implemented"), and nothing else in this module
captures or picks an image. `domain/location/RealLocationProvider.kt`'s "check, never request,
degrade gracefully on missing permission" pattern was mirrored for the *background* GPS case that
file documents, but a photo capture is a direct one-tap user action, not a passive background
feed — see `ProfileScreen.kt`'s `IdentityHeader` doc for why that made an inline runtime `CAMERA`
permission *request* the right fit instead (the first real permission-request flow anywhere in
this module — every existing `ContextCompat.checkSelfPermission` hit before this pass only
checked, never requested, per this file's own standing note). `domain/duress/
DuressAudioRecorder.kt` / `RemoteBackedDuressRepository.uploadAudio` (`domain/DuressRepository.kt`)
was read for the multipart-upload convention already established in this codebase and mirrored
field-for-field (`MultipartBody.Part.createFormData("file", ...)`).

Did NOT touch `domain/fare/FareEngine.kt` or `domain/fare/` — no reason to, unrelated domain.

**Backend contract used exactly as built this session** (BACKEND AGENT 2's report in the task
brief — read directly off that report's own CONTRACT section, not guessed):

- `POST /v1/users/{user_id}/photo` — multipart (`file` field), staff-role-gated
  (`owner`/`admin`/`dispatcher`). Returns `UserRead`.
- `GET /v1/users/{user_id}/photo` — any authenticated tenant user. Returns the raw image via
  `FileResponse`. 404 if the user has no photo or the file is missing.
- `UserRead` (the Android `UserDto`) gained `photo_url: str | None` — a relative on-disk path, not
  a directly-loadable URL.

**What changed:**

- **`data/remote/ApiService.kt`** — new `uploadUserPhoto(userId, file: MultipartBody.Part):
  UserDto` (`@Multipart @POST("/v1/users/{userId}/photo")`) and `getUserPhoto(userId):
  ResponseBody` (`@Streaming @GET("/v1/users/{userId}/photo")` — a raw-bytes `FileResponse`, not
  JSON, hence the plain `ResponseBody` return type instead of a DTO). `UserDto` gained a nullable
  `photoUrl` field (`@SerialName("photo_url")`), defaulted so no existing call site/construction
  needed a change.
- **New photo state on `ui/screens/profile/ProfileViewModel.kt`** (kept on the same `ViewModel`
  as the existing Compliance section, not a third one — see its own doc for why): `photoState`
  (`NoPhoto`/`Loading`/`Loaded(bitmap)`), `isUploadingPhoto`, `photoUploadError`. `loadPhoto()`
  fetches whatever is already on file via `getUserPhoto` and decodes it with
  `BitmapFactory.decodeStream` — a 404 or any other failure both land on `NoPhoto` (never a crash,
  never a broken-image icon), same "degrade to placeholder, do not error the whole screen"
  philosophy the existing `ComplianceSection` fallback already uses. `uploadPhoto(bytes:
  ByteArray)` posts the multipart body and, on success, decodes the same bytes straight back into
  the preview `Bitmap` rather than re-fetching over the network.
- **`ui/screens/profile/ProfileScreen.kt`'s `IdentityHeader`** — the avatar circle is now
  tap-to-capture-or-pick, not initials-only (initials remain the fallback whenever `photoState`
  is not `Loaded`, matching `WheelDashboardScreen.kt`'s `IdentityCard` avatar-fallback style this
  screen's own `IdentityHeader` already used). Two entry points: "Camera"
  (`ActivityResultContracts.TakePicturePreview` — the simpler of the two standard capture
  contracts per the brief, since it hands back an in-memory `Bitmap` with no `FileProvider`/
  manifest `<provider>` entry needed) and "Gallery" (`ActivityResultContracts.GetContent` — no
  runtime storage permission needed either, launches the system picker out-of-process). Camera
  requests `Manifest.permission.CAMERA` inline before launching (already declared in
  `AndroidManifest.xml` for the still-stubbed QR scanner, so no manifest change was needed); a
  denial falls back to an inline hint, never a crash. A read failure on the gallery `Uri` (rare,
  but a content provider can misbehave) deliberately reuses `uploadPhoto`'s own `bytes.isEmpty()`
  guard by passing an empty `ByteArray` rather than adding a second error-plumbing path.
- **`app/src/test/java/.../sync/OutboxDrainerTest.kt`'s `FakeApiService`** — added stub overrides
  for the two new `ApiService` methods (`uploadUserPhoto`/`getUserPhoto`, both `notUsed()`, same
  convention as every other unused override in that class). See the real, pre-existing gap flagged
  below for why this class almost certainly already failed to compile before this pass touched it,
  independent of these two additions.

**Cross-agent contract mismatch this pass flagged — fixed during integration verification,
not left standing:** `POST /v1/users/{user_id}/photo` originally shipped **staff-role-gated**
(`owner`/`admin`/`dispatcher`), which would have 403'd this exact Profile-screen call site for
every real `driver`-role account (this feature was scoped as a driver-facing action, and a driver
uploading their own photo is the obvious use case). Caught during the post-workflow verification
pass and fixed server-side: `POST /v1/users/{user_id}/photo` is now **self-or-staff gated** — any
authenticated user may upload their OWN photo (`caller.id == user_id`), and staff may additionally
upload on behalf of any user in their tenant (the admin-managed-onboarding case). See
`backend/app/api/v1/users.py`'s `upload_user_photo` doc comment and
`backend/tests/test_users.py::test_driver_can_upload_their_own_photo` (live-verified over real
HTTP too: a real driver token successfully uploaded to its own `user_id`). No Android-side change
was needed — this app was already calling the endpoint correctly as "the signed-in user updating
their own photo"; the backend's original gate was the bug, not this client.

**Second real, pre-existing gap surfaced while wiring this (not introduced by this pass):**
`OutboxDrainerTest.kt`'s `FakeApiService` already did not override every `ApiService` abstract
member before this pass touched it — `driverLogin`, `mfaLogin`, `verifyAdminPin`,
`tariffSigningPublicKey`, `publishPosition`, every duress endpoint, `getComplianceDossier`, the
zones endpoints, and more are all missing overrides too, none of it added by this pass. That
strongly suggests this JVM test module was already failing to compile against the real
`ApiService` interface for reasons unrelated to this change, quite possibly since one of the
earlier sibling passes that added those methods never touched this test file. Only the two methods
this pass itself added were backfilled here — fixing the rest is a materially bigger, pre-existing
cleanup job, out of this pass's scope, flagged here rather than silently left for whoever hits the
resulting compile error first.

**Genuinely unverified, same standing caveat as every entry in this file — this machine has no
Android SDK either, none of this has ever been run through `kotlinc`:** the two riskiest points to
check first on a real build, by eye: (1) `ActivityResultContracts.GetContent()` is
soft-deprecated in newer `androidx.activity` releases in favor of `PickVisualMedia` — it is still
present and functional as of the `activity-compose:1.9.1` version this project already depends on
(confirmed in `app/build.gradle.kts`), chosen deliberately as "the simpler/more standard choice"
per the brief, but a lint warning (not a compile error) should be expected; (2) the JPEG
re-compression quality (85) used when converting a captured `Bitmap` to upload bytes is a plain
reasonable default, not measured against any real size constraint the backend contract does not
name. Everything else in this pass (the `@Multipart`/`@Streaming` Retrofit annotations, the
`ByteArray`-based `MultipartBody.Part.createFormData` call, the `ViewModel` state-flow shape) is
the same well-established Retrofit/OkHttp/Compose surface every prior pass in this file has
already used successfully — see `RemoteBackedDuressRepository.uploadAudio` for the one other
multipart call site this pass's own convention was checked against.

## 2026-08-10 — Quick-tap canned messages (message templates, matching a real competitor
taxi-meter's "No Job / Recall / Job Query / Other" quick-request menu)

Scope per the brief: read `ui/screens/messages/MessagesWheelContent.kt`,
`ui/screens/messages/MessageThreadScreen.kt`, `ui/screens/messages/MessagesViewModel.kt`, and
`domain/MessagesRepository.kt` fully first. Did NOT touch `domain/fare/FareEngine.kt` or
`domain/fare/` — no reason to; unrelated domain.

**Backend contract used exactly as built this session** — read directly from
`backend/app/schemas/messages.py` and `backend/app/api/v1/messages.py`, not guessed. Same
situation as the 2026-08-10 zones entry immediately below this one: none of the six pasted
backend-agent session reports in the task brief actually described the message-templates
contract at all (one report was a jobs/duress-adjacent report, others covered evidence packs,
negotiated fares, the platform console, or hadn't finished polling their test run) — the only
honest way to get the real field names/paths was to read the backend source directly. Confirmed
`messages_router` is registered in `backend/app/main.py` (it is).

- `GET /v1/messages/templates` -> `list[MessageTemplateRead]` (`code`/`label`/`sender_type`) — any
  authenticated tenant user, not tenant-specific data.
- `POST /v1/messages/templates/{code}` -> `MessageRead` — body `TemplateMessageCreate`
  (`driver_id: str | None`, `note: str | None`, max 500 chars). Same sender-attribution rule as
  free-text `POST /v1/messages`: a `driver`-role caller always sends as themselves, `driver_id` in
  the body is ignored for them.
- Fixed driver-side template codes as seeded server-side (`app.services.messages.MESSAGE_TEMPLATES`):
  `no_job` ("No Job"), `recall` ("Recall"), `job_query` ("Job Query"), `other` ("Other"). There are
  also three `dispatch`-typed codes (`check_in`/`return_to_depot`/`contact_base_urgent`) — this
  driver-facing app filters the fetched list down to `sender_type == "driver"` client-side and
  never renders or sends the dispatch-side codes.

**What was added:**

- `data/remote/ApiService.kt` — `listMessageTemplates(): List<MessageTemplateDto>` (`GET
  /v1/messages/templates`) and `sendTemplateMessage(code, TemplateMessageCreateDto):
  MessageDto` (`POST /v1/messages/templates/{code}`), plus the `MessageTemplateDto`
  (`code`/`label`/`sender_type`) and `TemplateMessageCreateDto` (`driver_id`/`note`) DTOs, placed
  next to the existing Messages section/DTOs.
- `domain/MessagesRepository.kt` — `listTemplates(): Result<List<MessageTemplateDto>>` and
  `sendTemplateMessage(driverId, code, note): Result<MessageDto>` added to the interface and to
  `RemoteBackedMessagesRepository`, same thin `runCatching` wrapper pattern as every other method
  in that class. No change to `AppContainer.kt` was needed — `messagesRepository`'s existing
  singleton wiring already exposes these through the same interface reference every screen already
  pulls from `AppContainer`.

- `ui/screens/messages/MessagesViewModel.kt` — new `MessagesUiState` fields: `templates`,
  `templatesError`, `sendingTemplateCode`, `templateSendError`, `otherNoteText`. New private
  `MessageTemplatesCache` object (file-private, process-lifetime in-memory cache guarded by a
  `Mutex`) so the template menu is genuinely fetched once per app process rather than once per
  `MessagesViewModel` instance — worth knowing: `MessagesWheelContent` and `MessageThreadScreen`
  are separate nav destinations in `CabDispatchNavHost.kt` (`composable(...)` blocks), so each
  `viewModel()` call there actually creates its own `MessagesViewModel` instance scoped to that
  destination's own `NavBackStackEntry` — this file's own top-of-file doc comment claims the two
  screens "share this one ViewModel", which on inspection isn't literally true for `viewModel()`
  called with no shared `ViewModelStoreOwner`; pre-existing claim, not introduced or fixed by this
  pass, but the `MessageTemplatesCache` singleton is what actually makes "fetch once" true across
  both screens regardless of that. `loadTemplates()` runs from `init` alongside the pre-existing
  `loadThread()`/`observeLive()` calls, filters the response to `sender_type == "driver"`.
  `sendTemplate(code)` mirrors `sendReply()`'s pattern (`driverId = null`, merges the returned
  `MessageDto` into the thread) but also threads through `otherNoteText` as `note` only when
  `code == "other"`, and refuses to start a second send while one is already in flight
  (`sendingTemplateCode != null` guard) so a driver mashing a different button mid-send is a no-op
  instead of a double-send race.

- `ui/screens/messages/MessageThreadScreen.kt` — new `TemplateQuickTapRow` composable, placed
  between the thread's error texts and the existing free-text `ReplyComposer` at the bottom of the
  screen (not on the wheel-slot list side — the brief asked for "somewhere sensible in the messages
  thread UI", and this screen is the one with room for a whole extra row without crowding the
  6-row wheel-slot preview list). A horizontally-scrollable `LazyRow` of large (`heightIn(min =
  48.dp)`) tap-target buttons, one per driver-side template, label text upper-cased. Every
  template except "other" sends immediately on tap (`onTap(template.code)` straight into
  `viewModel.sendTemplate`). "Other" instead toggles a small inline `TextField` + a compact SEND
  button beneath the row — the brief's "if the backend contract supports an optional free-text
  suffix for an Other template, add a small text field only for that one case" applies exactly:
  the field never appears for the other three templates. The tapped button shows a
  `CircularProgressIndicator` in place of its label while `sendingTemplateCode` matches its own
  code (not the whole row), and every button in the row is disabled (not just the busy one) while
  any send is in flight, via `enabled = sendingCode == null`. Reuses the exact `TextField`/
  `TextFieldDefaults.colors(...)` block already established by `ReplyComposer` in the same file
  rather than inventing a second visual language for text input.

**Known duplication, left alone deliberately:** the `"other"` template code literal exists as a
private constant in two places — `MessagesViewModel`'s companion object
(`OTHER_TEMPLATE_CODE`, used to decide whether to forward `otherNoteText` as `note`) and a
separate top-level `private const val OTHER_TEMPLATE_CODE` in `MessageThreadScreen.kt` (used to
decide whether to show the inline note field vs. send immediately). Both are `private`, so neither
file can reference the other's copy without promoting one to `internal`/public — not done this
pass since it's a one-line literal with an obvious/stable source of truth
(`app.services.messages.MESSAGE_TEMPLATES`'s `code="other"`), not worth widening either class's
visibility surface for. A future pass wanting a single source of truth could promote one copy and
have the other file import it.

**Real risk / what's unverified (this was never run through a compiler, same standing caveat as
every entry in this file):**

- `LazyRow` + the `items(...)` extension: this file already imported `androidx.compose.foundation.
  lazy.items` for the pre-existing `LazyColumn` in this same screen, and that same `items` overload
  is documented to work against `LazyListScope` (which both `LazyRow` and `LazyColumn` provide) —
  reasoned through as correct by eye, but literally the first time this codebase's `LazyRow` usage
  would be compiled, since no other screen in this module uses `LazyRow` to compare against.
- `TextFieldDefaults.colors(...)`'s exact parameter set was copied verbatim from the pre-existing
  `ReplyComposer` in the same file (which presumably already compiled fine in whatever earlier pass
  wrote it, though "presumably" is doing real work in that sentence — nothing in this module has
  actually compiled yet, see this file's top section) — low risk since it's a literal copy, not new
  API surface, but flagging per this file's own "be honest" standard rather than asserting
  confidence I can't back with a real build.
- `MessageTemplatesCache`'s `Mutex`/`withLock` usage (`kotlinx.coroutines.sync`) is a new import in
  this module's messages screen — standard coroutines API, but unverified against this project's
  actual `kotlinx-coroutines-core` version/whatever it resolves to until `./gradlew assembleDebug`
  runs.
- Not manually tested against a running backend/emulator (no Android SDK in this sandbox, see this
  file's top section) — the contract above was read from source, not exercised over the wire.

## 2026-08-10 (newest) — Plot / Statistics screens (zone-based demand, matching a real competitor taxi meter)

Direct request, single most-requested feature-parity item this pass. Scope per the brief: read
`ui/screens/dashboard/WheelDashboardScreen.kt`, the wheel-slot content pattern
(`ui/wheel/content/`, e.g. `AvailableTripsWheelContent.kt`), and `ui/navigation/CabDispatchNavHost.kt`
first, then decide wheel-slot vs. separate-destination on the merits. Did NOT touch
`domain/fare/FareEngine.kt`.

**Wheel slot vs. separate destination — checked first, not guessed.** `ui/wheel/WheelState.kt`
declares `SLOT_COUNT = 6` as a hardcoded constant (`STEP_DEGREES = 360f / SLOT_COUNT`), and every
one of the 6 slots (Off Duty/Available, Available Trips, Messages, Trips, Earnings, Shift) is
already wired to real content per the 2026-08-01 reconciliation entry below — there is no free
slot, and the angle/geometry math in `ui/wheel/WheelGeometry.kt` (icon positions, spoke angles,
label ring) is built against that fixed count throughout `WheelDashboardScreen.kt`'s
`WheelArea`/`WheelRimAndSpokes`/`WheelSlotDot`. Retrofitting a 7th/8th slot would mean changing
`SLOT_COUNT` and re-deriving every angle constant with no way to verify the result on a real
screen in this sandbox — clearly more disruptive than the task brief's "use your judgement, match
whatever is least disruptive" standard. Went with separate destinations instead, reached from a
new small "Zones" entry point added to `WheelDashboardScreen.kt`'s existing `TopStatusStrip`
(the GPS/4G/Printer/Batt chip row) — a plain clickable label at the same visual weight as the
status chips beside it, not a full button, so it doesn't compete with `StartMeterButton`/the new
"Set Price" `TextButton` a concurrent sibling pass added to the same screen this same day (see
that pass's own entry, once it lands — `SetPriceEntryScreen`/`negotiatedTotal` were already present
in `WheelDashboardScreen.kt` when this pass started reading it; left entirely alone, out of scope).

**Backend contract used exactly as built this session by the zones-demand backend agent** (read
directly from `backend/app/api/v1/zones.py`/`app/schemas/zones.py`/`app/models/shift.py`, not
guessed — that agent's own session report never actually included the zones contract text, only
an oblique mention of a sibling-added "zones" section in `main.py`'s docstring, so the real source
was the only honest way to get this right):
- `GET /v1/zones?skip=&limit=` -> `Page[ZoneRead]` (`items`/`total`/`skip`/`limit`) — any
  authenticated tenant user.
- `GET /v1/zones/stats` -> `list[ZoneStats]` (`zone_id`/`zone_name`/`zone_number`/
  `plotted_vehicles`/`vacant_vehicles`/`busy_vehicles`/`jobs_holding`/`bookings_last_hour`/
  `street_hails_last_hour`) — any authenticated tenant user; registered server-side ahead of
  `GET /v1/zones/{zone_id}` specifically so `"stats"` isn't captured as a zone-id path segment.
- `POST /v1/zones/{zoneId}/plot` / `POST /v1/zones/unplot` -> `ZonePlotRead` (`shift_id`/
  `driver_id`/`vehicle_id`/`plotted_zone_id`/`plotted_at`) — any authenticated tenant user, acting
  on their own currently-open shift (identity-scoped server-side via the bearer token, no
  `driver_id`/`shift_id` sent from the device); `plot` 404s on an unknown zone, 409s
  (`NoActiveShiftError`) if the caller has no open shift.
- Admin zone CRUD (`POST`/`PUT`/`DELETE /v1/zones`) is owner/admin-only and deliberately NOT
  exposed on this device — a driver's tablet only ever reads the zone list/stats and plots itself.
- **One real gap found and closed while tracing the "currently plotted in" indicator:** `GET
  /v1/zones` returns `ZoneRead` rows with no per-caller plotting field, and neither does any other
  zones endpoint on a bare read — the only way to learn the calling driver's own current plot state
  is a side-effecting `plot`/`unplot` call, or reading it off their own `Shift` row. Confirmed
  `backend/app/schemas/shift.py`'s `ShiftRead` (used by the pre-existing `GET /v1/shifts/{shift_id}`,
  any authenticated tenant user, not previously called from this app) carries `plotted_zone_id`/
  `plotted_at` — added `ApiService.getShift`/`ShiftRepository.getShift` for exactly this read; see
  `PlotZoneViewModel.refresh`'s doc for how it's used (a best-effort second read after the zone
  list loads, degrading to "unknown/not plotted" on failure rather than blocking the zone list).

**What changed:**
- **New `data/remote/ApiService.kt` surface:** `ZoneDto`/`ZoneListResponseDto`/`ZonePlotReadDto`/
  `ZoneStatsDto` (field-for-field mirrors of the backend schemas above — `centerLat`/`centerLng`/
  `radiusM` are plain `Double`, not decimal-as-string, since the backend models them as Pydantic
  `float` not `Decimal`, unlike this file's money-field convention), `listZones`/`zoneStats`/
  `plotIntoZone`/`unplotZone` methods, plus `getShift` and two new nullable fields on `ShiftDto`
  (`plottedZoneId`/`plottedAt`, defaulted null — every existing `ShiftDto(...)` call site, including
  `RemoteBackedShiftRepository`'s synthetic-offline-shift fallback, keeps compiling unchanged).
- **New `domain/ZonesRepository.kt`** (`ZonesRepository`/`RemoteBackedZonesRepository`) — thin
  network-only `Result<T>` wrapper, same shape as `domain/JobsRepository.kt` (no Room/offline-queue:
  like a job offer, "plot me into zone X" and the live stats table are only ever meaningful right
  now, no "plot me in from 20 minutes ago" story to build). `domain/ShiftRepository.kt` gained
  `getShift` alongside the pre-existing `startShift`.
- **`data/AppContainer.kt`** — new `zonesRepository: ZonesRepository by lazy { RemoteBackedZonesRepository(apiService) }`,
  registered per the file's own "how a sibling agent registers a new repository" convention.
- **New `ui/screens/zones/` package:**
  - `PlotZoneViewModel.kt`/`PlotZoneScreen.kt` — the Plot screen: a list of zones (name/number,
    tap a row to plot in), a "currently plotted in: X" card with an Unplot action, matching the
    task brief's spec exactly. Styled with the same Back-header + `WheelColors` card pattern
    `ui/screens/tripdetail/TripDetailScreen.kt` already uses for a screen reached the same way
    (small entry point off the dashboard, not part of the original S1-S6 flow). A "Stats" link
    in the header navigates to the Statistics screen.
  - `ZoneStatisticsViewModel.kt`/`ZoneStatisticsScreen.kt` — the Statistics screen: a table of
    zones and their live stats (plotted/vacant/busy vehicles, jobs holding, bookings/street-hails
    last hour), horizontally scrollable (7 columns). Auto-refreshes every 20s while visible —
    **reused `SettingsViewModel`'s exact existing coroutine polling pattern**
    (`viewModelScope.launch { while (isActive) { poll(); delay(interval) } }`), per the brief's
    explicit instruction not to invent a new refresh mechanism. "While visible" falls out of
    ViewModel lifecycle for free — this screen has its own back-stack entry with its own
    `viewModelScope`, so navigating back cancels the loop with no explicit start/stop wiring.
- **`ui/navigation/CabDispatchNavHost.kt`** — two new routes, `CabDispatchRoutes.PLOT_ZONE`
  (`"plot_zone"`) and `CabDispatchRoutes.ZONE_STATISTICS` (`"zone_statistics"`), registered as
  ordinary `composable(...)` entries taking just `navController`, same shape as every other
  small off-dashboard screen (`TRIP_DETAIL`, `PROFILE`, etc.).
- **`ui/screens/dashboard/WheelDashboardScreen.kt`** — `TopStatusStrip` gained a required
  `onZonesClick: () -> Unit` parameter and a new "Zones" clickable row at the start of its
  existing GPS/4G/Printer/Batt chip `Row`; the one call site now passes
  `{ navController.navigate(CabDispatchRoutes.PLOT_ZONE) }`. This is the only change to this file
  — re-read fresh immediately before editing per this pass's own instruction (several sibling
  agents were touching it concurrently) and found the "Set Price"/`negotiatedTotal`/
  `SetPriceEntryScreen` changes already present; left completely untouched.

**Genuinely unverified, same standing caveat as every entry in this file — this machine has no
Android SDK either:** never run through `kotlinc`. Specific risks to check first on a real build,
in rough order of likelihood:
1. `ApiService.plotIntoZone`/`unplotZone` are bodyless `@POST`s (no `@Body` parameter) — modeled
   directly on `ApiService.logout()`'s existing identical shape (also a bodyless `@POST` with no
   params), so this should be safe Retrofit surface, but it's the first *parameterized* bodyless
   POST in this file (`plotIntoZone` still has a `@Path`) — worth a specific look if either 4xxs
   unexpectedly with a body-related error.
2. `PlotZoneViewModel`'s `shiftId` is a one-shot `SessionHolder.session.value?.shiftId` read at
   construction, same convention `AvailableTripsWheelViewModel`'s `driverId` property already
   uses — fine given this screen is only reachable once a driver session exists, but if a future
   pass makes Plot reachable earlier in the flow this assumption needs revisiting.
3. `ZoneStatisticsScreen`'s table is a fixed-width `Column` (`TABLE_WIDTH_DP`, 7 columns) inside a
   `horizontalScroll` wrapper, sized by eye against the app's existing 1280x800-class reference
   canvas ratio (see the 2026-08-02 LED-digit entry below for the same "sized by eye, not seen on
   a real device" caveat) — has never been measured against a real tablet's actual pixel width.
4. `ShiftDto` gaining two new nullable fields is additive/backward-compatible by construction
   (every existing named-arg `ShiftDto(...)` call site was checked and keeps compiling), but this
   is the only place in this pass that changes a DTO shared with the shift-open/shift-report flow
   rather than adding purely new surface — worth a specific look if `RemoteBackedShiftRepository`'s
   offline-shift-start fallback path shows anything unexpected around the new fields.

## 2026-08-03 (newest, reconciliation + fixes) — Blueprint gap-closing pass verified; two real bugs found and fixed

A 9-agent pass (4 backend, 2 dashboard, 3 Android) closed the remaining Taxi Meter SaaS Complete
Blueprint gaps: duress Twilio Voice escalation + audio recording, trip dispute flagging + new
payment methods (voucher/account/split-fare), tariff presets + auto-suggest, driver/vehicle
accreditation-expiry tracking, and this ambient live-position heartbeat. Rather than trust each
agent's self-report, this entry documents an independent verification pass done afterward — same
standard as every prior pass in this project: real backend test run, real migration-apply against
a scratch sqlite DB, live curl/browser exercise of the new endpoints and dashboard UI, and a
manual cross-file reconciliation read of everything the 3 concurrent Android agents touched.

**Backend — fully verified live, not just "tests pass":** 395/395 pytest (391 from the workflow's
own agents + 4 new tests added below), all Alembic migrations (including the two new ones) apply
cleanly to a fresh sqlite DB via `alembic upgrade head` (this matters — one agent had already
caught and fixed a real `batch_alter_table` bug in its own migration; this confirms no similar bug
slipped through), and every new endpoint exercised for real over HTTP: `GET /v1/tariffs/presets`,
`GET /v1/tariffs/suggest` (correctly detected an airport geofence and honestly degraded since no
airport-tariff exists yet), `GET /v1/fleet/compliance-expiry` + the driver-login 403 block (set a
real expired license on the seeded demo driver, confirmed both), `PATCH /v1/trips/{id}/flag`
(422 without a reason, 200 with one, correctly filterable), and split-fare close validation
(422 on a mismatched sum). Dashboard: `npm run build` clean, then logged in as the tenant owner in
a real browser and confirmed the "Review" trip filter, the flagged-trip dispute panel (showing the
exact `review_notes` set via the API), the Fleet & Drivers compliance-expiry banner, and the
Tariff Studio preset picker (clicking "Special Event" genuinely prefilled the form) all render and
work against live data — not just that the code compiles.

**Two real bugs found during this pass and fixed (not by the original agents):**
1. **`TripSyncItem` schema gap (backend).** The dispute-payments agent had already honestly
   flagged this in `ApiService.kt`'s own doc comment rather than silently leaving it: the backend's
   `TripSyncItem` Pydantic schema (`backend/app/schemas/trips.py`) was never extended to accept
   `voucher_code`/`account_reference`/`split_payments`, even though `TripCreate`/`TripCloseRequest`
   were — and `POST /v1/trips/sync` is the ONLY network call this app's real offline-first close
   flow ever makes (`closeTrip`/`createTrip` are live Retrofit surface with no actual call site on
   this app). Result: a driver closing a trip with a voucher/account/split-fare payment would have
   that detail captured correctly on-device (Room, receipt, Trip Detail) but silently dropped the
   moment it synced — Pydantic's `extra="ignore"` neither errors nor persists unknown fields.
   **Fixed:** `TripSyncItem` now declares all three fields with the same cross-field validation
   `TripCreate`/`TripCloseRequest` already had, and `app.api.v1.trips.sync_trips` now runs the same
   voucher-redemption/account-reference/split-sum checks `close_trip` already applied to the online
   path, before persisting. New tests:
   `test_sync_voucher_payment_persists_voucher_code`,
   `test_sync_voucher_without_code_is_422`,
   `test_sync_split_fare_matching_sum_persists_split_payments`,
   `test_sync_split_fare_mismatched_sum_is_422` (all passing). The Android-side doc comment on
   `TripSyncItemDto` (`data/remote/ApiService.kt`) is updated to reflect the fix.
2. **`RealLocationProvider.kt`'s `supervisePermission()` — likely pre-existing compile error,
   unrelated to this pass but caught while cross-checking the new heartbeat agent's own honest
   flag.** The heartbeat agent (building `LivePositionHeartbeat.kt`) noticed that
   `supervisePermission()` reads a bare `isActive`, but `kotlinx.coroutines.isActive` is an
   extension property on `CoroutineScope` and this is a plain `private suspend fun` on a class that
   does NOT implement `CoroutineScope` (`RealLocationProvider : SpeedSource`) — meaning `isActive`
   there almost certainly has no implicit receiver to resolve against and would be an unresolved
   reference at real compile time. The agent correctly avoided the same mistake in its own new code
   (using `scope.isActive` throughout `LivePositionHeartbeat.kt`) but, appropriately, didn't touch
   the pre-existing file since it was out of that task's scope. Confirmed the reasoning independently
   and fixed it here: line changed to `scope.isActive` (the class already holds `scope` as a
   constructor property). **First real compile-error candidate this module has had actually fixed
   before ever reaching a compiler** — everything else in this file's history has been "reasoned
   through," this one had a specific, checkable Kotlin-semantics argument behind it.

**Android reconciliation (3 concurrent agents on overlapping files — `AppContainer.kt`,
`ApiService.kt`, `DuressController.kt`):** read every diff by hand. `AppContainer.kt` cleanly
carries both the new `livePositionHeartbeat` singleton and the new `duressAudioRecorder`-wired
`duressController` construction — no duplicate singleton, no clobbered edit.  `ApiService.kt`
cleanly carries the new `flagTrip`/`uploadDuressAudio` endpoints and the extended
`TripCreateDto`/`TripCloseRequestDto`/`TripSyncItemDto`/`TripDto` fields plus the new
`SplitPaymentEntryDto`/`TripFlagRequestDto` types — no name collisions. `LivePositionHeartbeat.kt`
(new file) read in full: correct `SessionHolder.session`/`DriverSession.shiftId`/`vehicleId` field
usage confirmed against `domain/Session.kt`'s real shape, correct `scope.isActive` usage (see bug
#2 above). Still, as always: **none of this has been run through a real Kotlin compiler** — these
two fixes are the ones a careful read could actually prove wrong; anything subtler (a genuine type
mismatch this reasoning didn't catch) is still only found by `./gradlew assembleDebug`.

## 2026-08-03 (newest) — Voucher/account/split-fare payment methods + "Dispute" button (Close & Pay / Trip Detail)

Direct request, scoped to `ui/screens/closepay/` (extend the existing payment-method picker
pattern, do not restructure the screen) plus a new "Dispute" action on Trip Detail. Backend
counterpart already existed going into this pass (a sibling backend agent's own work this same
session) — used its exact contract, not a guess: `POST /v1/trips`/`PATCH /v1/trips/{id}`/
`POST /v1/trips/{id}/close` all gained optional `voucher_code`/`account_reference`/
`split_payments` fields, `PaymentMethod` widened to `cash|card|voucher|account|split_fare`, and
`PATCH /v1/trips/{id}/flag` (body `{flagged, reason}`) is the new "Dispute" endpoint —
`backend/app/schemas/trips.py`, `backend/app/api/v1/trips.py`.

**Did NOT touch `domain/fare/FareEngine.kt`** (the golden-vector-proven one, per the task's
explicit instruction) — confirmed first, by reading it, that `close(paymentMethod: String, ...)`
only ever branches on `paymentMethod == "card"` (for the non-cash surcharge); every other string,
including the three new values, already takes the no-surcharge path with zero code changes. The
charged total (`grandTotal`) is unaffected by any of this pass's work.

**What changed:**
- **`ui/screens/closepay/CloseAndPayViewModel.kt`** — `PaymentMethodOption` gained `VOUCHER`,
  `ACCOUNT`, `SPLIT_FARE` (persisted values `"voucher"`/`"account"`/`"split_fare"`, matching the
  backend's widened `PaymentMethod` Literal exactly); new `SplitLegMethod` enum
  (`cash|card|voucher|account`, no `split_fare` — mirrors the backend's `SubPaymentMethod` Literal,
  used only for a split-fare leg's own method). `ReadyToClose` gained `voucherCode`,
  `accountReference`, `splitLegAMethod`/`splitLegAAmount`, `splitLegBMethod`/`splitLegBAmount`
  (exactly two legs, per the brief — not the backend's unbounded list) + a `splitRemaining`
  getter (mirrors `changeDue`'s pattern for Cash). `canConfirm` extended per-method (non-blank
  code/reference; both split amounts positive and summing exactly to `grandTotal`).
  `selectPaymentMethod`'s surcharge logic reclassified as a `when` over all 7 methods — voucher/
  account/split-fare are cash-like (0% surcharge), matching neither being a card swipe.
  **Caught and fixed a real bug while wiring this**: `confirmPayment()`'s dispatch `when` wasn't
  exhaustive (a statement `when`, so the compiler wouldn't have caught it) — without adding
  branches for the three new methods, tapping "Confirm & close trip" under Voucher/Account/
  Split Fare would have silently done nothing. Fixed by routing all of CASH/CABCHARGE/VOUCHER/
  ACCOUNT/SPLIT_FARE through the existing `finalizeCloseWithProcessingDelay` (none of them touch a
  real payment gateway). `finalizeClose`/`buildReceipt` updated to pass/print the new fields.
- **`ui/screens/closepay/CloseAndPayScreen.kt`** — three new sub-screens matching the existing
  Cash/CabCharge sub-screen pattern exactly (`BackHeader` + `FareSummaryHeader(compact=true)` +
  a `WheelCard` + `PrimaryPayButton`): `VoucherEntryScreen` (one text field), `AccountEntryScreen`
  (one text field), `SplitFareEntryScreen` (two `SplitLegMethodSelector` chip-rows + amount fields
  + a "remaining to allocate" card, same visual language as Cash's "change due" card).
  `MethodPickerScreen` gained three more `SecondaryPayButton`s. No change to `ReadyToCloseContent`'s
  overall shape — same `PaymentSubScreen` enum + `when`-dispatch pattern, three more cases.
- **`data/local/entity/TripEntity.kt`** — three new nullable columns: `voucherCode`,
  `accountReference`, `splitPaymentsJson` (JSON-encoded `List<SplitPaymentEntryDto>`, same
  raw-blob convention `gpsTraceJson` already uses). `data/local/AppDatabase.kt` version bumped
  3 -> 4 (no Migration, same "still pre-release, no installed base" reasoning already documented
  there for the 1->2 and 2->3 bumps — do NOT copy that pattern once this ships for real).
- **`data/repository/TripRepository.kt`** — `closeTrip()` gained `voucherCode`/`accountReference`/
  `splitPayments` params (all optional, defaulted null — every existing call site, including the
  JVM test in `OutboxDrainerTest.kt`, keeps compiling unchanged), persisted onto the new
  `TripEntity` columns. `toSyncItemDto()` passes them through to the new `TripSyncItemDto` fields.
- **`data/remote/ApiService.kt`** — new `SplitPaymentEntryDto`, `TripFlagRequestDto`; new
  `flagTrip()` endpoint (`PATCH /v1/trips/{tripId}/flag`); `TripCreateDto`/`TripCloseRequestDto`/
  `TripSyncItemDto`/`TripDto` all gained the matching fields, mirroring the backend's widened
  schemas field-for-field (payment_method comments updated everywhere they appeared).
- **`ui/screens/tripdetail/TripDetailViewModel.kt`/`TripDetailScreen.kt`** — the "Dispute" button.
  Checked `ui/screens/hired/` first per the brief and confirmed it's the wrong place (S3/HIRED only
  ever shows the live in-progress trip, never a closed one) — Trip Detail (reached from the Trips
  wheel-slot content, already existed) is the real trip-history surface. New `DisputeSubmitState`
  enum + `disputeReason`/`disputeState`/`disputeError` on `TripDetailUiState.Loaded`;
  `submitDispute()` calls `ApiService.flagTrip` with two client-side gates before it ever touches
  the network: a non-blank reason (mirrors the backend's 422) and a non-null
  `TripEntity.serverId` (a trip shown here can be closed-but-not-yet-synced — see
  `TripDao.observeRecentTrips`'s own doc — and there's no server-side trip id to flag until
  `SyncWorker` confirms one; this screen surfaces that as an explicit "hasn't synced yet" message
  rather than a confusing network error). `TripDetailScreen.kt` wrapped its body in
  `verticalScroll` (wasn't scrollable before; the new Dispute card made that latent overflow risk
  real) and added a `DisputeSection` composable matching the existing card visual language.
  `domain/format/TripDisplayFormat.kt#asPaymentMethodLabel` extended for the three new values (used
  by both Trip Detail and the Trips history rows).

**Known gap, flagged loudly rather than silently assumed fixed — read this before debugging why a
voucher code "disappeared":** this app's *actual* live network path for closing a trip is
`POST /v1/trips/sync` (the offline outbox drain, `SyncWorker`/`TripRepository`'s own "never awaits
a network call" rule) — `ApiService.createTrip`/`closeTrip` are live Retrofit contract surface that
mirror the backend 1:1 but have **no call site** anywhere in this app, same as before this pass.
The backend's `TripSyncItem` Pydantic schema (`backend/app/schemas/trips.py`) was **not** extended
by the sibling backend pass to carry `voucher_code`/`account_reference`/`split_payments` — only
`TripCreate`/`TripUpdate`/`TripCloseRequest` were. So: `voucherCode`/`accountReference`/
`splitPayments` are captured correctly, persisted correctly to Room, and shown correctly on the
in-app receipt and Trip Detail — but when the trip syncs to the server, Pydantic's default
`extra="ignore"` behaviour silently drops those three fields (no error, no crash) while
`payment_method` itself (e.g. `"voucher"`) still syncs and persists fine. `TripSyncItemDto` sends
them anyway (harmless, forward-compatible) with a loud doc comment explaining exactly this, so a
future backend pass that extends `TripSyncItem` needs zero Android-side change. This is the same
class of gap `PaymentMethodOption.CABCHARGE`'s own pre-existing doc already flags for docket
richness — not a new pattern, just a new instance of it.

**Genuinely unverified, same standing caveat as every entry in this file:** none of this has ever
been run through `kotlinc`. The riskiest single thing to check first on a real build: the
`SplitLegMethod.entries` usage in `SplitLegMethodSelector` (Kotlin 1.9+ `entries` — already used
elsewhere in this module, e.g. `WheelSlot.entries`/`ProfileTab.entries` in
`WheelDashboardScreen.kt`/`ProfileScreen.kt`, so this should be safe, but it's the one new-to-this-
pass stdlib API surface touched). Everything else in this pass is the same well-established
Compose/Retrofit/Room/kotlinx.serialization surface every prior pass in this file has already used
successfully.

## 2026-08-03 (newest) — Duress audio recording, wired into the existing Active-phase state machine

Direct request, closing the "optional audio recording" gap in Blueprint §4.3/§8.3. The backend
counterpart already existed going into this pass (a sibling backend agent's own work this same
day): `POST /v1/duress/{event_id}/audio` (multipart `file` field, returns `DuressEventRead`) and
`GET /v1/duress/{event_id}/audio` (playback, not consumed by this device) —
`backend/app/api/v1/duress.py`/`backend/app/services/duress.py`. Used that exact contract, not a
guess.

**What changed:**
- **New `domain/duress/DuressAudioRecorder.kt`** — a real `android.media.MediaRecorder` wrapper,
  MPEG-4 container + AAC encoder (`.m4a`, matching the backend's own doc-comment example path).
  `start(eventId)`/`stop()` are both `suspend fun`s (`withContext(Dispatchers.IO)` internally,
  since `MediaRecorder.prepare()`/`start()`/`stop()`/`release()` are blocking calls). Permission
  handling mirrors `domain/location/RealLocationProvider.kt`'s exact graceful-degradation pattern
  (read that file first if you haven't — this class's doc points at it explicitly): `start()`
  checks `RECORD_AUDIO` via `ContextCompat.checkSelfPermission` and returns `false` (a silent
  no-op) if ungranted, never throws, and never crashes the duress state machine either way. Unlike
  `RealLocationProvider` this class does not itself poll for a later grant — see its doc for why
  that's a deliberate difference, not an oversight (a duress Active phase is one bounded lifecycle,
  not a process-lifetime subscription). `MediaRecorder(Context)` (API 31+) vs. the deprecated
  no-arg constructor (this project's minSdk 29) is branched on `Build.VERSION.SDK_INT`, same
  constraint `RealLocationProvider`'s doc already flags elsewhere in this module (the Ed25519/API
  level note).
- **Documented simplification, exactly as this task's brief pre-authorized:** Blueprint §4.3/§8.3
  literally says "a 60-second circular buffer". This implementation is the simpler alternative the
  brief explicitly sanctioned instead — record into a single file for up to
  `DuressAudioRecorder.MAX_RECORDING_DURATION_MS` (60s) and then stop, not a true rolling buffer
  that retains only the trailing 60 seconds of a longer recording. A real circular buffer needs
  either chunked/stitched short-segment recording or a raw PCM ring buffer + manual encoder pass —
  meaningfully more moving parts than a capped single `MediaRecorder` session, for code that has
  never been run through a compiler let alone a device mic. If a genuine rolling buffer is wanted
  later, `DuressAudioRecorder` is the one file to rework; nothing else needs to change (the
  60s-cap enforcement lives in `DuressController.runActivePhase`, not in this class, precisely so
  swapping the recording strategy doesn't touch the state machine — see below).
- **`domain/DuressController.kt`** — new optional constructor param `audioRecorder:
  DuressAudioRecorder? = null` (nullable/defaulted the same way `locationProvider` is nullable, for
  the same "no real Context in a test/preview construction" reason — see that class's own doc,
  now with a new "Audio recording" section). **Did not duplicate the state machine, per the task's
  explicit instruction** — `runActivePhase` (the existing GPS-relay/dispatcher-resolution poll
  loop) is the only place that calls into `DuressAudioRecorder`:
  - Starts recording the moment a real event id is known inside that same function (which, per
    the existing trigger-retry timing already documented on this class, is almost always the same
    moment `Active` itself is reached — see the new doc comment for the one honest edge case where
    it isn't: still fully offline at that point).
  - Every poll iteration (already running every 5s via `ACTIVE_POLL_INTERVAL_MS`) checks elapsed
    time against `MAX_RECORDING_DURATION_MS` and stops+uploads once it's past — no new timer/job,
    reuses the loop that was already there.
  - Also stops+uploads (if not already) the moment the loop sees the event reach a terminal
    status — so a duress event resolved in under 60s still gets whatever was captured, not just
    the full-60s-or-nothing case.
  - New private `stopAndUploadAudio(eventId)` helper: `audioRecorder?.stop()` (a no-op/`null` if
    nothing was recording — safe to call from both the cap-elapsed and terminal-status paths even
    though at most one of them actually has anything to stop) then
    `repository.uploadAudio(eventId, file)`, best-effort, same swallow-and-continue convention as
    every other network call already in this function.
- **`domain/DuressRepository.kt`** — new `uploadAudio(eventId, file): Result<DuressEventDto>` on
  the interface + `RemoteBackedDuressRepository` impl (builds a `MultipartBody.Part` via OkHttp's
  `File.asRequestBody(...)`, `"audio/mp4"` content-type — a reasonable label, not load-bearing,
  since the backend reads the upload generically via FastAPI's `UploadFile` and never inspects
  `content_type`, per that endpoint's own contract notes).
- **`data/remote/ApiService.kt`** — new `@Multipart @POST("/v1/duress/{eventId}/audio")
  uploadDuressAudio(eventId, file: MultipartBody.Part): DuressEventDto`. First `@Multipart`
  endpoint in this file — every other upload-shaped concern in this app so far has been JSON
  bodies only, so this is a genuinely new Retrofit annotation combination for this codebase (low
  risk: it's standard, well-documented Retrofit surface, not a Mapbox-SDK-shaped "might not match
  the real API" risk). `DuressEventDto` already had `audio_ref` from the original duress pass —
  no DTO change needed there.
- **`data/AppContainer.kt`** — new `duressAudioRecorder: DuressAudioRecorder by lazy {
  DuressAudioRecorder(appContext) }`, threaded into the existing `duressController` construction.
  No new `CoroutineScope` needed (unlike `speedSource`/`duressController` themselves) — the
  recorder has no supervising loop of its own to run, it's driven entirely by
  `DuressController.runActivePhase`'s existing scope/loop, per the "don't duplicate the state
  machine" instruction.
- **`AndroidManifest.xml`** — added `android.permission.RECORD_AUDIO` +
  `<uses-feature android:name="android.hardware.microphone" android:required="false" />` (mirrors
  the existing optional-camera-feature pattern just above it for QR pairing). No runtime-request
  flow wired anywhere yet — same standing gap this file already documents for
  `ACCESS_FINE_LOCATION`/`CAMERA` (grep for `ContextCompat.checkSelfPermission`, every hit today
  only checks, never requests); ungranted `RECORD_AUDIO` degrades to "no audio captured, everything
  else in the duress flow unaffected", never a crash, per the class's own doc.

**Not done, flagged rather than silently left implicit (none of this has ever been run through a
compiler — same standing caveat as every entry in this file):**
- The circular-buffer simplification above.
- The "still fully offline when Active is reached" audio-start gap above (rare in practice given
  the existing trigger-retry timing, but real).
- `MediaRecorder.stop()`'s well-known platform quirk (`RuntimeException` when stopped with no
  valid data ever written, e.g. near-instant start/stop) is caught with `runCatching` and treated
  as "nothing to upload" via the file-length check in `DuressAudioRecorder.stop()` — reasoned
  through, not verified on a real device/emulator.
- No UI surface for a driver to see "recording in progress" — the brief didn't ask for one, and
  Blueprint §4.3/§8.3 doesn't call for on-screen recording-indicator UI either (arguably correct
  for a safety feature that shouldn't visibly announce itself mid-duress); flagging in case that
  reading is wrong.
- Playback (`GET /v1/duress/{event_id}/audio`) is not called from this app — it's a
  dispatcher/dashboard-side concern per the backend contract, out of scope for the driver device.

## 2026-08-03 (same day as the duress-audio pass above) — Ambient live-position heartbeat while on shift (Blueprint §6.2.2)

Direct request, closing a real gap this file has flagged in three separate places (the MDM
"locate" entry below, its own "Medium priority" gap list entry, and the GPS-core reconciliation
pass's own gap list): `POST /v1/fleet/positions` (`ApiService.publishPosition`) was only ever
called *reactively*, in response to an admin's MDM "locate" request
(`SettingsViewModel.respondToLocateRequest`, itself only checked once, whenever S6/Settings
happens to be opened) — a dispatcher watching the fleet dashboard's Live Map saw no moving dot for
a driver just driving around normally. The Taxi Meter SaaS Complete Blueprint's own WebSocket spec
(§6.2.2) literally specifies `vehicle.heartbeat -> Every 30 seconds: GPS, status, battery` as an
ambient event while a vehicle is on shift; nothing implemented that until now.

**What changed:**

- **New `domain/LivePositionHeartbeat.kt`.** Mirrors `domain/DuressController.kt`'s shape (a
  process-lifetime `AppContainer` singleton, own `SupervisorJob`-backed `CoroutineScope`, not tied
  to any screen's ViewModel scope) but is *self-supervising* rather than externally
  trigger()/cancel()-driven: `start()` (called exactly once, from `AppContainer.init()`) launches a
  coroutine that collects `SessionHolder.session` for the process lifetime and starts/stops its own
  30s publish loop accordingly — the same self-supervising "poll/observe a condition, start/stop a
  child job" shape `domain/location/RealLocationProvider.kt`'s `supervisePermission` already uses
  for its own permission-gated start/stop (there: location permission; here: a shift being open).
  This means **no screen or ViewModel needed any change** to make this work — `LoginVehicleBindViewModel.startShift`
  (sets `SessionHolder.session` with a real `shiftId`) and `ShiftSubmittedScreen`'s DONE button
  (`SessionHolder.clear()`) already existed and already are this class's only two triggers; it just
  had nothing observing them for this purpose before.
- **"On shift" signal:** `SessionHolder.session.value?.shiftId != null` — the same field
  `ui/screens/shiftreport/ShiftReportViewModel.kt` already treats as "is there really an active
  shift" (see that class's `init`). Deliberately gated on shift state, not on the separate,
  still-unwired "For Hire"/availability toggle (see this file's "Availability broadcast not wired"
  gap below) — the blueprint's own wording is "while a vehicle is on shift", an ambient presence
  signal, not "while marked available for offers"; a driver on shift but on a break or mid-trip
  should still show up somewhere on the Live Map, not vanish from it.
- **Publishes `AppContainer.speedSource.locationFix`** (the real fused-GPS feed from the
  2026-08-03 GPS-core pass, same feed `SettingsViewModel.respondToLocateRequest` already reads)
  through the existing `ApiService.publishPosition` — no new backend endpoint, no new DTO. Skips
  silently (not an error) whenever there is no fix yet, same reasoning as
  `respondToLocateRequest`. `status` is published as a fixed `"unknown"` placeholder for the same
  reason that method's own placeholder exists: this app has no other real-time
  available/on-trip/break signal a process-lifetime singleton can read yet. Blueprint's line also
  names "battery" alongside GPS/status — **not sent**, flagged rather than silently dropped:
  `PositionPublishRequestDto` has no battery field at all (the backend's `PositionPublishRequest`
  doesn't carry one), so this cannot honestly be added without a backend/DTO change first.
- **`AppContainer.kt`** gained the `livePositionHeartbeat` singleton (constructed with `apiService`
  + `speedSource`, own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, same pattern as
  `duressController`/`speedSource` above it) and one new line in `init()`,
  `livePositionHeartbeat.start()` — unlike every other `by lazy` singleton in that file, nothing
  else in the app ever needs to reference this property by name for it to do its job, so it has to
  be forced eagerly in `init()` rather than left to whenever some screen happens to first touch it
  (the same reasoning `tariffSigningKeyCache`'s warm-up call already documents for itself).
  `data/remote/ApiService.kt`'s `PositionPublishRequestDto` doc comment updated to name this as a
  third call site (was "two call sites").
- **Softens, but does not fully close, the MDM-locate gap documented below and in this file's
  "Medium priority" list:** `respondToLocateRequest` itself is unchanged — `locate_requested` is
  still only read/acted on once, whenever S6/Settings happens to be opened. What changes in
  practice: any device that's on shift now publishes a fresh position every 30s regardless of
  whether an admin ever asked, so a dispatcher watching Live Map sees a live-ish dot on its own
  within half a minute of shift start, without needing a locate request to be answered at all. The
  specific mechanic those other entries describe (the `locate_requested` flag itself only getting
  acknowledged when S6 opens) is still real and still unchanged by this pass — left as-is rather
  than silently implied fixed.

**Genuinely unverified, flagged loudly (same standing caveat as every entry in this file — this
machine has no Android SDK either):** never run through `kotlinc`. The riskiest single line, by
eye, is `SessionHolder.session.collect { session -> ... }` inside `LivePositionHeartbeat.start()` —
relies on the trailing-lambda-to-`FlowCollector` SAM conversion (`FlowCollector` is a `fun
interface`) resolving against `Flow<T>`'s member `collect(FlowCollector<T>)` the same way
`WheelDashboardViewModel.kt`'s existing `region.collect { r -> ... }` already does in this exact
codebase — if that assumption is wrong for some reason this pass didn't anticipate, this is the
first place to look. Everything else in the new file deliberately avoids the one other real risk
this pass noticed while reading `RealLocationProvider.kt` for reference: that file's
`supervisePermission()` is a plain `private suspend fun` (no `CoroutineScope` receiver) that reads
a bare `isActive` — `kotlinx.coroutines.isActive` is declared as an extension property on
`CoroutineScope`, and a plain suspend function with no such receiver in scope has no implicit
receiver for it to resolve against, which reads like a real "unresolved reference: isActive" risk
in that existing file. Not touched here (out of this pass's scope — the brief was the new
heartbeat, not auditing/fixing `RealLocationProvider.kt`), but flagged here rather than silently
copied: `LivePositionHeartbeat.kt`'s own `publishLoop`/`publishOnce` use `scope.isActive` with an
explicit receiver everywhere instead, which resolves unambiguously regardless of implicit-receiver
rules. Whoever hits real compile errors in `RealLocationProvider.kt`'s `supervisePermission` should
look at this same fix (either give it a `CoroutineScope` receiver or use `scope.isActive`/`while
(true)` with cancellation propagating through `delay()` instead, the way `DuressController`'s own
poll loops already do).

## 2026-08-03 (newest) — Dashboard restructured into a permanent split panel: wheel fixed right, all content left

Direct request, working from a Gemini-generated reference HTML (`Dispatch Tablet - Exact Metallic
Meter Wheel` — a 42%/58% grid, static wheel dial on the right, a content panel on the left that
swaps per selected sector). The ask: the wheel should show *permanently* on the right side, and
"all data and pages or screens" should show on the left — not the previous layout, where the
wheel, identity card, quick-stats card, status card, and the active slot's content pane were each
independently `.align(...)`-positioned floating cards over the full-bleed map background (visually
similar in spirit, since the wheel already defaulted to `CenterEnd`, but never a real structural
guarantee — nothing stopped a future change from drawing something else on top of it).

**What changed, all in `ui/screens/dashboard/WheelDashboardScreen.kt`:**
- The screen's body is now a real `Row` split: `LeftContentPanel` (`weight(0.42f)`, matching the
  reference's left-panel proportion) and `RightWheelPanel` (`weight(0.58f)`) — each
  `fillMaxHeight()`, both sitting below a full-width `TopStatusStrip` header. The live map
  background is deliberately KEPT full-bleed behind both panels (spec §5: "nothing is ever a
  blank screen" — this reference has no map at all, but removing it wasn't part of the actual
  ask), with both panels using a semi-opaque `WheelColors.surfaceRaised` background so map imagery
  underneath doesn't fight with card/wheel readability.
- **`LeftContentPanel`** consolidates what used to be four independently-floating overlays
  (`IdentityCard` top-left, `QuickStatsCard` top-right, `StatusCard`, the slot `ContentPane`) into
  one panel: identity + quick-stats as a `SpaceBetween` header row (preserving their original
  left/right relative positions, just now scoped inside this one panel instead of the whole
  screen), then `StatusCard`, then the active wheel slot's real content (`ContentPane`, via the
  exact same `wheelSlotContentProviderFor` this screen already used — no slot-content composable
  itself needed any change) filling the rest of the panel's height via `weight(1f)` instead of the
  old fixed `heightIn(max = 380.dp)` cap. `bottom = 130.dp` padding on the panel keeps its content
  clear of `StartMeterButton`, which still floats over BOTH panels at the whole screen's
  `Alignment.BottomCenter` (a global action, not scoped to either panel).
- **`RightWheelPanel`** is just `WheelArea` centered in its own dedicated region — this is what
  makes "always visible on the right" an actual structural guarantee (nothing else can ever render
  in that Row cell) rather than an artifact of one Z-order among several overlays. Centered, not
  bottom-anchored — the request offered "bottom or centre right" as options; centered was chosen as
  the smaller change from the previous `CenterEnd` default. **Flagged for whoever picks this up:**
  swapping to bottom-anchored is a one-line change (`RightWheelPanel`'s `Box`'s
  `contentAlignment = Alignment.Center` → `Alignment.BottomCenter`) if bottom-right is preferred
  instead once someone can actually see this on a device.
- No wheel-slot content composable (`AvailableTripsWheelContent`, `MessagesWheelContent`, etc.)
  needed any change — they all still just implement `WheelSlotContentProvider.Body()`, which
  `ContentPane` renders identically to before (same `Box(...).verticalScroll(...)` wrapper, just
  sized by `weight(1f)` against the panel's real available height instead of a fixed 380dp cap).
- **Genuinely unverified, flagged loudly:** this is a real layout restructuring (a `Row` with two
  `weight`-based panels replacing several `Box`-scoped `.align(...)` overlays) that has never been
  measured on a real screen. The two biggest real risks to check first on a device: (1) whether
  `WheelGeometry.WHEEL_DIAMETER_DP` (the fixed wheel size) actually fits comfortably inside
  `RightWheelPanel`'s `weight(0.58f)` region on whatever real tablet this runs on — if the wheel
  visually crowds or clips against the panel edge, either shrink `WHEEL_DIAMETER_DP` or shift the
  `0.42f`/`0.58f` split; (2) whether `LeftContentPanel`'s `130.dp` bottom-padding reservation is
  actually enough clearance from `StartMeterButton` at whatever real device height this renders at
  — both were sized by reading the numbers, not by seeing them rendered.

## 2026-08-03 (reconciliation pass) — GPS core + map/region + driver-PIN + admin-PIN/tariff-signing + MDM-locate cross-checked

A core GPS-provider agent (`domain/location/RealLocationProvider.kt`, the `SpeedSource.locationFix`
contract) and 4 sibling feature agents (map/region GPS wiring, driver-PIN login, admin-PIN +
Ed25519 tariff-signature verification, MDM "locate") had all built against each other's contracts
in sequence/parallel, per this file's own entries below, without ever compiling against each
other. This pass's job was specifically to catch the naming/signature drift that produces — the
"most likely failure mode" this file's intro paragraph warns about.

**Unusual advantage this pass had that prior reconciliation passes didn't:** this checkout is a
real git repository with the pre-existing code committed at HEAD, so instead of re-reading every
file cold, every file this pass touched was diffed against HEAD directly (`git diff`) — a much
more precise way to see exactly what each agent actually changed than re-deriving it by eye.

**What was checked, and the result — no naming/signature mismatch was found anywhere:**

- **`SpeedSource`/`LocationFix` contract** (`domain/FareEngine.kt`, defined by the core GPS pass)
  vs. every consumer: `domain/location/RegionResolver.kt` (`resolve(fix: LocationFix?)` /
  `resolve(lat: Double?, lng: Double?)` — both overloads used correctly, and
  `WheelDashboardViewModel.kt` deliberately spells out the lambda rather than a method reference
  specifically to avoid ambiguity between the two), `WheelDashboardScreen.kt`'s `MapBackground`/
  `RealMapboxMapView` (`fix: LocationFix?` parameter threaded through consistently, including the
  new `cameraFor(fix)` helper), `AvailableTripsWheelViewModel.kt` and
  `AvailableTripOfferViewModel.kt` (both call `AppContainer.speedSource.locationFix.value` and
  `RegionResolver.resolve(...)` identically), and `SettingsViewModel.kt`'s
  `respondToLocateRequest()` (reads the same `.locationFix.value`, publishes `fix.lat`/`fix.lng`
  against `PositionPublishRequestDto`'s matching `lat`/`lng` fields). Every call site agrees with
  what `RealLocationProvider`/`domain/FareEngine.kt` actually built — no stale guess anywhere.
- **`StubSpeedSource`** — confirmed still defined in `domain/FareEngine.kt`, NOT deleted.
  `AppContainer.speedSource` no longer constructs it directly (now `RealLocationProvider`), but
  it's kept exactly as every prior entry documents: the explicit fallback for tests/previews, and
  `RealLocationProvider` itself reproduces the identical observable shape (`locationFix = null`,
  `speedKmh = 0.0`) when ungranted/no-fix, so nothing that used to rely on the stub's shape
  regresses.
- **The golden-vector fare engine (`domain/fare/FareEngine.kt`) — confirmed byte-for-byte
  untouched.** `git diff` against HEAD returns nothing for this file, its test
  (`FareEngineTest.kt`), or anything downstream of it that turns a live trip into a charged amount
  (`domain/fare/TripFareReconstruction.kt`, `domain/fare/TariffMapper.kt`,
  `ui/screens/closepay/CloseAndPayViewModel.kt` — all zero-diff too). The GPS core pass's only
  change to the *live* UI engine (`domain/FareEngine.kt`, the tick-by-tick display one — see this
  file's Step 1 for why there are deliberately two) was additive: `SpeedSource` gained
  `locationFix` alongside the pre-existing `speedKmh`, and `FareEngineImpl.tick()` still reads only
  `speedSource.speedKmh.value` exactly as before. This is the one regression that would have
  actually been dangerous (a miscalculated fare) — confirmed it did not happen.
- **`ApiService.kt`'s DTOs, cross-checked against the actual backend** (not just the Android side
  — `driver_code`/`pin` for driver-login, `configured`/`valid` for admin-PIN,
  `public_key`/`algorithm` for the signing key, `PositionPublishRequestDto`'s `vehicle_id`/`lat`/
  `lng`/`status`, and every URL path) against `backend/app/api/v1/{auth,fleet,tariffs,live_ops}.py`
  and `backend/app/schemas/{auth,fleet,tariffs,live_ops}.py` — every field name, type, nullability,
  and path matches exactly. `security/TariffCanonicalPayload.kt`'s field order/rate-quantization
  also line up field-for-field with `backend/app/services/tariff_signing.py`'s
  `canonical_tariff_payload`/`RATE_FIELDS`/`_fmt_rate` — the one residual risk already flagged by
  that file's own doc (the `effective_from`/`effective_to` byte-format assumption) still stands,
  since it genuinely can't be checked without a real signed payload from a real running backend.
- **`AppContainer.kt`/`AppDatabase.kt` wiring** — `tariffSigningKeyDao`/`tariffSigningKeyCache`
  registered once each, `AppDatabase` version 2→3 with `TariffSigningKeyEntity` in both the
  `@Database(entities = [...])` list and its own `abstract fun`, no duplicate singletons, the
  removed `tariffSignatureVerifier` singleton has zero remaining call sites (confirmed via
  project-wide grep — `TariffCache` now constructs `Ed25519TariffSignatureVerifier` itself, per
  that class's own doc explaining why it's no longer a singleton).
- **No dangling references** to anything a sibling deleted: `ADMIN_PIN_PLACEHOLDER`,
  `FARE_SCHEDULE_REGION`, and the hardcoded `DEFAULT_REGION` constant are all gone from every
  *live* call site (grepped project-wide) — the only remaining `DEFAULT_REGION` is in the already-
  dead, unreferenced `ui/screens/idle/IdleViewModel.kt` (superseded by `WheelDashboardViewModel`,
  see the 2026-08-01 entry below), correctly left alone rather than edited for no reason.

**Still not fully confident about — same standing caveat as every entry in this file:** none of
this has ever been run through `kotlinc`. This pass is a careful `git diff` + cross-file textual
trace, not a compiler-verified one, so a real type mismatch, missing import, or Compose API misuse
this manual pass didn't spot by eye is still possible — `./gradlew assembleDebug` (Step 0) is still
the first real test. The specific already-flagged risks below are unchanged by this pass (it
didn't attempt to close any of them, only to confirm no *new* cross-agent drift was introduced):
the tariff canonical-payload datetime-format assumption (`TariffCanonicalPayload.kt`'s own doc),
the Mapbox Maps SDK v11 offline-region API surface (`MapboxOfflineRegion.kt`'s own doc, still the
single highest-risk file in this module), and the MDM-locate/admin-PIN gaps each of those passes'
own entries already documented (locate only answered when S6 happens to be opened; no periodic
background heartbeat exists yet).

**What's left, in priority order (unchanged by this reconciliation pass, restated here for a
single up-to-date list):**

- **Hardware-dependent, reasonable to leave stubbed until a physical pilot:**
  `hardware/payments/CardPaymentGateway.kt` (Stripe Terminal Tap-to-Pay), `hardware/printing/
  ReceiptPrinterGateway.kt` (BT thermal printer), `hardware/receipt/{Sms,Email}ReceiptGateway.kt`.
  Real interfaces, mock/no-op implementations — don't fake these into "working" without real
  hardware or a real Stripe key to test against.
- **Offline-region-download risk flag:** `data/remote/MapboxOfflineRegion.kt` — written from a
  general understanding of the Mapbox Maps SDK v11 `TileStore`/`OfflineManager` API shape, not
  verified against a current release; the highest-risk unverified file in this module (see its own
  doc and the 2026-08-02 "Real offline maps" entry below before debugging it).
- **`reboot_requested`:** parsed (`DeviceDto.rebootRequested`) for parity/visibility only — nothing
  acts on it. Actually rebooting the OS needs device-owner-level Android permissions this app
  doesn't provision; stays a real, honest, backend-only command queue (see the backend's own
  HONESTY NOTE on `POST /v1/fleet/devices/{id}/reboot`).
- Everything else this pass touched (GPS provider + fare-engine input, map centering, region
  detection, driver-PIN login, admin-PIN factory reset, Ed25519 tariff-signature verification, MDM
  "locate") is functionally addressed as of the dated entries below — genuinely open, narrower
  follow-ups each of those entries already flags explicitly: the GPS status-strip dot and duress
  GPS relay still reading a separate raw `LocationManager` fix instead of `AppContainer.speedSource`,
  the driver-initiated-hire `TripContext.startLat`/`startLng` still hardcoded `0.0, 0.0`, ~~no
  periodic background heartbeat (so MDM "locate" only answers when S6 is opened)~~ **partially
  addressed (2026-08-03, "Ambient live-position heartbeat" pass) — see that entry above:** a
  process-wide 30s while-on-shift position heartbeat now exists, though it doesn't itself read or
  acknowledge `locate_requested` (still only actioned when S6 is opened), and
  `LoginVehicleBindViewModel.kt`'s `DEMO_DRIVER_ID` quick-login constant no longer matching a real
  seeded `driver_code`.

## 2026-08-03 (even later) — Real admin-PIN verification + real Ed25519 tariff-signature verification

Direct request, closing out both remaining "High priority (correctness)" gaps this file listed
below: the hardcoded `ADMIN_PIN_PLACEHOLDER` factory-reset check and `TariffSignatureVerifier`'s
placeholder public key. Backend counterparts (`POST /v1/fleet/devices/{id}/verify-admin-pin`,
`GET /v1/tariffs/signing-public-key`, `GET /v1/tariffs/active`'s new `signature` field) already
existed — see `shared/API_SUMMARY.md`'s "Admin PIN" / "Tariff signing" notes.

- **Admin-PIN-gated factory reset (`ui/screens/settings/SettingsViewModel.kt`).**
  `attemptFactoryReset` no longer compares the entered PIN to a hardcoded constant — it calls
  `POST /v1/fleet/devices/{id}/verify-admin-pin` (new `ApiService.verifyAdminPin` +
  `VerifyAdminPinRequestDto`/`VerifyAdminPinResponseDto`) using `SessionHolder.deviceId` (the same
  device id `loadDeviceStatus`'s heartbeat call already used). Three distinct outcomes, all
  surfaced via the existing `factoryResetError` UI state (no `SettingsScreen.kt` changes needed —
  it already renders that string and disables the button on `factoryResetInProgress`):
  no `deviceId` yet (device never paired) -> blocks with an explanatory error;
  `configured=false` (tenant never set a PIN) -> blocks with an explanatory error, does NOT allow
  the reset — this was the specific "don't silently allow it" case called out in the brief, since
  a naive `if (!configured || valid)` would let anyone wipe an unconfigured tenant's device with
  any PIN at all; `configured=true, valid=false` -> "Incorrect admin PIN"; `configured=true,
  valid=true` -> proceeds with the existing DB-wipe/session-clear logic, unchanged. A network/
  server failure fails closed (blocks the reset, shows an error) rather than allowing it.
- **Real Ed25519 tariff-signature verification.** The backend signs `GET /v1/tariffs/active`
  with Ed25519, not RSA (`backend/app/services/tariff_signing.py`) — `TariffSignatureVerifier.kt`'s
  original `RsaTariffSignatureVerifier` can never verify these signatures no matter what key it's
  given, so a new `Ed25519TariffSignatureVerifier` was added (BouncyCastle
  `org.bouncycastle:bcprov-jdk18on`, since `java.security`'s own Ed25519 support needs API 33+ and
  this project's minSdk is 29 — see that class's doc). `RsaTariffSignatureVerifier` is kept defined,
  just no longer constructed by anything.
  - New `security/TariffCanonicalPayload.kt#canonicalTariffPayload` — a byte-for-byte Kotlin port
    of the backend's `canonical_tariff_payload` (fixed field order, rate fields quantized to 4dp
    half-up via `BigDecimal`, compact JSON) — this is the exact byte string that gets
    Ed25519-verified, not the raw wire JSON. **Flagged risk, not fully verified without a real
    signed payload to test against:** `effective_from`/`effective_to` are passed through
    verbatim on the assumption FastAPI/Pydantic v2's default datetime JSON serialization matches
    Python's `datetime.isoformat()` byte-for-byte for the same underlying value — see that file's
    doc for exactly what to check first if a real signature ever fails to verify.
  - New `data/local/entity/TariffSigningKeyEntity.kt` + `dao/TariffSigningKeyDao.kt` +
    `sync/TariffSigningKeyCache.kt` — a Room-backed cache for the signing public key, deliberately
    mirroring `TariffCache`'s own pure-local-read-vs-network-refresh split 1:1 (per the brief).
    `AppDatabase` bumped 2 -> 3 (no destructive-migration shortcut, same as the 1->2 bump — see
    that class's doc). `AppContainer.init()` best-effort warms this cache on startup.
  - `sync/TariffCache.kt#refresh` now verifies `TariffDto.signature` against the cached/fetched
    public key BEFORE `tariffDao.upsert` — a tariff that fails verification throws the new
    `TariffSignatureException` and is never cached, so the previously-cached (already-verified)
    tariff simply stays in place. Offline behaviour matches the brief: the signing-key check
    prefers the Room-cached key over a network fetch, so being offline *right now* never fails a
    verification for a device that verified successfully at some point in the past — a network
    fetch is only attempted as a fallback when nothing has ever been cached at all.
  - `data/remote/ApiService.kt` gained `verifyAdminPin`/`tariffSigningPublicKey` +
    `VerifyAdminPinRequestDto`/`VerifyAdminPinResponseDto`/`TariffSigningPublicKeyDto`;
    `TariffDto` gained a nullable `signature` field (only ever populated by `activeTariff`'s
    response, per the backend's `SignedTariffRead` vs. plain `TariffRead`).
  - `app/build.gradle.kts` gained the BouncyCastle dependency (`org.bouncycastle:bcprov-jdk18on:1.78.1`).

**Not done, flagged rather than silently left implicit:** neither change has ever been run
through a real compiler (see this file's standing caveat) — the canonical-payload byte format in
particular is the highest-risk part of this pass to get subtly wrong (a single stray field, wrong
quantization, or datetime-format mismatch makes every real signature fail closed, which would be
silently indistinguishable from "the anti-tamper check is working as intended" until someone
actually looks at why tariffs never update on a real device). First real-device test should
specifically watch for `TariffCache.refresh` throwing `TariffSignatureException` against a real
signed payload from a real backend.

## 2026-08-03 (latest) — Real GPS wired into map centering + region auto-detection

Direct request, explicitly scoped as the sibling follow-up to the same day's location/fare-engine
pass (`domain/location/RealLocationProvider.kt`, `SpeedSource.locationFix` — see that pass's own
entry below): consume the real `LocationFix` feed it built for the two GPS-shaped gaps it
deliberately left open for "a sibling map-centering/region-detection agent" — see its CONTRACT
section below.

**What changed:**
- **New `domain/location/RegionResolver.kt` + `domain/location/GeoMath.kt`.** `RegionResolver.resolve()`
  turns a `LocationFix?` (or bare `lat/lng`) into the real Fares Order tariff region (`"urban"` /
  `"country"` — never `"exempt"`, which is a vehicle/tariff-type classification, not a
  location-derived one) via a simple distance-from-Sydney-CBD circle (`URBAN_RADIUS_KM = 50.0`,
  a placeholder tuned by eye, not a surveyed boundary). Falls back to `"urban"` when no fix is
  available — the exact same fallback every hardcoded call site already produced, so "no fix yet"
  never regresses behaviour. **Investigated and rejected both "call it server-side instead"
  alternatives before writing this** (see `RegionResolver`'s own doc for the full writeup): neither
  exists to call today. `backend/app/models/geofence.py`'s `kind="region"` geofence rows are
  explicitly documented as "reserved for future use and not yet consumed by any service" — no
  endpoint resolves a lat/lng to a region, and the geofence schema (`backend/app/schemas/geofence.py`)
  has no field that would even tie a region-kind geofence to a tariff-region label if one existed.
  `GeoMath.distanceKm()` is a plain-Kotlin haversine mirroring the backend's own formula/Earth-
  radius constant byte-for-byte (`app/services/trips.py::haversine_km`), deliberately NOT
  `android.location.Location.distanceBetween` (which `RealLocationProvider` uses internally) so
  this money-adjacent (tariff-selecting) logic stays JVM-unit-testable without Robolectric, same
  convention `domain/fare/FareEngine.kt`'s "line-for-line port" already established.
- **Region wired at every hardcoded `DEFAULT_REGION = "urban"` call site found live in the nav
  graph:** `ui/screens/dashboard/WheelDashboardViewModel.kt` (the real S2/Idle screen, registered
  under `CabDispatchRoutes.IDLE` — see the 2026-08-01 reconciliation entry below for why
  `ui/screens/idle/IdleViewModel.kt`/`IdleScreen.kt` are dead code, deliberately left untouched
  here too), `ui/screens/settings/SettingsViewModel.kt` (S6's passenger-facing fare-schedule
  display), `ui/wheel/content/AvailableTripsWheelViewModel.kt`, and
  `ui/screens/availabletrips/AvailableTripOfferViewModel.kt` (both accept-a-job-offer hand-offs).
  The dashboard's is the one made *reactive*: `WheelDashboardViewModel.region` is a
  `distinctUntilChanged()` `StateFlow` off `AppContainer.speedSource.locationFix`, `flatMapLatest`'d
  into `TariffCache.observeActiveTariff()` and re-`refresh()`'d on every genuine region change (not
  just once at launch) — so a driver who actually crosses the urban/country boundary mid-shift gets
  the newly-relevant region's tariff fetched too. The other three are one-shot snapshots
  (`RegionResolver.resolve(AppContainer.speedSource.locationFix.value)`) taken at the moment each
  already-existing one-shot tariff read happens — matches each call site's pre-existing shape,
  no new reactive plumbing invented where none existed before.
- **`ui/screens/dashboard/WheelDashboardScreen.kt`'s `MapBackground`/`RealMapboxMapView` now center
  on the real fix** when one exists (both the interactive Mapbox `MapView` tier and the Static
  Images API fallback tier), falling back to `SydneyCbdFallback` exactly as before whenever
  `AppContainer.speedSource.locationFix` is `null` (first launch, permission denied, indoors) —
  `SydneyCbdFallback` itself is unchanged/kept, per the brief's explicit instruction not to delete
  it. **Deliberately NOT a continuous ~1Hz follow-me camera on the interactive `MapView` tier** —
  see that function's own doc comment: this background map's pan/zoom is intentionally left enabled
  ("let the driver glance around", per the 2026-08-02 Maps-SDK entry below) and a camera that
  re-snaps to the driver on every single incoming GPS tick while the vehicle is moving would fight
  that glance-around pan every second. Instead, `LaunchedEffect(fix != null)` recenters exactly
  once per "no fix → fix" transition (covers the common "permission granted, first fix lands a few
  seconds after this screen opens" case, and any later signal-loss/regain). A genuine follow-me
  camera is a real, separate UX decision for whoever owns this screen next — flagged here rather
  than accidentally half-built. The Static Images API tier has no live camera to re-point (it's a
  fetched PNG — "a fresh image must be requested for a new center", per that file's own doc), so
  its `remember` key includes the resolved center coordinates directly, re-fetching whenever they
  change.
- **The driver-position pin drawn over the map** now sits at dead-center when a real fix exists
  (since the map itself is now centered on that fix) instead of the old fixed illustrative
  `(-160.dp, -30.dp)` offset — that offset only made sense back when the map was unconditionally
  centered on the fixed Sydney CBD point regardless of the driver's real location. Falls back to
  the same illustrative offset whenever there's no fix yet, so the no-GPS case still visibly reads
  as "approximate", not "definitely here".
- **Known, explicitly out-of-scope gap NOT touched by this pass:** `TripContext.startLat`/`startLng`
  at `WheelDashboardViewModel.startMeter()` (driver-initiated street-hail hire) is still a hardcoded
  `0.0, 0.0` — a real fix is now trivially available there
  (`AppContainer.speedSource.locationFix.value`) but this pass's brief was specifically "map
  centering + region detection", and trip-start coordinates are a more sensitity, money/toll-
  geofencing-adjacent piece of trip data than a background map's center or a tariff lookup — left
  for a deliberate follow-up decision rather than folded in silently. (Job-offer-accepted hires
  already use the job's own real pickup coordinates, not this stub — see
  `AvailableTripsWheelViewModel.beginHiredHandoff()`'s doc, unaffected either way.)
- **Also NOT touched, per the brief's own item 3:** `SettingsViewModel.kt#pollGps`'s GPS
  diagnostics status dot still reads a separate raw `LocationManager` last-known-fix rather than
  `AppContainer.speedSource.locationFix` — still a real, open consolidation candidate (as the
  location/fare-engine pass's entry below already flagged), just not attempted here since the
  brief called it explicitly non-priority ("leave that as-is unless it's trivial to also surface
  the new accuracy/speed data more richly").

## 2026-08-03 (later) — MDM "locate" command now wired end-to-end

Direct request. Previously `locate_requested` was a real backend flag (`POST
/v1/fleet/devices/{id}/locate` sets it, `Device.locateRequested`) that nothing on the device ever
read — an admin could flip it from the dashboard and the device would just never respond. Fixed
now that a real location provider exists (the 2026-08-03 location/fare-engine pass's
`domain/location/RealLocationProvider.kt`, `AppContainer.speedSource.locationFix`).

**What changed:**
- `data/remote/ApiService.kt`'s `DeviceDto` gained `locateRequested`/`rebootRequested` fields — it
  previously didn't even parse `locate_requested` back off the heartbeat response (harmless thanks
  to `ignoreUnknownKeys=true`, but meant nothing could act on it either). Also added
  `PositionPublishRequestDto`/`PositionPublishResponseDto` + `ApiService.publishPosition()`
  (`POST /v1/fleet/positions`, the Live Ops domain's pub/sub endpoint — see `shared/API_SUMMARY.md`
  "Live Ops"), previously not wired into this app's Retrofit contract at all.
- `ui/screens/settings/SettingsViewModel.kt`'s `loadDeviceStatus()` (the existing heartbeat call)
  now checks `device.locateRequested` on every heartbeat response; if set, `respondToLocateRequest()`
  publishes the device's current real fix (`AppContainer.speedSource.locationFix.value`) against
  its bound vehicle (`SessionHolder.session.value?.vehicleId`) via `publishPosition()` — so a
  dispatcher watching the fleet dashboard's Live Map sees a fresh pin as evidence the request was
  answered. New `LocateResponseState` UI state (`ui/screens/settings/SettingsScreen.kt`'s
  DiagnosticsCard) surfaces the outcome (`Sent` / `NoFixYet` / `NoVehicleBound` / `Failed(...)`) —
  silent (`Idle`) unless an admin has actually triggered a locate against this device.
- **No acknowledge/clear step, by design, not an oversight:** the only endpoint that flips
  `locate_requested` back off (`POST /v1/fleet/devices/{id}/locate`) is admin-only server-side —
  a driver/staff-role device JWT can never call it. A fresh position publish is treated as
  sufficient evidence per this pass's brief; the flag just stays set until an admin clears it,
  which only means this device re-publishes on every subsequent heartbeat while it's still set.
- **`reboot_requested` deliberately left alone**, exactly as this file already documents for other
  hardware-needing gaps: actually rebooting the OS needs device-owner-level Android permissions
  this app doesn't provision. `DeviceDto` now parses the field (for parity/visibility) but nothing
  reads or acts on it — see the backend's own HONESTY NOTE on `POST /v1/fleet/devices/{id}/reboot`
  (`backend/app/api/v1/fleet.py`). Still a real, honest, backend-only command queue.
- **Real, honest limitation this pass did NOT close:** `loadDeviceStatus()` only runs once, when
  `SettingsViewModel` is created — i.e. whenever the driver happens to open S6/Settings. There is
  no periodic/background heartbeat anywhere in this app yet, so a "Locate" request only gets
  answered the next time S6 is opened, not the instant an admin sets the flag. Closing that would
  need a periodic background heartbeat (WorkManager, mirroring `sync/SyncWorker.kt`'s pattern) —
  a materially bigger change than "wire the existing flow", left open below. **Partially addressed
  (see the same day's later "Ambient live-position heartbeat" entry above):** a separate 30s
  while-on-shift heartbeat now exists (`domain/LivePositionHeartbeat.kt`), so in practice a
  dispatcher sees a fresh position within ~30s of shift start regardless of whether a locate
  request was ever sent — but that new heartbeat does not read or acknowledge `locate_requested`
  itself; the specific mechanic described in this paragraph (the flag only getting acted on when
  S6 opens) is still real, just less consequential now that positions publish ambiently anyway.

## 2026-08-02 (even later) — Fixed: real map rendering solid black

Reported directly from a real build ("app runs, map area is blank/black") — this is the first
real-device/emulator bug report against any of this module's code, and a genuinely diagnosable
one (unlike the offline-region API, which is a "might not match the real SDK" risk flag, this had
an identifiable root cause).

**Root cause:** `RealMapboxMapView` (`WheelDashboardScreen.kt`) embedded a View-based Mapbox
`MapView` via `AndroidView` but never forwarded Activity lifecycle events to it. A `MapView` used
this way (as opposed to inflated via XML inside an Activity that itself calls
`mapView.onStart()`/`onResume()`/etc., the "normal" Mapbox integration path) needs those calls
forwarded manually, or it allocates its rendering surface but never starts its GL render loop —
symptom is a solid black view, no crash, nothing in Logcat naming the problem. Not a token/network
issue (that would show as a style-load error or placeholder tiles, not solid black).

**Fix:** a `DisposableEffect` observing `LocalLifecycleOwner` (the core
`androidx.compose.ui.platform` one, not the separate `androidx.lifecycle.compose` artifact this
project doesn't depend on) that forwards `ON_START`/`ON_RESUME`/`ON_PAUSE`/`ON_STOP`/`ON_DESTROY`
to the `MapView`. No manual "fire it once immediately" call needed — `Lifecycle.addObserver`
automatically brings a freshly-added observer up to the current state, which covers the
already-resumed-when-created case too.

**If the map is still black after pulling this fix**, it's very unlikely to be the same bug —
check (in this order): `local.properties` actually has a real `MAPBOX_ACCESS_TOKEN` on your
machine (it's gitignored, never synced by `git pull` — you have to set your own), Logcat for any
`Mapbox`-tagged error around the time the dashboard screen opens, and basic network connectivity
on the device/emulator.

## 2026-08-02 (latest) — Wheel visual redesign: dimensional rim/spokes/hub + glowing icons

Direct request, working from a Gemini-generated concept image (not real code/screenshot — the
garbled overlapping text visible in that image was an AI-image-gen artifact, not a real layout
bug to chase; ignored). The ask was aesthetic direction only: move the wheel from a flat ring of
plain dots toward a literal steering-wheel read (rim, spokes, center hub) with glowing icon
badges, matching the concept image's mood — not a pixel-accurate copy (that image also has
inconsistent icon choices, e.g. the same clock icon on two different slots, that weren't worth
replicating).

**What changed, all in `ui/screens/dashboard/WheelDashboardScreen.kt`:**
- New `WheelRimAndSpokes` (a `Canvas`) — a soft radial-gradient rim fill + a highlighted edge
  ring + thin gradient spoke lines from center to each icon position, drawn at the exact same
  angle math `WheelSlotDot` already uses so spokes always point precisely at their icon
  regardless of wheel rotation.
- New `WheelHub` — small decorative circular hub at dead center, no interaction, just completes
  the steering-wheel read.
- `WheelSlotDot`: replaced the 2-letter text abbreviation with a real icon glyph per slot
  (`slotIcon()`) — **emoji, not a Material icon set, on purpose**: this codebase already uses
  emoji for iconography elsewhere (`LoginVehicleBindScreen.kt`'s step icons) and has no
  `material-icons-extended` dependency, so named icons like `Icons.Filled.Email` would risk an
  unresolved-reference error this environment can't compile-check. Also added a glow to
  **every** icon now, not just the selected one (white/faint when unselected, gold/strong when
  selected) — matches the concept image's "all spokes lit" look; previously only the selected
  dot had any glow at all.
- Uses only core, well-established `androidx.compose.foundation.Canvas`/`drawCircle`/`drawLine`
  APIs (brush gradients, stroke caps) — this is standard, stable Compose Graphics surface, much
  lower risk than the Mapbox offline integration above; if something in this specific change
  doesn't compile it's more likely a typo than an API-shape mismatch.

## 2026-08-02 (later) — Real offline maps via Mapbox Maps SDK v11

A secret `MAPBOX_DOWNLOADS_TOKEN` (sk.*, "Downloads:Read" scope) became available this pass,
which unlocks something the earlier Static Images pass explicitly couldn't do: the actual Maps
SDK, with genuine offline-region download. **This is the highest-risk unverified code in the
whole module — read this section before touching anything Mapbox-related.**

**What changed:**
- `settings.gradle.kts` — added Mapbox's private Maven repo with HTTP Basic auth, credentials
  read from `local.properties`'s `MAPBOX_DOWNLOADS_TOKEN` (gitignored, machine-specific — if
  that property is missing/empty, Gradle sync will fail with a 401 the moment it actually needs
  to fetch `com.mapbox.maps:android`, not silently degrade — that's the correct/expected failure
  mode for "nobody's configured the secret token on this machine yet").
- `app/build.gradle.kts` — added `com.mapbox.maps:android:11.8.1`. **Version pin note:** written
  against a general understanding of the v11 API shape, not verified against Mapbox's actual
  current release — check their changelog and bump if meaningfully newer before relying on this.
- `CabDispatchApp.kt` — sets `MapboxOptions.accessToken` (the *public* pk.* token) at startup,
  the v11 programmatic-token pattern.
- New `data/remote/MapboxOfflineRegion.kt` — wraps `TileStore`/`OfflineManager` to download a
  Sydney-metro bounding box region for fully-offline map serving afterward. **Read this file's
  own doc comment before debugging it** — it names the exact risk (TileStore/OfflineManager
  signatures have genuinely changed across v11 minor versions, this was written from general
  knowledge of the shape, not a verified-current API reference) and points at Mapbox's own
  current "Android Offline Maps" docs as the authoritative source for reconciling any mismatch.
- `ui/screens/dashboard/WheelDashboardScreen.kt`'s `MapBackground` — now three-tier fallback:
  real interactive `MapView` (via `AndroidView` interop, not Mapbox's separate Compose-extension
  artifact — deliberate, to avoid betting on two different Mapbox API surfaces at once) → Static
  Images API (the previous pass's approach, kept as the no-secret-token fallback) →
  `IllustrativeGridFallback` (the original placeholder, kept as the final fallback). Once a
  region is downloaded via `MapboxOfflineRegion`, the SDK serves matching requests from its local
  cache automatically — no separate "offline mode" branch needed in the UI code.
- `ui/screens/settings/SettingsScreen.kt` + `SettingsViewModel.kt` — a "Download offline maps"
  action in S6 (Settings/Diagnostics), with progress/success/failure states.

**Compile-order suggestion:** if the build fails inside `com.mapbox.*` types, start with
`MapboxOfflineRegion.kt` (named above as the highest-risk file) before assuming something else
is wrong — the Gradle wiring (`settings.gradle.kts`/`build.gradle.kts`) and the simpler
`MapView`/`CameraOptions`/`Style.DARK` calls in `WheelDashboardScreen.kt` are much more standard,
stable API surface and less likely to be the actual problem.

## 2026-08-02 — LED digit + wheel selection polish (direct user request)

Two small, targeted visual changes, both **unverified like everything else here** (no compiler):

- `ui/screens/hired/HiredScreen.kt`'s `LedFareDigits`: color `WheelColors.meterLedRed` →
  `WheelColors.meterLedWhite` (new token, `ui/theme/Theme.kt`, `#F4FAFF`), size `72.sp` → `104.sp`,
  wider glow blur — requested for back-seat-passenger legibility. `meterLedRed` is kept defined
  (still named in doc comments elsewhere explaining why duress uses a different red) but is no
  longer used for the digits themselves.
- `ui/screens/dashboard/WheelDashboardScreen.kt`'s `WheelSlotDot`: added a lock-in pulse
  (`Animatable` + `keyframes`, scale 1 → 1.18 → 1 on the back-out ease, gated on the `selected`
  false→true edge via `LaunchedEffect(selected)`) and a gold `Modifier.shadow` glow on the
  selected dot. Previously the dot only crossfaded size/color/border on selection, which reads as
  passive — this matches the reference prototype's `.pulse` keyframe, which nothing had ported
  before.

If you're picking this up fresh: **check both render correctly before doing anything else with
them** — the LED digit font size in particular (104.sp) was chosen by eye against the 1280×800
reference canvas ratio, not measured against a real device, so it may need adjusting once you can
actually see it on a tablet.

## 2026-08-01 — Wheel-redesign reconciliation pass

Eight sibling agents built the wheel-nav dashboard redesign in parallel against a shared
foundation contract, never compiling against each other. This pass read every file each agent
touched, resolved every `TODO: verify against sibling ...` marker they'd deliberately left behind,
and traced the one deliberate real-gap-fix (toll-chip wiring) end to end. No Android SDK here
either, so this is a careful manual/textual pass, not a compiler-verified one — see "still not
fully confident about" below.

**What shipped this pass:**

- **Wheel-slot content wiring (the one real gap found).** `ui/screens/dashboard/WheelDashboardScreen.kt`
  had all five non-status wheel slots (Available Trips, Messages, Trips, Earnings, Shift) falling
  through to a `PlaceholderSlotContent` stub — every sibling agent had built and correctly
  documented their own slot composable, but nothing ever swapped the placeholder branches out.
  Wired `AvailableTripsWheelContent`, `MessagesWheelContent`, `TripsWheelContent`,
  `EarningsWheelContent`, and `ShiftWheelContent` into `wheelSlotContentProviderFor` via small
  per-slot `WheelSlotContentProvider` wrappers (`AvailableTripsSlotContent` etc., same file), each
  passed the shared `NavHostController` or a navigation callback exactly as that slot's own doc
  comment had already specified. This was the only place code actually needed to change — every
  sibling composable's own signature/contract was already correct.
- **NavHost.** Confirmed the full flow is wired as specified: Splash → Login/QR/pre-shift
  inspection → Shift-start confirmation → WheelDashboard (registered under the pre-existing `IDLE`
  route key so every old S2 `navigate(IDLE)` call kept working) → Start Meter → Hired (S3) →
  Close & Pay (S4) → Receipt → back to WheelDashboard. Available Trips/Messages/Trips/
  Earnings/Shift are wheel-slot content, not separate destinations, exactly per the brief; their
  detail screens (job-offer accept/decline, message thread, trip detail, submit-shift
  confirmation) are real routes and are all reachable now that the wheel-slot content wiring above
  landed (previously unreachable in practice, since nothing rendered the list rows that navigate to
  them). Profile is reachable from the dashboard's identity card; the duress overlays
  (`Duress triggered`/`Duress active`) render on both the dashboard and Hired; the Navigate overlay
  landed as a direct `openInMaps()` call from the job-offer detail screen rather than the
  `NavigateOverlay` composable itself (a legitimate design choice the sibling agent made — see that
  file's doc — `NavigateOverlay` has no call site as a result, kept as ready-made UI, not deleted).
- **Naming/signature reconciliation.** Every `// TODO: verify against sibling ... once merged`
  comment across the module (14 occurrences, across `CabDispatchNavHost.kt`, `NavigateOverlay.kt`,
  `WheelDashboardScreen.kt`, and every wheel-slot content/detail screen) was checked against what
  the sibling actually built. All 14 turned out to already be correct guesses — no caller/callee
  signature actually disagreed — so each was resolved by rewording the comment from "TODO: verify"
  to "Verified (reconciliation pass)" with a note of what was checked, not by changing any
  behaviour. The one exception was the dead `PlaceholderSlotContent` fallback class in
  `WheelDashboardScreen.kt`, removed since nothing references it after the wiring above.
- **AppContainer.** Audited every repository/gateway/controller singleton — `tripRepository`,
  `tariffCache`, `pureFareEngine`, `cardPaymentGateway`, `receiptPrinterGateway`,
  `smsReceiptGateway`, `emailReceiptGateway`, `tariffSignatureVerifier`, `qrScanner`,
  `tripStatsRepository`, `shiftRepository`, `speedSource`, `realtimeSocket`, `jobsRepository`,
  `messagesRepository`, `duressRepository`, `duressController` — each registered exactly once, no
  duplicates, nothing constructed a second competing instance elsewhere. `SharedPreferencesDriverAuthRepository`
  is the one repository *not* in `AppContainer` (constructed inline in
  `LoginVehicleBindViewModel`) — legitimate, not a miss: it needs an `Application` `Context` for
  `SharedPreferences` that `AppContainer` doesn't hold onto after `init()`.
- **Toll-chip wiring, traced end to end.** Confirmed this is a real fix into the trip domain
  model, not a local UI counter: `HiredScreen`'s toll chips call `HiredViewModel.addToll()` →
  the live `domain.FareEngineImpl` updates its running `FareState.breakdown.tolls` → every engine
  tick, `HiredViewModel.doPersistTick()` writes that cumulative total into
  `TripRepository.tick(tolls = ...)` → persisted onto `TripEntity.tolls` in Room → S4
  (`CloseAndPayViewModel`) reads it back via `domain/fare/TripFareReconstruction.kt`'s
  `reconstructFareState()` (`FareState.tolls = trip.tolls`) → fed into the golden-vector-proven
  `domain.fare.FareEngine.close()` → `FareBreakdown.tolls` is part of `grandTotal`, which is what's
  charged (`deviceTotal`), shown on the receipt, and shown on the Trip Detail screen. Confirmed
  correct; no changes needed here, just verification.

**What's still genuinely stubbed or scoped down** (unchanged by this pass, listed here since the
brief asked for it alongside what shipped):

- No proximity/ETA job matching — job offers are first-accept-wins only, no distance-from-driver
  shown on any job card (GPS is still stubbed, see below).
- "Navigate" (row 28) is a `geo:`/Google Maps deep link into the device's default maps app, not
  custom turn-by-turn — a deliberate spec decision (§7: "explicitly NOT custom turn-by-turn"), not
  a shortcut taken this pass.
- No true 7-segment font for the meter's LED fare digits — monospace + red glow + text-shadow is
  the documented fallback (spec §11 sanctions this; no licensed font available/sourced).
- ~~The dashboard's map background is a plain drawn diagonal grid...~~ **Addressed (2026-08-02,
  Mapbox Static Images API pass):** `ui/screens/dashboard/WheelDashboardScreen.kt`'s
  `MapBackground` now async-loads a real Mapbox map PNG (dark-v11 style, matching the app's dark
  theme) via `data/remote/MapboxStaticImage.kt`, using Coil (`io.coil-kt:coil-compose:2.6.0`, new
  dependency, `app/build.gradle.kts`). The plain-drawn diagonal grid wasn't deleted — it's kept as
  `IllustrativeGridFallback`, now used only for the image's loading/error states (bad token,
  offline, Mapbox outage) so a network failure never leaves a blank background.
  - **Read this before attempting the full Maps SDK (v10/v11, interactive pan/zoom/offline tiles)
    instead of the Static Images API — do not re-attempt it without first reading this paragraph:**
    that SDK's Gradle dependency resolves from Mapbox's *private* Maven repo, which requires
    configuring a separate **secret downloads token** (`sk.*`, "Downloads:Read" scope) as Maven
    repository credentials in `settings.gradle.kts`. A public `pk.*` access token — all that's
    wired into this app (`local.properties`' `MAPBOX_ACCESS_TOKEN`, exposed as
    `BuildConfig.MAPBOX_ACCESS_TOKEN`) — is not accepted there; the dependency fails to resolve
    before any app code runs, no matter how correctly the integration code itself is written. The
    Static Images API sidesteps this entirely: it's a plain authenticated HTTPS GET
    (`https://api.mapbox.com/styles/v1/mapbox/dark-v11/static/{lon},{lat},{zoom}/{w}x{h}@2x?access_token=...`)
    returning a real rendered map PNG, needs no Maven credential, and the `pk.*` token is
    explicitly designed to be used exactly this way. **Note for whoever picks this up next:** this
    machine's `local.properties` actually *also* has a `MAPBOX_DOWNLOADS_TOKEN` (`sk.*`) sitting
    next to the public one — this pass deliberately did not use it (out of scope, and wiring Maven
    repo credentials + the full SDK is a materially bigger change than this pass's brief), but a
    future pass wanting the interactive SDK may not need to go get a new secret token first — check
    whether that one is still valid before assuming it needs sourcing from scratch.
  - ~~Still a real, honest gap: the map is centered on a **fixed Sydney CBD coordinate**~~
    **Addressed (2026-08-03, map-centering/region-detection pass — see that entry near the top of
    this file for the full writeup):** `MapBackground`/`RealMapboxMapView` in
    `WheelDashboardScreen.kt` now center on `AppContainer.speedSource.locationFix` when a real fix
    exists, on both the interactive `MapView` tier and this Static Images fallback tier —
    `SydneyCbdFallback` is kept as the real fallback center whenever there's no fix yet, not
    deleted. The position pin drawn over the map now sits at dead-center when a real fix exists
    (previously always the old static illustrative offset).
  - Still non-interactive by design (a fetched PNG, not a live map you can pan/zoom/rotate) — that's
    the Static Images API's nature, not a shortcut taken this pass; see the constraint above for
    why the interactive SDK isn't viable with only a `pk.*` token.
- ~~GPS is still stubbed project-wide~~ **Addressed for the provider + fare engine + map centering +
  region detection (2026-08-03, two passes):** `domain/location/RealLocationProvider.kt` is a real
  `FusedLocationProviderClient`-backed `SpeedSource`, wired as `AppContainer.speedSource` — the
  fare engine (`domain/FareEngine.kt`'s `FareEngineImpl.tick`) now ticks against real device speed
  instead of `StubSpeedSource`'s fixed 0.0. `SpeedSource` also gained
  `locationFix: StateFlow<LocationFix?>` (lat/lng/speed/accuracy/timestamp) alongside the
  pre-existing `speedKmh`; a same-day sibling pass (see this file's top entry) then consumed that
  feed to close the two GPS-shaped gaps this note used to leave open — the dashboard's map
  background now centers on it (`WheelDashboardScreen.kt`) and every hardcoded
  `DEFAULT_REGION = "urban"` call site now resolves a real region from it
  (`domain/location/RegionResolver.kt`). **Still genuinely open:** GPS status-strip dot
  (`SettingsViewModel.kt#pollGps`) and duress GPS relay (`HiredViewModel.kt#lastKnownFix`) still
  read a separate raw `LocationManager` last-known-fix rather than this feed — untouched by either
  pass (explicitly out of scope for both), but now a real candidate to consolidate onto
  `AppContainer.speedSource.locationFix` in a future pass. `WheelDashboardViewModel.startMeter()`'s
  driver-initiated-hire `TripContext.startLat`/`startLng` is also still a hardcoded `0.0, 0.0` for
  the same "explicitly out of scope for this pass's brief" reason — see the top entry's own
  call-out. Runtime permission handling: `RealLocationProvider` only *checks*
  `ACCESS_FINE_LOCATION` (poll-and-degrade, see its doc) — no screen anywhere in this module
  actually *requests* it yet (grep for `ContextCompat.checkSelfPermission`, every existing hit only
  checks); wiring a real `ActivityResultContracts.RequestPermission` prompt somewhere in the S1/S2
  flow is still open and is a UI-consumer concern, not this file's.
- ~~Driver login still maps Driver ID/PIN onto the staff email/password contract~~ **Fixed
  (2026-08-03, driver-PIN login pass):** the backend shipped the dedicated `POST
  /v1/auth/driver-login` endpoint (`driver_code` + `pin`) this gap asked for.
  `domain/DriverAuthRepository.kt` now calls it directly instead of the staff `/v1/auth/login`
  placeholder mapping; the offline-cached-hash fallback is unchanged. `data/remote/ApiService.kt`
  gained `driverLogin()`/`DriverLoginRequestDto`/`DriverLoginResponseDto` and `mfaLogin()`/
  `MfaLoginRequestDto` — the driver-login response is a union (`TokenResponse |
  MfaRequiredResponse`, same two-step TOTP contract as staff login) since driver accounts with MFA
  enabled are a real, documented path (shared/API_SUMMARY.md), not a hypothetical one; `login()`
  now returns a 3-way `DriverLoginResult` (`Success`/`MfaRequired`/`Failure`) instead of a plain
  `Result` so the MFA challenge isn't forced through the failure branch. `ui/screens/login/
  LoginVehicleBindScreen.kt` gained an inline MFA-code sub-view on the same driver-login card (see
  `MfaCodeStep`); `LoginVehicleBindViewModel.kt` gained `verifyMfaCode()`/`cancelMfaChallenge()`.
  The "Driver ID" field's label/hint text already didn't imply email — no copy change was needed
  there.
  - **New follow-on gap this gap's fix introduced:** `LoginVehicleBindViewModel.kt`'s
    `DEMO_DRIVER_ID` debug quick-login constant is the seeded demo driver's *email*
    (`driver@lillycabs.test`), which no longer works as a `driver_code` now that quick-login goes
    through the real endpoint — `backend/scripts/seed.py` mints a random `driver_code` per fresh
    DB and only prints it to stdout, so there's no fixed value to hardcode. See that constant's
    doc comment for the two ways to actually fix it (read the printed code manually, or have
    seed.py write it somewhere this constant can read at debug-build time). The button still
    renders and still fails safely (a normal 401 -> login error text), it just doesn't
    one-tap-login anymore until someone picks this up.
- ~~`TariffSignatureVerifier`'s public key is still a placeholder; `SettingsViewModel`'s admin
  factory-reset PIN is still a hardcoded, explicitly-flagged-non-secure placeholder.~~ **Fixed
  (2026-08-03, admin-PIN + tariff-signature pass — see that section above for the full writeup).**
- Hardware gateways (`CardPaymentGateway`, `ReceiptPrinterGateway`, SMS/email receipt gateways)
  remain mock/no-op behind real interfaces, per the existing "leave stubbed until a physical pilot"
  guidance below — not touched.

**Found but not fully reconciled without a compiler:** nothing outstanding as of this pass — every
signature this pass could trace by reading both the caller and callee agreed exactly (see the 14
resolved TODOs above). The residual risk is the same one the rest of this file already flags
loudly: **none of this has ever been run through `javac`/`kotlinc`**, so there could still be a
real type mismatch, a missing import, or a Compose API misuse this manual pass simply didn't spot
by eye — `./gradlew assembleDebug` (Step 0 below) is still the first real test. A few pre-existing
`TODO(integration agent)` comments (not the "verify against sibling" kind this pass targeted)
remain genuinely open — `domain/Session.kt` (session persistence across process death) and
`domain/FareEngine.kt`/`HiredViewModel.kt` (the live fare-engine instance being nav-scoped, not
process-scoped) — both pre-existing, scoped-out design decisions flagged for a future pass, not
things this pass introduced or was asked to close.

## Step 0 — get it compiling (do this before anything else)

1. Open `android/` (this folder) as the project root in Android Studio, let Gradle sync.
2. `./gradlew assembleDebug` (or Build → Make Project). Fix real compile errors as they surface —
   check `AppContainer.kt` first if you see "unresolved reference" errors, it's the manual
   service-locator wiring everything else depends on.
3. `./gradlew testDebugUnitTest` — two plain-JUnit test files exist and should just run:
   `domain/fare/FareEngineTest.kt` and a sync-outbox test. **Do not weaken or delete an
   assertion to make a test pass** — if `FareEngineTest` disagrees with the backend, the backend
   (`../backend/app/services/fare_engine.py` + `../backend/tests/test_fare_engine_golden.py`) is
   the source of truth; fix the Kotlin port, not the test.
4. Only once it compiles and unit tests pass, move to a device/emulator.

## Step 1 — cross-check the fare engine against the backend (safety-critical, do this explicitly)

There are **two** fare-related classes in this module and that's deliberate, not a bug — don't
merge them without understanding why first:

- `domain/fare/FareEngine.kt` — the line-for-line port of the backend's `fare_engine.py`, proven
  against the same golden vectors as `backend/tests/test_fare_engine_golden.py`. This is what
  must be correct for money.
- `domain/FareEngine.kt` — a live, tick-by-tick UI-facing engine that drives the S3 running-fare
  display in real time. It persists `movingSeconds`/`waitingSeconds` to Room as it goes; S4
  (`CloseAndPayViewModel`) reconstructs the final trip from those persisted counters through
  `domain/fare/TripFareReconstruction.kt`, which is what actually calls the golden-vector-proven
  engine for the number that gets shown/charged/synced.

Read `domain/fare/TripFareReconstruction.kt` and confirm for yourself that the number the
passenger is charged always comes from the proven engine, never from the live UI engine directly.
If you find a code path where it doesn't, that's a real bug — fix it before anything else on this
list.

## Step 2 — known gaps, in priority order

### High priority (correctness / would-block-shipping)

- ~~**`security/TariffSignatureVerifier.kt`** — has a `*** PLACEHOLDER KEY — REPLACE BEFORE
  SHIPPING ***` comment.~~ **Fixed (2026-08-03, admin-PIN + tariff-signature pass):** now fetches
  a real Ed25519 public key from `GET /v1/tariffs/signing-public-key` (Room-cached, see
  `sync/TariffSigningKeyCache.kt`) and verifies every fetched tariff's signature via the new
  `Ed25519TariffSignatureVerifier` before caching it — see that dated section above for the full
  writeup, including the one real residual risk flagged there (the canonical-payload byte format
  has never been checked against a real signed payload from a real compiler-built backend).
- ~~**`ui/screens/settings/SettingsViewModel.kt`** — `ADMIN_PIN_PLACEHOLDER = "913572"` is a
  hardcoded factory-reset PIN, explicitly marked `*** NOT A REAL SECURITY CONTROL ***`.~~ **Fixed
  (2026-08-03, same pass):** `attemptFactoryReset` now calls `POST
  /v1/fleet/devices/{id}/verify-admin-pin` and correctly distinguishes "no PIN configured for this
  tenant yet" (blocks, doesn't silently allow) from "wrong PIN" — see that dated section above.
- **`domain/DriverAuthRepository.kt`** — the meter's "Driver ID" field is currently mapped
  straight to the backend's `email`, and "PIN" to `password` — i.e. it's reusing the staff
  email/password login, not a real driver-PIN system. Decide: keep this as the permanent design,
  or build a dedicated driver-PIN backend endpoint (`POST /v1/auth/driver-login` taking a short
  driver code + PIN, separate from staff login) and wire this class to it. If you build the
  backend endpoint, the FastAPI conventions to follow are in `../backend/app/api/v1/auth.py` and
  `../backend/app/core/security.py` — tenant-scoped, same JWT shape, same `require_role` pattern
  every other domain router uses.

### Medium priority (real functionality gaps, no hardware needed)

- ~~GPS is stubbed, not real.~~ **Fixed for the core provider + fare engine + map centering +
  region detection (2026-08-03, two passes):** `domain/location/RealLocationProvider.kt` +
  `AppContainer.speedSource`, then map-centering/region-detection consumed that feed the same day —
  see the "still genuinely stubbed" section above for the full writeup and what's still open (GPS
  status-strip, duress relay, driver-initiated-hire start coordinates — all separate call sites
  neither pass touched).
- ~~Region is hardcoded to `"urban"`~~ **Fixed (2026-08-03, map-centering/region-detection pass):**
  every hardcoded `DEFAULT_REGION = "urban"` call site actually reachable from the nav graph
  (`WheelDashboardViewModel.kt`, `SettingsViewModel.kt`, `AvailableTripsWheelViewModel.kt`,
  `AvailableTripOfferViewModel.kt`) now resolves a real region via `domain/location/RegionResolver.kt`
  — a distance-from-Sydney-CBD circle (`"urban"`/`"country"`), not the real polygon-based
  geofencing spec B7 ultimately calls for (see that class's doc for why: neither a server-side nor
  an on-device geofence-based resolver exists to call yet — `backend/app/models/geofence.py`'s
  `kind="region"` rows are still explicitly "reserved for future use"). Never returns `"exempt"` —
  that's a vehicle/tariff-type classification, not location-derived. The dead, unreferenced
  `ui/screens/idle/IdleViewModel.kt`/`IdleScreen.kt` (superseded by `WheelDashboardViewModel`/
  `WheelDashboardScreen`, see the 2026-08-01 reconciliation entry) still has its own hardcoded
  `DEFAULT_REGION` — deliberately left alone, it's dead code with no route pointing at it.
- **Toll preset amounts are illustrative placeholders** (`domain/TripModels.kt`) — replace with
  real fixed toll amounts (M5, Harbour southbound, airport) once you have them.
- **Availability broadcast not wired** — `IdleViewModel.kt`'s "For Hire" toggle doesn't tell the
  backend anything yet. Backend already has `POST /v1/fleet/positions` for this (see
  `../shared/API_SUMMARY.md`).
- ~~MDM "locate" command is backend-only, nothing on-device acts on it~~ **Fixed (2026-08-03,
  see that dated section above):** `SettingsViewModel.kt`'s existing heartbeat now reads
  `locate_requested` back and answers it via `POST /v1/fleet/positions`. Real open limitation left
  behind: it only fires once, when S6/Settings happens to be opened — no periodic background
  heartbeat exists in this app yet, so a locate request isn't answered until the driver next opens
  that screen. `reboot_requested` remains intentionally backend-only (see the Low-priority
  hardware note's spirit — this one's blocked by missing device-owner OS permissions, not missing
  hardware). ~~No periodic background heartbeat exists in this app yet~~ **Partially addressed
  (2026-08-03, "Ambient live-position heartbeat" pass, same day):** `domain/LivePositionHeartbeat.kt`
  now publishes a position every 30s for the whole duration of an open shift, independent of
  whether any locate request was ever sent — so in practice a dispatcher isn't waiting on a locate
  response at all for a driver who's on shift. The `locate_requested` flag itself is still only
  read/acted on when S6 happens to be opened, unchanged by that pass — see its own entry for the
  exact scope of what it did and didn't close.
- ~~Duress gesture is a no-op~~ **Fixed (wheel-redesign Profile/overlays pass):** the hidden
  triple-tap gesture (S3/Hired and the wheel-dashboard shell) now calls the real backend duress
  endpoints via `domain/DuressController.kt` + `domain/DuressRepository.kt`, driving the
  "Duress triggered"/"Duress active" overlays in `ui/overlays/DuressOverlays.kt`. GPS relay while
  active is still best-effort last-known-fix (see the `SpeedSource` gap below) rather than a true
  continuous stream — a real fused/live location provider would upgrade both at once.
- **QR vehicle-pairing scanner is a stub** (`domain/QrScanner.kt`) — manual-entry fallback works,
  but real camera scanning (CameraX + ML Kit) isn't implemented.
- **Pre-shift inspection checklist items are placeholders** (`LoginVehicleBindViewModel.kt`) —
  confirm real checklist content against whatever compliance checklist NSW cl.14 actually
  requires (spec section A1) before this is real.

### Low priority (hardware-dependent — reasonable to leave stubbed until a physical pilot)

`hardware/payments/CardPaymentGateway.kt` (Stripe Terminal Tap-to-Pay), `hardware/printing/
ReceiptPrinterGateway.kt` (BT thermal printer), `hardware/receipt/{Sms,Email}ReceiptGateway.kt` —
all real interfaces with mock/no-op implementations behind them. Don't fake these into "working" —
they genuinely need physical hardware (or a real Stripe key) to implement for real. Leave stubbed
and move on unless you specifically have hardware to test against.

## Step 3 — manual end-to-end test, once it runs

1. Log in (S1) — see the driver-auth gap above; for now use a real backend user's email/password
   as "Driver ID"/"PIN" (create one via the dashboard's Fleet & Drivers page, or
   `POST /v1/users`).
2. Walk S1 → S2 (available toggle) → S3 (start a trip, watch the fare accrue) → S4 (close, check
   the fare breakdown + GST line) → S5 (shift report) → S6 (settings/diagnostics).
3. **Offline test — this is the point of the whole app:** turn on airplane mode, run a full trip
   start-to-close. Confirm it works with zero errors (no network calls should block anything).
   Then re-enable network and confirm `SyncWorker` drains the outbox automatically within a
   couple minutes — check the backend's `GET /v1/trips` (or the dashboard's Trips page) for the
   trip showing up, and confirm running it twice doesn't create a duplicate (idempotency via
   `client_uuid`).

## Backend, for testing against

Needs to run **on this same machine** for the emulator's `10.0.2.2:8001` alias to reach it (a
physical device needs your LAN IP instead — see `app/build.gradle.kts`'s `API_BASE_URL` per
build type).

```
cd backend
uv sync
uv run python scripts/init_db.py
uv run python scripts/seed.py
uv run uvicorn app.main:app --port 8001
```

Seeded logins (both password `ChangeMe123!`): `admin@cabdispatch.test` (platform owner,
cross-tenant), `owner@lillycabs.test` (Lilly Cabs tenant owner). Neither is a `driver` role —
create one via the dashboard or `POST /v1/users` before testing driver login specifically.
Full API reference: `../shared/API_SUMMARY.md` and `../shared/openapi.json`.

## When you fix something

Commit as you go with real messages (what + why, not "fix bug"). Push to `main` unless you're
mid-something risky, in which case a branch + note to the user is safer. Update this file's
"known gaps" section if you close one out or discover a new one — keep it honest for whoever
(human or Claude) reads it next.
