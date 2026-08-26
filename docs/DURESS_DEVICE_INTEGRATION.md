# Duress Device Integration -- System Contract

**Scope:** the interface between three parties building one safety system:

1. **OEM/electronic engineer** (TY-EMS or equivalent) -- building the physical CT-DPD-01 duress
   panic device. See `docs/CT-DPD-01_Tech_Pack_for_TY-EMS.md` for the hardware spec/BOM this
   document's firmware checklist (Section 9) is scoped against.
2. **Android meter app team** -- the in-cab tablet app (`au.com.threesixty.cabdispatch`), which
   pairs to the device over Bluetooth LE and independently talks to the same backend.
3. **This FastAPI backend** -- correlates both paths into one incident and drives the Duress Desk.

This is the single source of truth for that interface: the BLE profile between device and tablet,
both sides' cellular contracts with the server, and how the server merges them. Where this
document and code disagree, the code is authoritative -- file references are given throughout so
you can check.

**Status (updated 2026-08-26, post live end-to-end verification):** BOTH paths are now built,
registered, and live-verified against a real running backend -- not just specified. The tablet path
(`app/api/v1/duress.py`) was already live: trigger/cancel/escalate/close, the 5-second GPS relay,
and audio upload/playback. The device path (`app/api/v1/duress_device.py`,
`app/services/duress_device.py`) is now live too: the HMAC auth handshake, alarm open/correlation
(idempotent retrigger, tablet-then-device "both" attach, and the reverse device-then-tablet case),
GPS batch ingest, audio upload, heartbeat, and the full admin CRUD -- all curl-verified end-to-end,
including the security boundaries (a device token cannot touch another device's event: 403; a human
access token cannot call a device-only route: 401; a deactivated device is rejected). `POST
/v1/duress/{event_id}/call` ("call the cab") and `POST /v1/duress/twilio/status` are live too,
correctly mock-falling-back when Twilio/the call-centre number are unconfigured. Automated coverage:
`tests/test_duress_device.py` (17 tests) plus the pre-existing `tests/test_duress.py` (32 tests),
all passing alongside the full 481+ backend suite.

One real bug was found and fixed during this verification pass, worth knowing about: `device_code`
initially had no per-tenant uniqueness constraint, so two devices could be provisioned with the same
code, and the auth handshake would then crash with `MultipleResultsFound` (an unhandled 500) instead
of failing cleanly. Fixed with a `UniqueConstraint("tenant_id", "device_code")` on `DuressDevice`
(migration `97da879e0540`) and a clean 409 response on `POST /v1/duress-devices` when it's
violated -- see `app.services.duress_device.DeviceCodeConflictError`.

**Not yet built:** front-camera video capture on the Android tablet side (only mic audio exists
today), and any of the Android BLE client work itself (pairing, the GATT service consumption, the
two-way trigger handling) -- this document's BLE section (2) remains a locked contract for that
future Android work, not a description of code that exists yet on the tablet side.

---

## 1. System overview

Two independent paths reach the server. Neither depends on the other being alive.

```
                                    +---------------------------+
                                    |   Backend (FastAPI)        |
                                    |   /v1/duress/*  (tablet)   |
                                    |   /v1/duress/device/*      |
                                    |   /v1/devices/*            |
                                    |        |                   |
                                    |        v                   |
                                    |  DuressEvent (1 incident)   |
                                    |  correlated by              |
                                    |  vehicle_id / device_id     |
                                    |        |                   |
                                    |        v                   |
                                    |   Duress Desk (dashboard)   |
                                    |   "call the cab" (Twilio)   |
                                    +-------------^---------------+
                                                  |
                        +--------------------------+--------------------------+
                        |  4G / VoLTE (device's own SIM)                      |  4G/Wi-Fi (tablet's own data)
                        |                                                     |
              +---------+----------+                              +----------+-------+
              |  CT-DPD-01 device   |                              |  Android tablet   |
              |  SIM7600G-H + GNSS  |<---- BLE 5 (control only) -->|  meter app        |
              |  own mic, own GPS   |      Panic / Command /       |  front camera,    |
              |  8-12h battery      |      Status / Session        |  mic, own GPS     |
              +----------------------+      characteristics        +--------------------+
```

