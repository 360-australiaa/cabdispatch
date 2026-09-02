# Duress Desk audit follow-up: device provisioning, audio playback, snapshot gallery

Frontend-only fixes for three gaps found in a Duress Desk audit. No backend
files were touched — every endpoint used here already existed and was
already permission-gated. All new/changed files live under
`dashboard/src/pages/duress/`.

Verified live against a running backend at `http://127.0.0.1:8001`, logged
in as `owner@lillycabs.test`. See "Live verification" at the end for the
exact requests and responses.

---

## 1. Duress hardware (device) provisioning UI

Backend: `backend/app/api/v1/duress_device.py` (full CRUD on
`/v1/duress-devices`), schemas in `backend/app/schemas/duress_device.py`,
model in `backend/app/models/duress_device.py`.

**Role policy actually implemented by the backend** (read from
`duress_device.py`'s own docstring + route decorators, not assumed):
list/get (`GET /v1/duress-devices`, `GET /v1/duress-devices/{id}`) is any
authenticated tenant user — read-only visibility. Create/update/rotate-
secret/delete are `owner`/`admin`/`dispatcher` only
(`_DISPATCH_ROLES`, same gate as `app/api/v1/duress.py`'s escalate/close).

**Key finding that shaped the whole design:** the secret is **write-only in
both directions that matter for this UI** — `DuressDeviceCreate.plaintext_secret`
and `DuressDeviceRotateSecretBody.plaintext_secret` are both *inputs* the
operator supplies (the K_dev shared secret is burned into / re-flashed onto
the physical unit's firmware first), and `DuressDeviceRead` never includes
the secret in any form. Confirmed live: `POST .../rotate-secret` returns a
plain `DuressDeviceRead` with no secret field at all
(see `dashboard/src/pages/duress/RotateSecretModal.tsx`'s doc comment,
lines 9-21, and the live check below). This means the
task's speculative "reveal it once, in a copyable field, like the fleet
pairing-code modal" pattern **does not apply** — there is nothing the server
ever generates or returns to reveal. Instead, both the create form
(`DeviceFormModal.tsx`) and the rotate-secret modal (`RotateSecretModal.tsx`)
are input forms with an explicit warning that the value typed in is
encrypted at rest immediately and cannot be read back by this or any other
screen — matching the model's own module docstring
(`backend/app/models/duress_device.py:13-21`).

**Files:**
- `dashboard/src/pages/duress/types.ts:165-220` — `DuressDevice`,
  `DuressDeviceListResponse`, `DuressDeviceCreateBody`,
  `DuressDeviceUpdateBody`, `DuressDeviceRotateSecretBody`, mirroring the
  backend schemas field-for-field.
- `dashboard/src/pages/duress/api.ts:112-170` — `listDuressDevices`,
  `createDuressDevice`, `updateDuressDevice`, `rotateDuressDeviceSecret`,
  `deleteDuressDevice`, `listVehicleOptionsForDeviceLink`.
- `dashboard/src/pages/duress/DevicesPanel.tsx` (new) — list table (device
  code, linked vehicle rego, phone, battery, last seen, active badge),
  role-gated write affordances (`MANAGE_ROLES`, mirroring
  `_DISPATCH_ROLES`), register/edit/rotate/delete actions.
- `dashboard/src/pages/duress/DeviceFormModal.tsx` (new) — create (device
  code + vehicle link + phone + shared secret) and edit (vehicle link +
  phone + active toggle) in one component, since the backend's create vs.
  update schemas genuinely differ (device_code and the secret are create-only;
  `active` is update-only).
- `dashboard/src/pages/duress/RotateSecretModal.tsx` (new) — confirmation-
  gated (explicit checkbox + destructive button) secret rotation.
- `dashboard/src/pages/duress/index.tsx` — added an "Events / Devices" tab
  switcher (`ViewTab`, `TabButton`), mirroring the exact tab pattern already
  used in `dashboard/src/pages/compliance/index.tsx:38-79`. The "Trigger
  event" header action only shows on the Events tab.

**Deviations from the task brief's literal wording, both intentional and
both driven by what the backend schema actually supports (read first, per
instructions):**
- *"Delete (deactivate)"* — the backend's `DELETE /v1/duress-devices/{id}`
  is a real hard delete (`await session.delete(device)`), not a soft
  deactivate. `DuressDeviceUpdate.active` is the actual deactivation
  mechanism. The UI reflects this honestly: the delete confirmation modal
  says "This permanently removes the device record… This cannot be undone"
  and points at "Edit → Active" for a reversible deactivation instead of
  conflating the two operations.
- *"Edit (e.g. relink to a different vehicle, rename)"* — `DuressDeviceUpdate`
  has no `device_code` field, so renaming isn't something the backend
  supports post-creation (`device_code` is immutable by design — see that
  column's own comment in `duress_device.py`, "so the device can be
  re-provisioned... without changing its physical label"). The edit form
  only exposes what the schema actually allows: vehicle link, phone number,
  active flag.

---

## 2. Audio playback for a captured duress recording

Backend: `GET /v1/duress/{event_id}/audio`
(`backend/app/api/v1/duress.py:350-381`) streams `event.audio_ref` as a
`FileResponse`; role policy is any authenticated tenant user
(`get_current_user`), same as `/trigger`/`/cancel`/`/gps`.

**File:** `dashboard/src/pages/duress/DuressAudioPlayer.tsx` (new). Since the
route needs the same bearer auth as every other duress route, a plain
`<audio src="...">` can't hit it directly — this fetches the file via
`apiClient` with `responseType: "blob"` (same pattern as
`downloadComplianceDocument`/`downloadVehicleEvidencePack` and this same
page's existing `CameraSnapshotPanel.tsx`), builds an object URL, and sets
that as a real `<audio controls>` element's `src`. Object URLs are revoked
on every `eventId`/`audioRef` change and on unmount. Renders `null`
entirely when `audio_ref` is absent (confirmed: no player, no placeholder —
literally nothing, per the brief).

Wired into `dashboard/src/pages/duress/EventDetailPanel.tsx:225`
(`<DuressAudioPlayer eventId={event.id} audioRef={event.audio_ref} />`),
added *alongside* the existing raw-text "Audio ref" field
(`EventDetailPanel.tsx:192`), not replacing it — the raw path is still
useful for an admin cross-checking the on-disk file.

**Honest gap I found and did NOT paper over:** the task brief assumed
`device_audio_ref` (the physical CT-DPD-01 device's own captured audio)
would also get a player. Reading `backend/app/api/v1/duress.py` and
`backend/app/api/v1/duress_device.py` in full turned up **no `GET` route
that streams `device_audio_ref` back** — only a `POST
/v1/duress/device/{event_id}/audio` *upload* route exists for it. There is
nothing to fetch. `device_audio_ref` is therefore left exactly as it was —
a raw text field — because building a player against a nonexistent endpoint
would mean fabricating one, which the task explicitly forbids. This is a
real, separate backend gap worth flagging for a future pass, not something
fixable from the frontend.

---

## 3. Snapshot gallery / scrub bar

Backend: `GET /v1/duress/{event_id}/snapshots` (list metadata, newest
first) and `GET /v1/duress/{event_id}/snapshot/{snapshot_id}` (one frame's
bytes) — both in `backend/app/api/v1/duress.py:484-553`, both any
authenticated tenant user.

**File:** `dashboard/src/pages/duress/SnapshotGallery.tsx` (new), rendered
by `CameraSnapshotPanel.tsx` (which still owns the single "latest frame,
auto-refreshing" live view — unchanged). The gallery is a genuine
prev/next scrub bar, not a wall of thumbnails:
- Fetches the metadata list only (`listDuressSnapshots`,
  `dashboard/src/pages/duress/api.ts:78-90`) — cheap even for a long
  incident with hundreds of frames.
- A row of small timestamp buttons (oldest→newest, left→right) plus
  explicit Prev/Next controls let a dispatcher jump to or step through any
  captured frame.
- Only the **currently selected** frame's image bytes are ever fetched, via
  the same authenticated-blob-fetch-then-object-URL pattern as
  `CameraSnapshotPanel`'s existing "latest" viewer — never all frames at
  once, so browsing a long sequence stays cheap.
- Jumps to the newest frame automatically when `latestSnapshot?.snapshot_id`
  (the `kind:"snapshot"` websocket notification `useDuressLiveGps` already
  surfaces) changes, so a dispatcher watching a live incident sees new
  frames land without manual refresh; renders nothing when the event has no
  captured frames at all (`CameraSnapshotPanel`'s own empty state already
  covers that case, so this doesn't duplicate it).

---

## Verification

### `npm run lint` (`tsc --noEmit`)

```
> cab-dispatch-dashboard@0.1.0 lint
> tsc --noEmit
```

Zero errors, zero warnings.

**Note on `npm run build`:** running the stricter `tsc -b && vite build`
surfaces two **pre-existing, unrelated** errors in
`dashboard/src/pages/compliance/index.tsx` (`canManage` used outside the
scope it's declared in, lines 207 and 253) — a file this task never
touched (confirmed via `git status`: only files under
`dashboard/src/pages/duress/` are modified/new). This is a real bug
elsewhere in the codebase surfaced only by the project-referenced build
config, not something introduced or fixable within this task's scope.
`npm run lint`, the verification command actually specified for this task,
passes clean.

### Live backend checks (`http://127.0.0.1:8001`, `owner@lillycabs.test`)

All of the following were run for real against the running dev backend
(not fabricated):

| Request | Result |
|---|---|
| `GET /v1/duress-devices` | `200`, `{items, total, limit, offset}` |
| `GET /v1/fleet/vehicles?skip=0&limit=200` | `422` — **caught a real bug**: the endpoint's server-side cap is `limit<=100`, not 200. Fixed `listVehicleOptionsForDeviceLink` to request `limit=100` before shipping. |
| `GET /v1/fleet/vehicles?skip=0&limit=100` | `200`, `{id, rego, ...}` shape confirmed |
| `POST /v1/duress-devices` (device_code + vehicle_id + phone_number + plaintext_secret) | `201`, full `DuressDeviceRead`, no secret in response |
| `PATCH /v1/duress-devices/{id}` (`{active:false}`) | `200`, updated row |
| `POST /v1/duress-devices/{id}/rotate-secret` (`{plaintext_secret:...}`) | `200`, plain `DuressDeviceRead` — **confirms no secret is ever returned**, validating the no-reveal-once UI design |
| `DELETE /v1/duress-devices/{id}` | `204` |
| `POST /v1/duress/trigger` | `201`, opened a real test event |
| `GET /v1/duress/{id}/snapshots` (before upload) | `200`, `{items:[], total:0}` |
| `GET /v1/duress/{id}/audio` (before upload) | `404`, `{detail:"No audio recording on file..."}` — confirms the empty-state path |
| `POST /v1/duress/{id}/audio` (multipart, fake `.m4a`) | `200`, `audio_ref` set |
| `GET /v1/duress/{id}/audio` (after upload) | `200`, `content-type: audio/mp4`, real bytes streamed back — confirms the blob-fetch path works end-to-end |
| `POST /v1/duress/{id}/snapshot` (multipart, fake `.jpg`) | `201`, `DuressSnapshotRead` shape confirmed |
| `GET /v1/duress/{id}/snapshots` (after upload) | `200`, one item, exact shape matches `DuressSnapshotMeta` |
| `GET /v1/duress/{id}/snapshot/{snapshot_id}` | `200`, `content-type: image/jpeg`, real bytes |
| `DELETE /v1/duress/{id}` | `204` (test event cleaned up) |

**What I could NOT fully verify live:** actual `<audio controls>` playback
and `<img>` rendering in a real browser — the curl checks above confirm the
HTTP contract (status codes, content-types, and that the blob bytes come
back correctly), but I did not drive a browser against the dashboard UI
itself in this pass. The uploaded test files were synthetic (a few bytes of
non-real audio/image data, not real recordings), since no real duress
hardware exists in this dev environment — exactly the caveat the task
brief anticipated.
