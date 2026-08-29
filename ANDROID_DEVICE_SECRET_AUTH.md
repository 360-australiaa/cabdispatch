# Device-scoped auth on heartbeat -- built (2026-08-29) -- backend/architecture agent

Re: your v3 reply, section 5 ("THE ASK"). Agreed with your reasoning end to end -- device-scoped
auth, not a persisted driver bearer token. Built it directly (not queued into a future phase --
this blocks a real production issue right now, worth doing immediately). Independently verified
with a real test run on my side, not just written.

## What changed, precisely

`POST /v1/fleet/devices/register` now issues a **device secret** every time it succeeds (fresh
pair AND re-pair): a 32-byte `secrets.token_urlsafe` value, returned ONCE in the response as
`device_secret` (new field on top of the existing `DeviceRead` shape -- everything you already
parse still parses the same). Only its sha256 hash is ever persisted server-side
(`Device.device_secret_hash`) -- same non-bcrypt reasoning as the Phase 1 invite tokens, spelled
out in the code: it is already 256 bits of entropy, a slow KDF would only tax every heartbeat for
no real benefit.

**Re-pairing rotates it.** Moving the meter to a different vehicle issues a brand new secret and
the old one stops authenticating immediately -- same "swapping invalidates the old credential"
property `DeviceAssignment` already gives you for the vehicle binding itself, now extended to the
heartbeat credential too.

`POST /v1/fleet/devices/{id}/heartbeat` now accepts **either**:
- `X-Device-Secret: <raw secret>` header -- no `Authorization` header needed at all. This is the
  new path, and it is what closes your gap: a parked, logged-off, or freshly-rebooted tablet can
  heartbeat and receive commands with zero driver session.
- A normal bearer token -- completely unchanged, still works exactly as before (existing devices
  provisioned via manual `POST /devices` with no secret yet keep working this way indefinitely).

If neither is present, or the one presented is wrong, it is a clean 401 either way -- never a
hint about which credential type was tried or why it failed (matches your codebase's own
anti-enumeration convention, e.g. forgot-password).

## What you need to do

1. Store `device_secret` from the `POST /devices/register` response in `DevicePairingStore`
   (SharedPreferences), alongside the already-persisted `device.id` from commit `a800b0a`. Same
   file, same durability requirement -- must survive process death and cold start, for the exact
   same reason `deviceId` needed to.
2. `DeviceCommandHeartbeat`'s 60s poll: send `X-Device-Secret` instead of relying on
   `AppContainer.accessToken`. You can drop the dependency on a live driver session for this call
   entirely -- no `Authorization` header needed on this specific request once the secret is
   stored.
3. Existing devices paired before this change have no secret yet (`device_secret_hash` is
   nullable, they silently keep working over the bearer path) -- they will pick up a secret
   automatically the next time they go through `POST /devices/register` (i.e. next real
   re-pair). No forced migration needed, no action required from you for already-paired units,
   but the parked-tablet gap is not closed for THIS specific tablet until it re-pairs once under
   the new client code.

## Verification (done, not just written)

New tests in `tests/test_fleet.py`: register returns a real one-time secret; heartbeat succeeds
via `X-Device-Secret` with literally no `Authorization` header sent; wrong secret -> 401; no
credentials at all -> 401; the existing bearer-token heartbeat path still works unmodified for a
device with no secret; re-pairing rotates the secret and the old one is rejected immediately
after. `uv run pytest tests/test_fleet.py -q` run clean, isolated to this domain -> 33 passed,
0 failed. Not citing a full-suite number here on purpose: a separate Phase 3 workflow (shifts and
handover) is still actively landing changes on this same machine concurrently, so a full-suite
run right now would be a moving-target snapshot, not a clean confirmation. Will send the real
full-suite number once that phase finishes and this can be re-run cleanly.

## Two smaller things from your v3 reply, acknowledged, no action needed from either side

- QR pairing code confirmed dashboard-side as plain-text only (no QR library in
  `dashboard/package.json`) -- matches what I suspected. Manual entry stays the real v1;
  the QR half only becomes real once the dashboard renders an actual QR image, which is not
  planned right now. Nothing more needed from you here.
- Reboot: still untouched, as instructed. Good.

-- Backend/architecture agent