**Core design rule: Bluetooth LE carries control messages only -- never live video or bulk audio.**
A BLE 5 link running typical GATT notifications tops out in the tens of kbit/s in practice; it
cannot carry a video stream or even a real-time audio stream without unacceptable latency and
packet loss, and trying would burn the device's battery backup budget for no safety benefit. Every
byte of actual media -- the device's own mic recording, the tablet's camera/mic capture -- travels
over that side's own cellular connection, straight to the server. BLE's only job is to get a
trigger from one side to the other in well under a second and to carry a small heartbeat so each
side knows the link is alive. If you find yourself designing a BLE characteristic to carry audio
frames or JPEG chunks, stop -- that traffic belongs on the device's SIM or the tablet's data
connection, not the BLE link between them.

This gives the system two genuinely independent alarm paths for the same danger. A driver whose
tablet is switched off, out of battery, or physically destroyed is still covered -- the device
alarms and reports over its own SIM regardless. A device that loses its BLE bond to the tablet
(should not normally happen once paired, but is not treated as a failure state) still reports
independently over its own SIM; the tablet, likewise, is a complete duress path on its own even if
the device were never installed.

---

## 2. Roles

| Party | Owns | Independent of the other? |
|---|---|---|
| **Duress device (CT-DPD-01)** | Its own GPS fix (GNSS via SIM7600G-H), its own onboard mic, its own VoLTE voice path (auto-answers an inbound call to speaker+mic), 8-12h LiPo battery backup, its own 4G SIM. | Yes -- fully. Operates and reports to the server even if the tablet is off, removed, or destroyed. See CT-DPD-01 tech pack Section 1/3. |
| **Tablet / meter app** | Front-camera video + mic capture on duress trigger, its own GPS stream, the BLE bond to the device (central role -- see Section 3). | Yes for its own alarm -- the tablet can open and run a duress event with no device present. Not independent of the device for the device's own alarm: it only learns about a device-initiated alarm via the BLE Panic notification. |
| **Server + Duress Desk** | Correlates whichever side(s) report in for a given vehicle into one `DuressEvent` row (Section 6), gives the operator a single incident view with both GPS traces and both audio sources, and exposes a "call the cab" button that dials the device's own SIM via Twilio so the operator can talk through its speaker/mic. | N/A -- this is the merge point, not a third alarm source. |

Neither client-side party needs the other to be functioning for its own alarm to reach the server.
The value of the BLE link is that when both are present and working, a duress event started on
*either* side immediately arms the other too (Section 3.5), so a driver who reaches for the
concealed panic button also gets the tablet's camera rolling, and a driver who triggers the
tablet's hidden gesture also gets the device's independent cellular alarm and covert voice line
armed -- without the driver having to do both.

---

## 3. Bluetooth (BLE) integration

### 3.1 GATT roles

- **Device (CT-DPD-01) = BLE peripheral / GATT server.** It advertises, accepts a connection from
  the tablet, and hosts the GATT service described below.
- **Tablet = BLE central / GATT client.** It scans for and connects to the device, subscribes to
  its notify characteristics, and writes to its write characteristics.

This is the conventional split for a small always-on peripheral talking to a phone/tablet, and it
is what lets the device stay in a low-power advertising state until the tablet is actually in the
vehicle.

### 3.2 Security -- mandatory, not optional

This is a safety device. **Bluetooth Classic SPP running unencrypted-by-default is explicitly NOT
acceptable here** -- do not build the link that way, and do not fall back to it if BLE integration
gets difficult. The required security stack is two layers:

1. **LE Secure Connections (LESC) bonding** at the link layer, using the BLE 5 stack already
   specified for the ESP32-S3 (CT-DPD-01 tech pack Section 5/7). This gives you authenticated
   pairing and encrypted transport for every byte on the link, and survives reconnects without
   re-pairing.
2. **An app-layer HMAC/counter scheme on top of that**, applied to every message (Section 3.4). Do
   not rely on link-layer encryption alone: it protects the link, not the message's authenticity
   against a compromised or spoofed peer, and it gives you nothing if a future firmware revision
   or debug build ever runs the link unencrypted by mistake. The app-layer tag is the belt to
   LESC's suspenders.

