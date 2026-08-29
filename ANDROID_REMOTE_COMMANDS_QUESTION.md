# Remote commands not working for Lilly Cabs live device (2026-08-28) -- backend/architecture agent

Real bug report from the user, not a hypothetical: on the dashboard, Lilly Cabs device
android_id `1c9211b61ae15c68` (model SM-T575, vehicle KHI-01 -- this looks like your real test
tablet) has kiosk_locked=Locked, plus force_update/locate/reboot all showing "Pending" -- meaning
an admin toggled all four via the dashboard. None of it visibly does anything on the physical
tablet.

## The mechanism (confirmed directly from the backend source, not assumed)

`POST /devices/{id}/kiosk-lock`, `/force-update`, `/locate`, `/reboot` are PURE flag-set
endpoints -- they write one boolean column each, nothing more. `POST /devices/{id}/heartbeat`
is a ONE-WAY channel FROM the device (writes last_seen_at/battery/network/app_version) -- it does
NOT push anything TO the device. There is no separate push channel anywhere in this backend.

For any of these four to actually do something on the tablet, YOUR app has to:
1. Call heartbeat regularly (already true if it is calling it at all).
2. Read `kiosk_locked` / `force_update_pending` / `locate_requested` / `reboot_requested` back
   from the heartbeat response (a `DeviceRead` object -- all four fields are in there).
3. Actually ACT on each one client-side -- enforce kiosk mode, trigger an update check, get a
   fresh location fix and report it, etc.

I have not found any mention in your prior status report of step 3 being built or tested for any
of the four. If it genuinely is not built yet, that is the actual root cause -- the flags being
"Pending" forever server-side is completely expected in that case; nothing is broken on the
backend, there is just no client-side consumer.

## `reboot_requested` specifically: do not build anything for this one right now

This is permanently, deliberately non-functional today, documented directly in the backend model
(`app/models/fleet.py`, `Device.reboot_requested`'s own code comment): no on-device app code can
make Android actually reboot the OS without the tablet being enrolled as Android Device Owner via
zero-touch/QR provisioning at device setup time -- which has never been built and is out of scope
unless the user explicitly asks for that provisioning work separately. Leave this one alone.

## Real question I cannot answer from here

Your last status report said, verified by grep: `ApiService.registerDevice` has ZERO real call
sites, and `SessionHolder.deviceId` is always null with nothing ever setting it -- meaning device
pairing/heartbeat should be a structural no-op. But the dashboard shows `last_seen_at: 6m ago` for
this exact device (real-looking android_id, model matching your test tablet, vehicle matching your
test vehicle) -- something DID successfully call heartbeat recently, with `app_version` populated
but `battery`/`network` both null (both are optional fields on that request).

Two questions:
1. Was that heartbeat call from the real app, or a manual/curl test call you (or a prior session)
   made directly against the endpoint to sanity-check it? If manual, that fully explains the null
   battery/network (a minimal test body) and there is no real contradiction.
2. If it really was the live app: has something changed since your last report that gives
   `SessionHolder.deviceId` a real value in some code path you did not examine before (e.g. a
   lookup-by-android_id fallback, not the persisted var)? Worth a quick recheck before building the
   real pairing screen from `ANDROID_PAIRING_UX_SPEC.md`, since if a working heartbeat path already
   exists, kiosk-lock/force-update/locate enforcement might be the ONLY missing piece, not the
   whole registration flow.

## What would actually close this out

Once you confirm whichever of the above is true: build the "read the four flags from heartbeat
response, act on them" logic client-side for kiosk-lock/force-update/locate (skip reboot, see
above). This is a smaller, separate ask from the pairing-screen spec I sent earlier -- worth
tackling either order, your call, but the user is blocked on kiosk-lock specifically right now for
a real device in production, so it is probably the more urgent of the two.

-- Backend/architecture agent