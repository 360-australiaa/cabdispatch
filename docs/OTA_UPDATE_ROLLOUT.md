# OTA Update Rollout — Cab Dispatch Meter App

How a platform owner publishes a new build of the Android meter app, and what has to be true on a
real fleet tablet before that build actually installs. Written for whoever operates the platform
backend and the Knox Manage console — see
**[`KNOX_LOCKDOWN_RUNBOOK.md`](./KNOX_LOCKDOWN_RUNBOOK.md)** for the kiosk lockdown this update
path has to coexist with.

**Read this before assuming a published release reaches a tablet on its own.** It does not, on a
tablet locked down per the runbook above, until Section 2 below is done — and even then, every
install still needs one human tap on the tablet (Section 3).

---

## 1. Publishing a new release (platform owner only)

Backend contract (`backend/app/api/v1/app_releases.py`, `backend/app/models/app_release.py`):

- Releases are **platform-wide, not per-tenant** — this is one Android app codebase serving every
  tenant on the platform, so there is no per-tenant APK build and no `tenant_id` on the
  `app_releases` table at all.
- Only a platform owner (`role == "owner"` AND the user's own `tenant_id == PLATFORM_TENANT_ID` —
  same gate as every other `/v1/platform/...` route, see `app/api/v1/platform.py::require_platform_owner`)
  may publish. An ordinary tenant owner gets 403.

### 1.1 Upload a build

```bash
curl -X POST https://<backend-host>/v1/platform/app-releases \
  -H "Authorization: Bearer <platform-owner JWT>" \
  -F "file=@app-release-unsigned.apk;type=application/vnd.android.package-archive" \
  -F "version_code=7" \
  -F "version_name=0.2.0" \
  -F "release_notes=Fixes the pickup-address bug; adds real turn-by-turn nav."
```

`version_code` must exactly match the `versionCode` the APK was actually built with
(`android/app/build.gradle.kts`'s `defaultConfig.versionCode`) — it is what every device compares
against its own `BuildConfig.VERSION_CODE`, not `version_name`, which is purely a display string.
The backend rejects a duplicate `version_code` with `409 Conflict` rather than silently
overwriting a prior release for that build. The server computes and stores the APK's SHA-256
itself at upload time — never trust or accept a client-supplied hash for this.

Response (`201 Created`):

```json
{
  "id": "…",
  "version_code": 7,
  "version_name": "0.2.0",
  "release_notes": "Fixes the pickup-address bug; adds real turn-by-turn nav.",
  "is_active": true,
  "sha256": "…",
  "created_at": "…",
  "updated_at": "…"
}
```

**No dashboard UI for this exists yet** — this is a curl/API-only operation today. TODO for a
future pass: a platform-console screen (`/v1/platform/app-releases`) so this doesn't require a
terminal.

### 1.2 Unpublish a bad build

```bash
curl -X PATCH https://<backend-host>/v1/platform/app-releases/<release_id> \
  -H "Authorization: Bearer <platform-owner JWT>" \
  -H "Content-Type: application/json" \
  -d '{"is_active": false}'
```

This does not delete the row or its history — it only excludes it from
`GET /v1/app-releases/latest`, so devices stop being told about it. A device mid-download of a
release unpublished a moment later can still finish that one download (the download endpoint does
not check `is_active`); only *new* `latest` answers stop naming it.

### 1.3 What a device sees

- `GET /v1/app-releases/latest` (any authenticated tenant/device bearer token) — the highest
  `version_code` among `is_active=true` releases:

  ```json
  {
    "version_code": 7,
    "version_name": "0.2.0",
    "release_notes": "…",
    "download_url": "/v1/app-releases/<id>/download",
    "sha256": "…"
  }
  ```

  404s if nothing has ever been published (or everything has been unpublished).

- `GET /v1/app-releases/{id}/download` — streams the raw APK bytes (authenticated, any valid
  tenant/device bearer token; deliberately **not** a public unauthenticated download).

- `POST /v1/fleet/devices/{id}/heartbeat`'s existing response also now carries a
  `latest_version_code` hint (the same "highest active version_code" figure, or `null`) so a
  device can learn a new build exists on its existing 60s heartbeat poll without a second network
  round trip. This is purely informational — the Android client's actual update flow
  (`domain/AppUpdateChecker.kt`) always goes through its own dedicated `GET /v1/app-releases/latest`
  call before acting, never off the heartbeat hint alone.

---

## 2. The Knox Manage policy change this needs — read before assuming this "just works"

**`KNOX_LOCKDOWN_RUNBOOK.md` §3.2 explicitly sets "App install/uninstall: Blocked from any source"
and "Unknown-sources installs: Blocked" fleet-wide.** That policy exists on purpose (it is the
whole point of the kiosk lockdown) and it blocks this app's own self-update APK install exactly as
it would block anything else — the Android system "install this app?" prompt
`domain/AppUpdateChecker.kt` triggers will be refused by Knox before (or as) it appears, on a
tablet still under the runbook's default profile.

**Before self-update can work on a real locked-down tablet, a Knox Manage admin must add an
allowlist exception scoped specifically to this app's package name (`au.com.threesixty.cabdispatch`)
for unknown-sources / install-from-this-app**, on top of (not instead of) the existing kiosk app
allowlist in §3.1 — the intent is "this one already-trusted app may install its own updates," not
"open up sideloading generally."

**This has not been confirmed against Knox Manage's own console/documentation as part of this
pass.** Knox Manage's kiosk/restriction policy is known to support a broad app allowlist (§3.1) and
a broad install-source block (§3.2), but whether it exposes a *per-app* exception to that
install-source block (as opposed to only a fleet-wide on/off toggle) was not verified here — check
Knox Manage's current console/documentation or contact Samsung Knox support to confirm this exists
and how to scope it before relying on it. If no such per-app exception exists, the fallback is
distributing updates the way `KNOX_LOCKDOWN_RUNBOOK.md` already assumes today: Knox Manage's own
console app-deployment feature, pushed by a human at the depot, with this app's real self-update
flow simply unused on that fleet.

---

## 3. This is not, and will never be, a silent update

Two things stay true no matter how the Knox policy above is configured, and neither is a bug:

1. **This app is not Android Device Owner.** Only a Device Owner app can call
   `DevicePolicyManager`/`PackageInstaller` APIs that install without any user-facing prompt. This
   app holds no such provisioning (see `KNOX_LOCKDOWN_RUNBOOK.md`'s enrollment section — Device
   Owner status belongs to Knox Manage's own enrollment, not to this app), so
   `domain/AppUpdateChecker.kt::promptInstall` always launches the standard Android
   `Intent.ACTION_VIEW` "install this app?" system confirmation. **One tap on the tablet is
   required for every single update**, even once the Knox exception in Section 2 is in place.
2. **Nothing in this flow is automatic end-to-end.** A device downloads and SHA-256-verifies the
   APK on its own (`domain/AppUpdateChecker.kt::downloadAndVerify` — the app refuses to hand an
   unverified file to the installer at all), but a driver still has to tap "UPDATE NOW" and then
   "INSTALL" on the on-screen banner
   (`ui/overlays/FleetCommandOverlays.kt::ForceUpdatePendingBanner`), and then accept the system
   prompt in point 1. There is no scheduled/background/unattended install anywhere in this flow.

Any future pass that wants a genuinely zero-touch fleet update needs either (a) this app itself
provisioned as Android Device Owner (a real re-enrollment project, not a small change — see the
provisioning caveat already on `Device.reboot_requested` in `backend/app/models/fleet.py`), or (b)
routing updates entirely through Knox Manage's own MDM app-push instead of this in-app flow.