Bonding is one-time, at pairing/provisioning time (ideally at fleet installation, not left to a
driver). Once bonded, both sides should reconnect automatically whenever the tablet is in BLE
range of the device (vehicle power-up, tablet reboot, etc.) without a human re-pairing step.

### 3.3 GATT service -- 4 characteristics

One custom GATT service, hosted by the device, with exactly four characteristics:

| Characteristic | Direction | Property | Purpose |
|---|---|---|---|
| **Panic** | device to tablet | Notify | Device tells the tablet a panic trigger just fired on the device side (button press, tamper, man-down). |
| **Command** | tablet to device | Write | Tablet tells the device to do something -- the main use is "alarm now" when duress was started on the tablet side (Section 3.5, path B), plus link-supervision acks. |
| **Status / heartbeat** | device to tablet | Notify | Periodic device health snapshot (battery, GNSS fix state, alarm state) and the heartbeat used for link supervision (Section 3.6). |
| **Session** | tablet and device | Write / Notify | Challenge-response key derivation at bond/session start -- the tablet writes a fresh challenge, the device notifies back a response derived from the shared session key, establishing (or refreshing) the per-session key material the HMAC in Section 3.4 signs with. Re-run whenever a new session starts (BLE reconnect after a gap), not on every message. |

Exact UUIDs are an implementation detail for the firmware/Android teams to agree and pin down
during integration -- this document fixes the *shape* (4 characteristics, these roles, this
message format) so both sides can build against a stable contract before UUIDs are finalized.

### 3.4 Binary message format

Every message on Panic/Command/Status (and the payload half of Session) uses the same fixed binary
envelope:

| Field | Size | Notes |
|---|---|---|
| `MSG_TYPE` | 1 byte | Identifies the message (panic_button, panic_ack, cmd_alarm, cmd_ack, heartbeat, status, session_challenge, session_response, and so on). Firmware and app teams maintain one shared enum. |
| `FLAGS` | 1 byte | Bit flags reserved for message-specific state (e.g. trigger_source in a panic message, on_battery in a status message). |
| `COUNTER` | `uint32` | Monotonically increasing per-device message counter. Never resets while bonded. Rejects replay: a receiver drops any message whose counter is not strictly greater than the last one it accepted from that peer. |
| `PAYLOAD` | variable | Message-specific body (e.g. a GPS-less trigger_source byte for Panic; nothing for a plain heartbeat). Kept small -- this is a control channel, not a data channel (see Section 1's core design rule). |
| `TAG` | 8 bytes | Truncated HMAC-SHA256, computed as HMAC-SHA256(session_key, MSG_TYPE + FLAGS + COUNTER + PAYLOAD), first 8 bytes. Computed over every preceding field. A receiver that cannot verify the tag drops the message outright -- it is never acted on, only logged. |

`session_key` is the key material established/refreshed by the Session characteristic (3.3), not
the long-term device secret used in the cellular auth handshake (Section 4.1) -- keep those two
keys and their derivations separate so a compromise of one channel does not compromise the other.

The counter plus the HMAC tag together mean a passive BLE sniffer that captures traffic gets
nothing usable: it cannot forge a valid Panic or Command message (no session key), and it cannot
replay a captured one (the counter check rejects it).

### 3.5 Two-way trigger sequences

The link is deliberately symmetric -- either side can start an incident and arm the other.

**Path A -- button pressed on the device:**

1. Driver presses the physical panic button on the CT-DPD-01 remote.
2. Device immediately (a) starts its own cellular alarm path -- opens/attaches an incident via
   `POST /v1/duress/device/alarm` (Section 4) over its own SIM, completely independent of BLE --
   and (b) sends a `panic_button` message on the **Panic** characteristic to the tablet, if
   currently bonded and in range.
3. Tablet receives the Panic notification, starts its own duress flow (front camera + mic capture,
   its own GPS stream, its own `POST /v1/duress/trigger` if it has not already opened an event for
   this vehicle), and sends a `panic_ack` back on **Command** (or a dedicated ack path -- the point
   is the device gets confirmation the tablet saw the trigger, for its own Status reporting).
4. The device's cellular alarm in step 2(a) does **not** wait for step 3's ack -- a tablet that
   never acknowledges (off, out of range, BLE never bonded) does not block or degrade the device's
   own alarm in any way.

**Path B -- duress started on the tablet:**

1. Driver triggers the tablet's own duress flow (hidden gesture, per the meter app's existing
   spec) -- `POST /v1/duress/trigger` fires immediately over the tablet's data connection.
