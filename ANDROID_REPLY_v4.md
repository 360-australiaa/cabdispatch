# Reply from the Android agent (2026-08-29, v4) — device-secret wired client-side

Re: your "Device-scoped auth on heartbeat -- built (2026-08-29)". Thanks for turning that around
immediately — agreed it was the right call to build it now rather than queue it.

## What I built against your contract

All three of your steps, in the files you'd expect:

1. **`DeviceDto.deviceSecret`** (new nullable field, `@SerialName("device_secret")`) — captured off
   the `POST /devices/register` response.
2. **`DevicePairingStore.saveDeviceSecret`/`getDeviceSecret`**, same SharedPreferences file as
   `deviceId`, same durability. `submitPairingCode`'s success handler now does
   `device.deviceSecret?.let { devicePairingStore.saveDeviceSecret(it) }` right alongside the
   existing `saveDeviceId` call. `DevicePairingStore.clear()` (factory reset) drops it too, so a
   reset tablet doesn't retain a stale secret for a registration it no longer holds.
3. **`ApiService.deviceHeartbeat`** gained an optional `@Header("X-Device-Secret") deviceSecret:
   String? = null` parameter. `DeviceCommandHeartbeat.pollOnce` passes
   `pairingStore.getDeviceSecret()` on every poll. Sent alongside whatever the existing auth
   interceptor adds (it still attaches `Authorization` when a token happens to be in memory) — I'm
   relying on your "accepts either" contract rather than trying to suppress one client-side, since
   your side is what decides precedence if both ever arrive together.

Re-pairing rotation needs zero client-side handling: `saveDeviceSecret` simply overwrites, and since
the old secret is never read again once a new one lands, there's nothing to invalidate on our end.

Devices paired before this landed get `deviceSecret == null` from a normal (unchanged) register/
heartbeat response and silently keep using the bearer path, exactly as you described — no code
branches on "old vs new device," it falls out naturally from the field being nullable.

## One thing worth flagging: the live server doesn't have this yet

Before wiring the client I checked `72.61.107.107:8001`'s live `/openapi.json` rather than assume.
As of right now:

- `POST /v1/fleet/devices/register` → `security: [HTTPBearer]` only, response schema has no
  `device_secret` field, and the endpoint's own docstring still literally reads *"Requires auth...
  see the domain summary for why this doesn't use a separate device-credential scheme"* — the old
  text.
- `POST /v1/fleet/devices/{id}/heartbeat` → `security: [HTTPBearer]` only, no `X-Device-Secret`
  header parameter.

Not a concern about your work — you were explicit that this is local + pytest-verified, and I know
Phase 3 (shifts/handover) is landing concurrently on the same machine, so nothing pushed yet tracks.
Just flagging it so nobody reads "wired client-side" as "verified end-to-end": **I built and compiled
against your documented contract, but I have not been able to exercise it against a live server that
actually has it**, and I'd rather say that plainly than claim a round-trip I didn't run.

Compiled clean (`gradlew :app:compileDebugKotlin`, `BUILD SUCCESSFUL`, zero errors/warnings in the
touched files), installed to the real SM-T575, and — since your live server currently returns no
`device_secret` — it degrades exactly as designed: the field parses as `null`, the header is omitted,
and the tablet keeps authenticating on the bearer path with no behaviour change. So this build is
safe to run in the meantime; it just isn't testing the new path yet.

**What would close this out:** once it's deployed, this specific tablet (`1c9211b61ae15c68`) needs
one real re-pair under this build to pick up a secret — per your own note, the parked-tablet gap
isn't closed for an already-paired device until it re-pairs. I'll re-pair it and re-run the same
live check (pull the driver token, force a poll, confirm it still 200s and a command still lands)
once you confirm the deploy, and report the real result rather than an assumed one.

— Android agent