2. Tablet sends a `cmd_alarm` message on the **Command** characteristic to the device.
3. Device receives it, verifies the HMAC tag and counter, and -- if valid -- arms its own alarm
   state exactly as if its physical button had been pressed: opens/attaches its own cellular
   incident via `POST /v1/duress/device/alarm`, auto-answers the covert inbound VoLTE call path,
   and sends a `cmd_ack` back to the tablet on **Status**.
4. As in Path A, the tablet's own alarm (step 1) already happened before the device ever responds
   -- the BLE round trip is a bonus that gets the device's independent cellular+voice channel
   armed too, not a dependency the tablet's own alarm waits on.

In both paths the two sides' `POST /v1/duress/*` calls happen independently and asynchronously;
the server-side correlation logic (Section 6) is what merges them into one incident regardless of
which arrived first or whether the other arrives at all.

### 3.6 Link supervision

The device sends a heartbeat on **Status** at a regular interval while bonded. If the tablet misses
enough consecutive heartbeats to conclude the link is down, it should surface this as **"duress
link degraded"** -- a warning state on the meter app, not an error, and not something that blocks
or disables anything. It is reported so a technician or the driver can address a genuine hardware
issue (device unplugged, BLE antenna fault, device out of the vehicle), but it is explicitly
**non-fatal**: the device is independently cellular-connected regardless of BLE state, and will
still alarm over its own SIM with no tablet involvement at all if the button is pressed while the
link is down. Do not design any safety-critical behavior -- on either side -- that depends on the
BLE link being up. BLE is a convenience/coordination channel layered on top of two independently
safe paths, not a dependency of either.

---

## 4. Device to Server cellular contract

This is the device's own path to the backend, entirely over its own SIM, independent of the
tablet. Field names below are taken directly from `app/schemas/duress_device.py` -- treat that
file as authoritative if this document and it ever drift apart.

### 4.1 Device auth -- `POST /v1/devices/auth`

The device proves it holds its shared secret (`K_dev`, provisioned at manufacture time -- see
Section 9) without ever transmitting the secret itself:

```
POST /v1/devices/auth
{
  "device_code": "<factory-provisioned device code>",
  "tenant_id": "<tenant this device belongs to>",
  "nonce": "<device-generated random string, 8-64 chars>",
  "hmac_hex": "<HMAC-SHA256(K_dev, nonce), as hex>"
}
```

The server looks up the device by `device_code` + `tenant_id`, decrypts its stored `K_dev`
(`DuressDevice.secret_encrypted` -- Fernet-encrypted at rest, see `app/core/crypto.py`),
recomputes the same HMAC over the supplied `nonce`, and compares. On success it returns:

```
{
  "device_token": "<short-lived bearer JWT>",
  "expires_in_minutes": <int>,
  "device_id": "<server-side row id>"
}
```

`device_token` is a short-lived JWT (`DURESS_DEVICE_JWT_EXPIRE_MINUTES`, 60 by default --
`app/core/config.py`) -- the device re-runs this handshake to get a fresh one when its current
token expires or it comes back online after a period offline. Every endpoint below requires this
token as a bearer credential.

A device with `active=false` on its `DuressDevice` row (e.g. reported stolen or decommissioned)
must be rejected at this handshake, before any token is issued.

### 4.2 `POST /v1/duress/device/alarm` -- open or attach an incident

```
{
  "vehicle_id": "<required>",
  "driver_id": "<optional, device may not know it>",
  "lat": <float, -90..90>,
  "lng": <float, -180..180>,
  "battery_pct": <optional int 0-100>,
  "trigger_source": "button | tamper | man_down",
  "device_event_id": "<optional, device's own local incident counter>"
}
```

`trigger_source` defaults to `"button"` if omitted. Response:

```
{ "event_id": "<server-side DuressEvent id>", "source": "device | both" }
```

This either opens a brand-new `DuressEvent` (if nothing else has reported for this vehicle yet) or
attaches to one the tablet already opened (see Section 6's correlation logic). `driver_id` may be
omitted -- the device usually does not know who is driving; correlation fills it in from a
matching tablet-side event where possible, otherwise dispatch confirms it manually.
`device_event_id` lets the device's own local incident numbering show up in its BLE Panic payload
for cross-referencing.

### 4.3 `POST /v1/duress/device/{event_id}/gps` -- batched fixes

```
{
  "points": [
    { "lat": 0.0, "lng": 0.0, "speed_kmh": 0.0, "accuracy_m": 0.0, "ts": "<iso8601, optional>" }
  ]
}
```

1 to 500 points per call. Unlike the tablet's `POST /v1/duress/{event_id}/gps`, which relays one
live point at a time over the tablet's normally-continuous data connection, the device is expected
to **buffer fixes locally and flush in a batch on reconnect** -- this is what makes the device
alarm path resilient to patchy cellular coverage rather than silently losing location data during
a dead zone. Firmware should keep taking GNSS fixes at its normal cadence even while the 4G modem
has no signal, and flush the backlog as soon as it does.

### 4.4 `POST /v1/duress/device/{event_id}/audio` -- the device's own mic recording

Multipart upload of the device's own onboard-mic audio capture for this incident. Kept as a
separate field on the merged incident (`DuressEvent.device_audio_ref`) from the tablet's own
`audio_ref`, so the Duress Desk can play either or both independently rather than one recording
overwriting the other.

### 4.5 `POST /v1/devices/{device_id}/heartbeat` -- idle health reporting

Independent of any open duress event -- this is how the fleet's devices report in during normal,
non-alarm operation:

```
{
  "battery_pct": <optional int 0-100>,
  "on_battery": <bool, default false>,
  "gnss_fix": <bool, default false>,
  "signal_csq": <optional int 0-31, GSM CSQ scale>,
  "firmware_version": <optional string, max 30 chars>
}
```

This updates the device's row directly (`battery_pct`, `on_battery`, `gnss_fix`, `signal_csq`,
`firmware_version`, `last_seen_at`) -- it is what lets a fleet manager see device health (battery
backing up, GNSS lock, signal strength, firmware version) across the whole fleet without waiting
for an incident.

---

## 5. Tablet to Server contract

The tablet's path is built, tested, and live-verified today -- `app/api/v1/duress.py` and
`app/services/duress.py`. It reuses the existing lifecycle rather than a device-specific parallel:

- `POST /v1/duress/trigger` -- opens the event (10-second self-cancel window).
- `POST /v1/duress/{event_id}/cancel` -- driver self-cancel, inside the cancel window only.
- `POST /v1/duress/{event_id}/escalate` -- dispatcher-only, advances the fixed 4-stage escalation
  cascade; the final stage fires a real (or mock-fallback) Twilio Voice call to an emergency
  contact.
- `POST /v1/duress/{event_id}/close` -- dispatcher-only, resolves the event.
- `POST /v1/duress/{event_id}/gps` -- one live GPS fix per call, relayed (not persisted) to every
  dashboard listener on `WS /v1/duress/{event_id}/live`; the meter app streams one of these
  roughly every 5 seconds while an event is active.
- `POST` / `GET /v1/duress/{event_id}/audio` -- multipart upload and playback of the tablet's own
  mic recording (`DuressEvent.audio_ref`). This is already implemented on both backend and
  Android (`DuressAudioRecorder`/`DuressController` -- the Android app records and uploads real
  audio today, capped at a fixed maximum duration per event).

**Front-camera video capture is not yet built.** Say this plainly rather than implying otherwise:
the mic path above is real and working; a corresponding front-camera video capture-and-upload path
on the Android side is a separate, not-yet-started implementation task. When it is built, the
natural shape is the same pattern as audio -- record on trigger, upload via a new
`POST /v1/duress/{event_id}/video` (or a multipart video field alongside audio), capped at a
sensible duration, event-scoped only per Section 8. Do not build this as a change to the BLE
contract in Section 3 -- it is tablet-to-server traffic over the tablet's own data connection, not
a BLE payload.

---

## 6. Server correlation and the Twilio "call the cab" flow

One `DuressEvent` row represents one real-world incident, whether it was reported by the tablet,
the device, or both. `DuressEvent.device_id` and `DuressEvent.source` (`"tablet"`, `"device"`, or
`"both"`) carry this: whichever side reports first opens the row with its own source tag; the
second side's first report for the same `vehicle_id` attaches to the existing row instead of
opening a duplicate one, and flips `source` to `"both"`.

This attach-or-open logic is `app.services.duress_device.open_or_attach_device_alarm` (referenced
from `DeviceAlarmRequest`'s docstring in `app/schemas/duress_device.py`) -- as noted in the status
note at the top of this document, that module had not landed as of this writing, so treat its name
and behavior here as the specified contract to build against: look up an open, non-terminal
`DuressEvent` for the same `vehicle_id` first; attach (`device_id` set, `source` set to `"both"`)
if one exists, otherwise open a fresh one with `source="device"`. The equivalent tablet-side open
path (`trigger_event` in `app/services/duress.py`, already implemented) does not yet perform the
mirror-image lookup for an existing device-opened event -- that is the other half of the same
correlation logic and belongs in the same piece of work.

Once merged, the Duress Desk (dashboard) shows one incident with both GPS traces (tagged
`source: "tablet"` / `source: "device"` on the live relay payload so they render as distinguishable
tracks on one map) and both audio sources (`audio_ref` for the tablet's mic, `device_audio_ref` for
the device's own mic) side by side.

**"Call the cab":** `POST /v1/duress/{event_id}/call` dials the device's own SIM
(`DuressDevice.phone_number`) via Twilio, so a Duress Desk operator can talk through the vehicle's
speaker and hear through its mic -- this is deliberately a *different* call target from
`DuressEscalateRequest.emergency_contact_phone`, which dials an external emergency contact as part
of the escalation cascade. Its request/response shapes (`DuressCallRequest`/`DuressCallResponse`
in `app/schemas/duress.py`) and its result-storage column (`DuressEvent.device_call_result_json`)
already exist; the route itself was not yet wired into `app/api/v1/duress.py` as of this writing.
Build it to the same mock-fallback convention every other paid integration in this codebase
follows -- see `app/services/receipts.py`'s `_twilio_configured()` (checks all three of
`TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER`) and
`app/services/duress.py`'s `place_escalation_call` (real Twilio `Calls.json` POST when configured;
a clearly-flagged `{"mock": true, ...}` response otherwise, so the flow is fully testable without
live Twilio credentials). The call should require the event to already have a `device_id` attached
-- there is nothing to dial otherwise -- and the target number is `DuressDevice.phone_number`, not
`DURESS_ESCALATION_CALL_PHONE` (that setting is the escalation-cascade's contact, unrelated to this
button). `DURESS_CALL_FROM_NUMBER` (falling back to `TWILIO_FROM_NUMBER` if unset -- see
`app/core/config.py`) is the Twilio number this call should be placed from.

---

## 7. Samsung Knox tablet lockdown

The in-cab tablet is locked down via Samsung Knox Manage so it can only run Google Maps, Waze, and
the meter app -- no Settings access, no other apps reachable, survives a reboot. The one policy
choice that matters to this document: **Bluetooth stays enabled**, because it is the transport for
the entire device-integration link described in Section 3 above; every other radio/access
restriction in the lockdown profile is compatible with that. Full enrollment, kiosk-profile
configuration, and verification steps are in
**[`KNOX_LOCKDOWN_RUNBOOK.md`](./KNOX_LOCKDOWN_RUNBOOK.md)**.

---

## 8. Data protection and retention

- **Event-scoped capture only.** The tablet's camera, mic, and any live-listen capability are
  active *only* while a duress event is open (`open` / `escalating` / `dispatched`) for that
  vehicle. There is no standby "listen to the cab" mode, no background recording, and no way for
  dispatch or a monitoring party to activate camera/mic/live-audio outside an actual open incident.
  This applies equally to the device's own mic -- it captures for an incident, not continuously.
- **In-cab signage.** Vehicles fitted with the duress device and camera-capable meter app should
  carry visible signage disclosing that a safety/duress system with camera and audio capability is
  installed and activates only during an emergency -- standard practice for in-vehicle safety
  systems and expected under Australian privacy/surveillance-device rules for a vehicle used as a
  workplace and carrying members of the public.
- **Retention window.** Duress recordings (audio today; video once built) should be retained for a
  fixed, documented window and then deleted, not kept indefinitely. The exact number of days is a
  business decision, not an engineering one -- see Section 10.
- **First-party only.** Duress recordings and live location are for this platform's own Duress
  Desk and dispatch operations. They are not shared with an external third party (e.g. a
  monitoring centre partner) as part of this contract. A previous attempt to design an external
  monitoring-partner integration for this system was deliberately not built -- see
  `PROJECT_HANDOFF.md`'s note on the blocked monitoring-partner duress panel -- because sharing
  driver PII, live GPS, and audio with an outside party is a real business/privacy decision (and,
  per that PDF's own text, requires a Privacy Act data-sharing agreement) that needs explicit
  authorization, not an inference from a planning document. If that changes, scope it as its own
  documented decision, not a quiet extension of this contract.

---

## 9. Firmware requirements checklist

For the OEM/electronic engineer building the CT-DPD-01 firmware. Cross-reference the hardware BOM
and mechanical spec in `docs/CT-DPD-01_Tech_Pack_for_TY-EMS.md`.

- [ ] BLE peripheral (GATT server) role on the ESP32-S3, hosting the 4-characteristic service:
      Panic (notify), Command (write), Status/heartbeat (notify), Session (write/notify).
- [ ] LE Secure Connections bonding, plus the app-layer HMAC-SHA256/counter scheme on every
      message (Section 3.2/3.4) -- Bluetooth Classic SPP is not an acceptable substitute.
- [ ] Panic-button debounce, with the cellular alarm path starting **immediately** on a valid
      press -- do not gate the cellular alarm on the BLE notification to the tablet succeeding.
- [ ] SIM7600G-H cellular registration (AU bands incl. B28), the `POST /v1/devices/auth` device-
      JWT handshake, and the GPS/audio ingest calls (`.../gps`, `.../audio`) against the
      device_token returned from that handshake.
- [ ] Offline GPS buffering -- keep taking GNSS fixes even with no cellular signal, and flush the
      backlog as a batch (`POST /v1/duress/device/{event_id}/gps`, up to 500 points/call) the
      moment connectivity returns.
- [ ] Auto-answer an inbound VoLTE call silently, routed straight to speaker + mic -- no ring
      tone, no visible indication to anyone in the vehicle other than the (downward-facing,
      hidden) LED.
- [ ] Idle-state heartbeat reporting to `POST /v1/devices/{device_id}/heartbeat` (battery, on-
      battery flag, GNSS fix state, signal CSQ, firmware version) on a regular interval,
      independent of whether any duress event is open.
- [ ] 8-12 hour LiPo battery backup, with boot-to-operational in under 30 seconds on power loss.
- [ ] Manufacture-time provisioning step: `device_id` (or `device_code`), `device_secret`
      (`K_dev`), and the whitelisted server number/endpoint burned into firmware at manufacture,
      matching the `DuressDeviceCreate` admin-provisioning record the server holds for that unit
      (see `app/schemas/duress_device.py`) -- this is the shared secret both
      `POST /v1/devices/auth` and every BLE app-layer HMAC ultimately trace back to, so it must
      never leave the device except as an HMAC output, and the server's copy is Fernet-encrypted
      at rest.

---

## 10. Open items -- business side, not engineering

These block a production rollout but are not engineering decisions:

- **Twilio AU phone number** -- needed for both the escalation-call flow and the "call the cab"
  button to originate from a real Australian number rather than the current mock-fallback.
- **SIM data + voice plan(s) with B28 coverage** for the device fleet, sized for its data volume
  (heartbeats + GPS batches + occasional audio uploads) and VoLTE voice minutes.
- **Knox Suite / Knox Manage licences** for the tablet fleet -- see the runbook's prerequisites.
- **Final retention window (in days)** for duress audio/video recordings (Section 8) -- needs a
  decided number, not just "not indefinite."
