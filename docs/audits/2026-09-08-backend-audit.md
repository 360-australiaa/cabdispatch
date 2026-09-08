# Cab Dispatch backend audit (2026-09-08)

Scope: `backend/app` (31 routers, 41 alembic migrations, 40 test files / 759 tests), against `android/.../ApiService.kt` and `dashboard/src`.

The four highest-severity findings (sync_trips batch abort, no-op logout, WebSocket token-type gap, tick replay) were independently re-verified against the source by the reviewing session before this document was committed.

---

## 1. ENDPOINT INVENTORY

Legend: **D** = dashboard calls it, **A** = Android meter calls it, **X** = external caller (Stripe/Twilio/CT‑DPD‑01 hardware), **—** = no client.

### auth — `app/api/v1/auth.py`
| line | method + path | purpose | auth |
|---|---|---|---|
| 103 | POST /v1/auth/login | email+password → token pair or `mfa_token` | public **D A** |
| 116 | POST /v1/auth/driver-login | driver_code+PIN, blocks expired license | public **A** |
| 160 | POST /v1/auth/mfa/login | exchange mfa_token+TOTP | public **D A** |
| 200 | POST /v1/auth/refresh | new token pair | refresh JWT **D A** |
| 228 | POST /v1/auth/logout | **no-op** (see §5) | bearer **D A** |
| 238 | GET /v1/auth/me | current user | bearer **D A** |
| 249/268/292 | POST /v1/auth/mfa/setup, /verify, /disable | TOTP enrol | bearer **D** |

### users — `users.py`
48 GET, 79 POST(admin), 111 GET/{id}, 124 PATCH(admin), 148 DELETE(admin), 178 POST/{id}/photo, 232 GET/{id}/photo — all tenant-scoped. **D** (all), **A** (photo GET+POST only).

### fleet — `fleet.py`
| line | endpoint | auth | client |
|---|---|---|---|
| 90 | GET /v1/fleet/vehicles | tenant | **D A** |
| 120/151/177 | POST/PATCH/DELETE /vehicles[/{id}] | admin | **D** |
| 139 | GET /vehicles/{id} | tenant | **D** |
| 218 | POST /vehicles/{id}/pairing-code | admin | **D** |
| 234 | GET /vehicles/{id}/evidence-pack | tenant+user | **D** |
| 266/292/321 | lifetime-totals / pilot-report / shift-history | tenant | **D** |
| 356 | GET /fleet/compliance-expiry | tenant | **D A** |
| 407 | GET /fleet/devices | tenant | **D** |
| 436 | POST /fleet/devices | admin | **D** |
| 458 | GET /fleet/devices/{id} | tenant | **—** |
| 470/502 | PATCH/DELETE /devices/{id} | admin | **D** |
| 523 | POST /devices/register | **pairing code only** | **A** |
| 605 | POST /devices/{id}/heartbeat | device-secret **or** bearer | **A** |
| 652/670/688/759 | kiosk-lock / force-update / locate / reboot | admin | **D** |
| 707/739 | locate-response / command-ack | device-secret or bearer | **A** |
| 787 | POST /devices/{id}/verify-admin-pin | tenant (any role) | **A** |
| 831 | POST /fleet/wipe-test-data/force | **owner** | **D** |

### live_ops — `live_ops.py`
82 GET /v1/vehicles **D**, 106 GET /v1/vehicles/{id} **D**, 118 /position-history **D**, 155 GET /v1/drivers **D**, 175 GET /v1/drivers/{id} **D**, 192 POST /v1/fleet/positions **D A**, 219 GET /v1/fleet/positions **—**, 229 GET /v1/fleet/positions/{vehicle_id} **—**, 284 WS /v1/fleet/live **D**.

### trips — `trips.py`
97 POST **D A**, 171 POST /sync **A**, 388 GET **D A**, 433 GET /earnings/today **A**, 466 GET /{id} **A**, 478 /gps-trace **D**, 529 PATCH **D**, 555 DELETE **D**, 574 PATCH /{id}/tick **A**, 649 POST /{id}/close **D A**, 695 PATCH /{id}/flag **D A**, 746 /receipt/email **A**, 781 /receipt/sms **A**. All tenant-scoped only — **no role gate and no "is this your trip" check** on create/tick/close.

### tariffs — `tariffs.py` + `fares_order_router`
117 GET **D**, 143 GET /active **A**, 178 GET /signing-public-key (**public**) **A**, 192 /presets **D A**, 208 /suggest **D A**, 251 POST **D**, 270 POST /from-preset **—**, 312 GET /{id} **—**, 321 PATCH **D**, 343 DELETE **D**, 358/385/402/412/429 extras, 445 change-log **D**, 472 change-log/{log_id} **—**, 495 GET /v1/fares-order/current **D A**.

### Others
shifts, zones, jobs, messages, psl, reports, geofences, compliance, billing, platform, vouchers, corporate-accounts, me, wallet, ratings, announcements, incentives, fatigue-alerts, tenants, app-releases, toll-roads, audit-log, duress, duress-device — all present and wired in `main.py:161-194`.

### (a) Backend endpoints **no client uses**

**Whole router dead:** `payments.py` — **10 endpoints, zero client calls** (`:97,127,136,154,187,215,238,262,303`; `:351` is the Stripe webhook). The meter closes and pays entirely through `POST /v1/trips/sync` / `/close`; no `Payment` row is ever created by either client.

**Individually dead:** `fleet.py:458` GET /devices/{id}; `live_ops.py:219,229`; `audit_log.py:45` POST /v1/audit-log (all audit rows are written server-side); `tariffs.py:270,312,402,472`; `duress.py:511,540`; `fatigue_alerts.py:85`; `wallet.py:52`; `compliance.py:137`; single-item GETs in `geofences.py:136`, `zones.py:158`, `vouchers.py:142`, `corporate_accounts.py:136`, `announcements.py:86`, `incentives.py:74`, `psl_ledger.py:91`, `billing.py:95`, `shifts.py:211`, `duress_device.py:168`. `duress_device.py:223/255/298/339/356` are **X** (hardware) — not dead, but no software client exercises them.

### (b) Android calls that don't exist / have drifted
**Every path in `ApiService.kt` resolves to a live route.** What has drifted is semantics:
1. `ApiService.kt:173-186` `listVehicles(limit = 100)` — backend cap `fleet.py:93` `le=100`. A tenant with >100 vehicles silently fails rego→UUID resolution on the meter.
2. `ApiService.kt:853-868` `DeviceDto.vehicleId` is returned by the heartbeat (`schemas/fleet.py:142`, populated at `fleet.py:647`) — but `domain/DeviceCommandHeartbeat.kt` never reads it. The self-heal channel exists on the wire and is unused.
3. `ApiService.kt:298` `syncTrips` posts a **batch**; `trips.py:222/263/270/276` raise `HTTPException` mid-loop before `session.commit()` at `trips.py:378`. One bad item **discards every good trip in the same batch** — see §4.
4. `ApiService.kt:190` `activeTariff(region)` — backend 422s unless `region ∈ {urban,country,exempt}` (`models/tariffs.py:31-33`).
5. `ApiService.kt:146` `verifyAdminPin` — backend (`fleet.py:787-793`) has **no role gate**; any tenant user may brute-force the admin PIN, unrate-limited.

### (c) Dashboard calls that don't exist
**None missing.** One role mismatch: `dashboard/src/hooks/useWhite-labelSettings.ts:42,52` → `GET/PATCH /v1/tenants/me`, but `tenants.py:36,50` gate both behind `_require_owner` (`:32`). A dashboard `admin` gets 403 on White-label Settings.

---

## 2. MULTI-TENANCY & GLOBAL READINESS

### Enforcement mechanism
- `core/database.py:80-90` `TenantScopedMixin` — plain `tenant_id` FK column + index. **No DB-level RLS**, explicitly documented (`core/database.py:83-85`, `core/security.py:4-7`).
- `core/security.py:319-331` `get_current_tenant_id` is the sole boundary. Cross-tenant override: `core/security.py:305-309` — `role == "owner"` **and** `tenant_id == PLATFORM_TENANT_ID` (`:35`) may pass `?tenant_id=`.
- `core/security.py:334-346` `get_optional_tenant_id` — used only by the three device routes, each of which re-gates in `fleet.py:563-602`.

### Queries missing / weakening the tenant filter
| file:line | issue | severity |
|---|---|---|
| `services/reports.py:91` | `select(User.id, User.name).where(User.id.in_(driver_ids))` — no tenant filter (adjacent vehicle lookup at `:94` has one). Not exploitable today. | low |
| `services/reports.py:100` | Same pattern for `Tariff`. | low |
| `api/v1/duress.py:261-263` | Cross-tenant scan of every `DuressEvent` with a `device_call_result_json` on every Twilio callback. O(table) on an unauthenticated-by-tenant path. | medium (perf) |
| `services/fleet.py:254`, `:317-322` | Device/pairing lookups without tenant filter — intentional, secret/code is the credential. | ok |
| `api/v1/geofences.py:105` | `or_(tenant_id == X, tenant_id.is_(None))` — global reference geofences. Intentional. | ok |

**No blocking tenant-isolation defect found.** Isolation is 100% convention: no test asserts every `TenantScopedMixin` model is filtered on every query path.

### Tenant onboarding
- **Self-serve does not exist.** `POST /v1/platform/tenants` (`platform.py:74-93`) is the only tenant-creation route, gated by `require_platform_owner`. It creates a `Tenant` row **only** — no owner user, no tariff. A platform operator must then `POST /v1/users` while impersonating via `?tenant_id=`, then create a tariff, before the tenant can do anything.
- `scripts/seed.py:262-334` is the only path that produces a *working* tenant, hardcoded to "TCT"/"Lilly Cabs".

### Hardcoded region assumptions

| assumption | location | today it is | to make it per-tenant |
|---|---|---|---|
| `Australia/Sydney` timezone | `services/fare_engine.py:340` `NSW_FARE_ZONE`; used `:405`; `services/tariffs.py:202-207` | module constant | `Tenant.timezone` column (does not exist — `models/tenant.py` has id/name/abn/tsp/bsp/theme_json/plan/status/stripe_acct_id/admin_pin_hash) + thread through `resolve_time_class_and_peak`, `classify_time_of_day`, every `datetime.now(UTC).date()` "today" bucket (`services/trips.py:613-617`, `platform.py`, `reports.py`) |
| Night window 22:00–06:00 | `fare_engine.py:407` `hour >= 22 or hour < 6` | literal | tariff columns `night_start_hour`/`night_end_hour` |
| Peak = Fri/Sat/pre-holiday night | `fare_engine.py:418` `weekday in (4, 5)` | literal | ditto |
| NSW public holiday calendar | `fare_engine.py:287-317` frozenset (2027 *calculated*, `TODO(risk flag)` `:282-286`); duplicated in Android | hardcoded, duplicated | `holiday_calendar` table / per-region provider |
| PSL levy $1.32 | `models/tariffs.py:78`, `schemas/tariffs.py:37`, `fare_engine.py:120,659,694` | **per-tenant tariff setting ✅** | fine |
| Maxi 150% | `models/tariffs.py:76`, `fare_engine.py:118,708` | **per-tenant ✅** | fine |
| Maxi *eligibility rule* | `fare_engine.py:473-482` `maxi_applied` | code, NSW cl 2(d)(ii) | per-region rule |
| Rounding cl 4(a) | `fare_engine.py:59-74,662,716,760,763` | code | per-region rounding policy |
| GST `/11` | `fare_engine.py:611,673,763` | literal | per-tenant `gst_rate` |
| AUD | `models/toll.py:287,349`, `schemas/toll.py:116`, `models/billing.py:36`, `services/billing.py:65,108`, `services/payments.py:89,118,171,233`, `services/psl_ledger.py:56`, `services/receipts.py:390,425` | hardcoded in 9 places | `Tenant.currency` + money formatter |
| Region vocabulary `urban\|country\|exempt` | `models/tariffs.py:31-33`, `api/v1/tariffs.py:159-163`, `services/tariffs.py:62` | hardcoded enum | region descriptor per jurisdiction |
| "Point to Point" / NSW wording | `api/v1/reports.py:10,61,188` (`/v1/reports/nsw-ptp-export`), `schemas/reports.py:20,38`, `models/compliance.py:4`, `services/evidence_pack.py:4`, `main.py:26` | path name + copy | neutral `/v1/reports/regulator-export` + per-region formatter |
| Toll roads registry | `models/toll.py:1-101`, `app/data/nsw_toll_roads.json`, `scripts/seed_toll_roads.py`, `services/tolls.py` | platform-wide, un-tenanted, NSW-only | `jurisdiction` dimension on `TollRoad`/`TollGantry` |
| Airport fixed fare $60/$80 | `fare_engine.py:173-182` | module constants | per-tenant/per-region |
| CabCharge / TTSS | `core/config.py:79-88` | env vars ✅ | fine |

**Cost of a second jurisdiction:** the `Tariff` *rate card* is already per-tenant and ports cleanly. What does not port is everything that is a *rule*: night/peak windows, holiday calendar, maxi eligibility, rounding, GST divisor, toll registry. Minimum viable shape: `Tenant.timezone` + `Tenant.currency` + `Tenant.jurisdiction`; a `FareRegion` protocol carrying `(tz, holidays, night_window, peak_rule, rounding, gst_divisor, maxi_rule)` with `NSWRegion` as first implementation; `NSW_FARE_ZONE` becomes a parameter of `resolve_time_class_and_peak`. The Android app mirrors the same rules and must be plugin-ised in lockstep.

---

## 3. DEVICE / COMMISSIONING CONTRACT

### The handshake, traced
1. Admin: `POST /v1/fleet/vehicles/{id}/pairing-code` (`fleet.py:218`) → `services/fleet.py:268-283`. 8 chars, 32-symbol alphabet, 15-min TTL, single-use.
2. Tablet: `POST /v1/fleet/devices/register` (`fleet.py:523`) with `{android_id, pairing_code, model, app_version}` — no bearer, by design.
3. `services/fleet.py:286-357`: tenant read off the code row (`:329`); find-or-create `Device` by `(tenant_id, android_id)`; binds `vehicle_id`; mints a fresh secret (`:234-239`, SHA-256 hex at `:222-231`); clears `revoked_at` (`:346`); burns the code (`:351-353`).
4. Plaintext secret returned exactly once (`fleet.py:555-560`).
5. `POST /devices/{id}/heartbeat` every 60s, `X-Device-Secret` **or** bearer via `_authenticate_device_or_bearer` (`fleet.py:563-602`). Response carries command flags + `latest_version_code` (`:646-649`).
6. Admin sets `locate_requested`/`reboot_requested`; device answers `locate-response` (`fleet.py:707`) or `command-ack` (`:739`).
7. OTA: `GET /v1/app-releases/latest` (`app_releases.py:126`) + `/{id}/download` (`:149`) — **bearer only**.

### Gaps

| # | gap | evidence |
|---|---|---|
| G1 | **A device can silently re-pair to a different vehicle with no audit trail.** `services/fleet.py:338` unconditional; nothing written to `AuditLog`. Only detection is the advisory shift-time cross-check at `services/shift.py:120-180`. | `services/fleet.py:286-357` |
| G2 | **No secret rotation except full re-pair.** `mint_device_secret` only from `register_device` (`:348`). Contrast `duress_device.py:194` rotate-secret. | |
| G3 | **Revocation is bypassable.** `_authenticate_device_or_bearer`'s bearer fallback (`fleet.py:591-597`) calls `get_device_or_404`, which does **not** check `revoked_at` (only `authenticate_device` at `services/fleet.py:256` does). A revoked tablet with any valid human token keeps heartbeating. | `services/fleet.py:149-156` vs `:256` |
| G4 | **Orphaned device on vehicle delete: FK handled, device never told.** `services/fleet.py:176-184` nulls `Device.vehicle_id`; `:186-208` force-closes the open shift. The device keeps its stale `vehicleUuid` until re-pair. (Android-side self-heal landed 2026-09-08 via 404-triggered re-resolve.) | |
| G5 | **No device-secret-authenticated "read my own row".** Mitigation already on the wire: the heartbeat response is a full `DeviceRead` including `vehicle_id` — a **client-side one-liner** in `DeviceCommandHeartbeat.kt`. A dedicated `GET /v1/fleet/devices/me` is cleaner but optional. | |
| G6 | **`GET /fleet/vehicles` capped at 100** (`fleet.py:93`); the meter uses it as its rego→UUID resolver with no paging loop. Breaks at fleet #101. Needs `?rego=` exact-match (the partial-match filter at `fleet.py:109-112` exists but the client doesn't use it). | |
| G7 | **Register is unauthenticated and unrate-limited.** `fleet.py:540-542` acknowledges it. No attempt counter on the code lookup. | |
| G8 | **`verify-admin-pin` has no role gate and no attempt limit** (`fleet.py:787-819`). | |
| G9 | **`command-ack` only understands `"restart"`** (`services/fleet.py:440-441`). `force_update_pending` and `kiosk_locked` have **no ack path** — read "Pending" forever. | |
| G10 | Pairing codes never garbage-collected. | |

---

## 4. DATA INTEGRITY

### Server-side fare recomputation & auto-flag ✅
- `services/trips.py:385-478` `recompute_from_trace` replays the client's GPS trace and **ignores** the client's `time_class`/`is_peak` (`:411-421`) and `maxi` (`:84-98`).
- `services/trips.py:531-541` `compute_variance_pct`, clamped to `9999.99` (`:528`).
- `api/v1/trips.py:237` `fare_check_passed = variance_pct <= 1.0`; `:330-331` sets `flagged_for_review`. Correct.

### Offline sync idempotency
- Unique `(tenant_id, client_uuid)` — `models/trips.py:143`. Pre-check `trips.py:180-189`, race handled `:360-373`.

**🔴 The batch-abort bug (verified):** `sync_trips` (`trips.py:171-382`) flushes per item but commits **once** at `:378`. Any of `:222` unknown tariff / `:263` invalid voucher / `:270` invalid account / `:276` split mismatch raises `HTTPException` inside `for item in items` (`:180`) — FastAPI unwinds without committing, **every already-flushed good trip in that batch is lost**. The Android client posts all queued offline trips as one batch; one poisoned item destroys a whole shift's offline trips. `TripSyncResultItem` already has a per-item shape — it isn't used for the failure case. **Highest-severity backend finding.**

**🔴 `/tick` has no idempotency (verified):** `PATCH /v1/trips/{id}/tick` (`trips.py:574`), `TripTickRequest` (`schemas/trips.py:192-203`) = `points` + optional dest, **no sequence number, no last-known-ts**. `services/trips.py:190-259` walks from `trip.last_ts` and accumulates. A retried request replays the same points: `elapsed_seconds` clamps to 0 for backwards time (`:212`) so waiting time is safe, but **`haversine_km` distance accrues again** — silent over-charge, and the online-close path sets `max_fare_check_passed = True` (`:376`) so nothing catches it. Fix: monotonic `tick_seq`, or ignore points with `ts <= trip.last_ts`.

### Other trip-integrity notes
- `POST /{id}/close` is double-submit safe (`trips.py:657-658`). `POST /v1/trips` is 409-safe on duplicate `client_uuid` (`:158-163`).
- `services/trips.py:376` `max_fare_check_passed = True` unconditionally on the online-close path — any trip closed online has an unverified fare by construction.
- **No role/ownership check on any trip write** (`trips.py:100,174,578,653,533,559`). Any driver token can tick or close any other driver's trip. Only `/flag` checks identity (`:700`).

### Shifts
- `start_shift` (`services/shift.py:183-302`) handles dangling shifts correctly. Vehicle delete → `close_open_shifts_for_vehicle_deletion` (`:333-415`), audit-logged. Well designed.
- **Gap:** nothing closes an open shift when a *driver* is deleted (`fleet_wipe.py` deletes drivers without touching shifts).
- **Gap:** `_recompute_trip_aggregates` (`services/shift.py:68-117`) sums **all** trips, not just closed. Contrast `services/trips.py:622`.
- **Gap:** cash/card split is `payment_method == "cash"` vs everything-else (`:30,100,107`). `split_fare` counted 100% as card.

### Retention & audit
- **Position history:** `POSITION_HISTORY_RETENTION_HOURS = 72` (`services/live_ops.py:115`), pruned lazily **per vehicle on write** (`:373-381`). A vehicle that stops reporting keeps its rows forever. Labelled "a TECHNICAL DEFAULT, not a decided data-retention policy" (`:108-114`).
- **Audit log:** genuine SHA-256 hash chain (`services/audit_log.py:100-138`), `verify_chain` (`:222-266`) at `GET /v1/audit-log/verify`. Never deleted, even by force-wipe. Strongest integrity control in the codebase. **Weakness:** `_latest_hash` (`:141-154`) + insert is not serialised — two concurrent audited writes on one tenant can fork the chain, permanently failing `verify_chain`.

---

## 5. SECURITY

### Token lifetimes & session handling
- `core/config.py:37` access **30 min**; `:38` refresh **14 days**; `core/security.py:60` MFA-pending **5 min**.
- **🔴 `POST /v1/auth/logout` is a no-op (verified).** `api/v1/auth.py:227-234` — docstring: "revokes nothing server-side". Both clients call it and assume it works. The revocation store exists (`core/security.py:147-210`); the endpoint doesn't use it.
- **🔴 No refresh-token rotation.** `auth.py:199-224` issues a new pair but never revokes the presented refresh jti (contrast `mfa_login` at `:191-194`). A leaked refresh token is a 14-day skeleton key.
- **🔴 Revocation store falls back to per-process memory** (`core/security.py:147-210`) — unreliable in any multi-worker deploy.
- **🟠 WebSocket auth does not check token type (verified).** All four WS helpers call `decode_token` and never assert `type == access`: `live_ops.py:264` (checks revocation at `:270`), `duress.py:578` (checks role, **not** revocation, **not** type), `jobs.py:274` (**neither**), `messages.py:247` (**neither**). A **refresh** or **`mfa_pending`** token opens a live socket; on jobs/messages a **revoked** token also works. The `mfa_pending` case defeats MFA for the realtime surfaces.
- **🟠 Login does not check tenant status.** `auth.py:92-93` checks `user.status` but not `Tenant.status == "suspended"`. Suspension is cosmetic.

### Device credential HMAC
- `services/fleet.py:222-231` SHA-256 hex, `:260` `hmac.compare_digest`. Correct.
- Duress hardware uses Fernet-encrypted reversible secret (`core/crypto.py`). Also correct, but **two different device credential models coexist**, and only the duress one has rotation.

### Rate limiting
- **None, anywhere.** No middleware in `main.py:147-153` other than CORS. Unprotected: `/auth/login`, `/auth/driver-login` (6-digit PIN), `/auth/mfa/login`, `/fleet/devices/register`, `/verify-admin-pin`.

### CORS
- `main.py:147-153` + `core/config.py:41-48`: `allow_credentials=True`, origins from env. No wildcard-with-credentials bug. ✅

### Secrets in repo
- `.gitignore` excludes `.env*`; only `.env.example` / `.env.production.example` tracked with `change-me-*` placeholders. ✅
- **🟠 Two functional private keys committed as source defaults:** `core/config.py:148-150` (Ed25519 tariff-signing private key) and `:163` (Fernet key). Flagged `*** PLACEHOLDER KEY ***` but real, working keys.
- **🔴 The production startup guard only checks one of the three.** `core/config.py:197-215` `assert_production_secrets_safe` validates `JWT_SECRET` only. A deploy that forgets `TARIFF_SIGNING_PRIVATE_KEY` or `SECRET_ENCRYPTION_KEY` boots happily — every tariff signed with a publicly-known key (defeating `TariffSignatureVerifier` entirely).
- **🟠 Seed credentials committed and published:** `scripts/seed.py:51,57`, echoed at `:327-333`, in `docs/DEPLOY_UBUNTU.md:182`, and `ANDROID_STATUS_FOR_BACKEND_AGENT.md:18,39`. `DEPLOY_UBUNTU.md:130` instructs running `seed.py` **on production**. No `ENV != production` guard.

### Endpoints with no auth dependency (all reviewed)
`auth.py:103,116,160,200` correct; `fleet.py:524` register — needs rate limiting; `payments.py:352` Stripe webhook ✅ signature-verified; `duress.py:228` Twilio ✅ signature-verified; `tariffs.py:179` public key correct; `duress_device.py:224,256,299,340,357` HMAC/JWT device auth; 4 × WebSocket — see above.

### 🔴 ADDENDUM — live credentials and a plaintext production endpoint baked into the distributed APK
Verified by unpacking `cabdispatch-meter-0.6.2.apk` and grepping its 20 `classes*.dex` files (extraction removed afterward):
- `ui/screens/login/LoginVehicleBindViewModel.kt:39-40` declares the seeded demo driver code and PIN as `const val`s; its own doc (`:34-37`) calls them "this tenant's real seeded driver code, verified live". **Both strings are present in `classes11.dex`.** `LoginVehicleBindScreen.kt:212` gates only the *button* behind `BuildConfig.DEBUG`; `build.gradle.kts:107` minifies only `release`; the shipped APK has 20 unminified dex files, i.e. it was not built through `release`.
- The APK embeds `http://72.61.107.107:8001` — not the release placeholder `https://api.cabdispatch.example.com` (`build.gradle.kts:109-111`, unresolved `TODO(sibling agent, release hardening)`).
- Chained with: `POST /v1/auth/driver-login` (`auth.py:116`) has no rate limiting; the PIN is 6 numeric digits; the driver-code lookup at `auth.py:142` is `select(User).where(User.driver_code == …)` — **global, not tenant-scoped**; `DEPLOY_UBUNTU.md:130` instructs running `seed.py` on production, which is what minted this account.
- The three ~145 MB APKs in the repo root are untracked but **not gitignored**.

**Required:** rotate/delete the account on the live server now; delete the constants (or move them under `src/debug/`); `*.apk` in `.gitignore`; release build fails on the placeholder URL; rate-limit `driver-login` per-code and per-IP; scope the driver-code lookup to a tenant; HTTPS becomes a prerequisite, not a doc item. Also confirm the offline-login cache (`DriverAuthRepository.kt:144-158` `seedOfflineDemoDriver` writes an entry with `tenantId = null`) cannot be populated by any non-debug path.

### Other
- `POST /v1/fleet/wipe-test-data/force` (`fleet.py:831`) is a live production route that irreversibly deletes vehicles/devices/drivers + PSL/wallet/ratings/compliance. Marked TEMPORARY (`:822-828`). Dashboard exposes it at `pages/fleet/api.ts:788`.

---

## 6. OPERATIONS

### Alembic
**✅ Exactly one head: `c7a4e2b8f13d`.** 41 revisions; three forks correctly merged at `95db941b68cd` and `2acd19d3155f`.

**Risky migrations:** `a9c1f4e7d2b8_nsw_toll_road_registry.py` deletes 9 seeded geofence rows (data-destructive); `6ce5e71ba25d` adds CASCADE/SET NULL (converts blocked deletes into silent cascades); merge revisions can't safely downgrade; `7060b390bade` pricing-model change. **No migration has ever run in CI**: `tests/conftest.py:18` pins SQLite and `Base.metadata.create_all` (`:39`) — the suite never runs alembic. `services/fleet.py:1-42` documents a production-only bug that 649 passing tests could not catch for exactly this reason.

### Background jobs — **none. Zero scheduler infrastructure.**
No APScheduler, Celery, cron, `create_task`, or lifespan handler. Everything periodic is lazy:

| what | mechanism | file:line | failure mode when idle |
|---|---|---|---|
| Job-offer expiry | on every offer read/action | `services/jobs.py:405-435` | an unread offer stays `pending` forever |
| Position-history retention | inline, **same vehicle only** | `services/live_ops.py:373-381` | a vehicle that stops publishing keeps GPS history indefinitely — privacy exposure |
| Fatigue alerts | side effect of `PATCH /trips/{id}/tick` | `api/v1/trips.py:612-625` | **a driver on a 14-hour shift with no active trip is never flagged** |
| Compliance expiry alerts | same tick side effect | `api/v1/trips.py:627-639` | an idle vehicle's expired rego is never alerted |
| Duress escalation cascade | manual `POST /{id}/escalate` only | `api/v1/duress.py:146` | **no automatic timer.** A panic event nobody watches sits at stage 1 forever. Most consequential missing job. |
| Pairing-code cleanup | none | — | unbounded |
| Stale device detection | none | — | no "offline >N min" alerting |

### Logging
Per-module `getLogger`; **no `basicConfig`/`dictConfig`**, no structured logging, no request-id, no Sentry. **No exception handlers registered** — unhandled errors return bare 500 with no CORS headers (root of the "phantom 503", `services/fleet.py:28-42`).

### Health endpoint
`main.py:156-158` returns ok **without touching the DB or Redis**. Docker's healthcheck gates on it, so a dead Postgres reports healthy.

### `docs/DEPLOY_UBUNTU.md` gaps
1. **HTTP-only, bare IP** — JWTs, device secrets, duress audio, GPS in cleartext. Top operational risk.
2. No log management. 3. Backups are a manual `pg_dump` with no cron, no offsite, no restore drill, and **no backup of `cabdispatch_uploads`**. 4. No monitoring. 5. No secret-rotation procedure. 6. Instructs running `seed.py` in production. 7. No rollback. 8. Doesn't mention `DURESS_ESCALATION_CALL_PHONE` — leaving it empty silently skips the emergency call. 9. **No worker-count guidance** — with >1 worker the in-process broadcasters (`services/live_ops.py:156-166`, `JobOfferBroadcaster`, `message_broadcaster`) and the in-memory revocation fallback silently break. 10. No restart policy.

---

## 7. TESTS

**40 files, 759 tests.** Dedicated file per router for almost everything. Cross-cutting: `test_operations_cycle`, `test_receipts`, `test_evidence_pack`, `test_health_and_auth_smoke`, `test_config_production_guard`, `test_admin_pin`.

**Genuinely untested:**
1. **Alembic migrations — zero.** 2. **Postgres — zero.** 3. **WebSocket routes** — no test rejects refresh/mfa_pending/revoked tokens. 4. **Concurrency** — audit-chain fork, tick replay. 5. **Multi-tenancy as a property.** 6. **`sync_trips` partial-failure** — no test for what happens to good items when item 3 of 5 is bad (it would fail). 7. `payments.py` tested but unreachable.

---

# Prioritised backlog — parallelisable workstreams

### WS-1 · P0 — Offline trip loss & meter double-charge
**Files:** `api/v1/trips.py`, `schemas/trips.py`, `services/trips.py`, `tests/test_trips.py`
1. Make `POST /v1/trips/sync` per-item transactional: savepoint per item, catch errors, return a failed `TripSyncResultItem` instead of aborting (`trips.py:171-382`, raises at `:222,263,270,276`).
2. `/tick` idempotency: `tick_seq` on `TripTickRequest` (`schemas/trips.py:192`) or drop points with `ts <= trip.last_ts` in `apply_tick` (`services/trips.py:200-226`).
3. Ownership/role gating on trip writes (`trips.py:98,172,530,556,575,650`) — mirror `flag_trip` (`:696-745`).
4. Tests: poisoned batch preserves good items; replayed tick is a no-op; driver A cannot close driver B's trip.

### WS-2 · P0 — Auth session lifecycle & WebSocket token type
**Files:** `core/security.py`, `api/v1/auth.py`, the four WS helpers (`live_ops.py:250-280`, `duress.py:560-600`, `jobs.py:250-295`, `messages.py:225-270`), `tests/test_auth_mfa.py`, new `tests/test_ws_auth.py`
1. One shared `authenticate_websocket_token()` in `core/security.py` enforcing **type == access + revocation + tenant**; replace all four copies.
2. `POST /v1/auth/logout` actually revokes (`auth.py:227-234`).
3. Rotate refresh tokens: revoke the presented jti in `/refresh` (`auth.py:199-224`).
4. Check `Tenant.status != "suspended"` in `_login_result` (`auth.py:80-99`).

### WS-3 · P0 — Production secret guard & deploy hardening
**Files:** `core/config.py`, `scripts/seed.py`, `docs/DEPLOY_UBUNTU.md`, `.env.production.example`, `tests/test_config_production_guard.py`
1. Extend `assert_production_secrets_safe` (`config.py:197-215`) to reject the committed defaults for `SECRET_ENCRYPTION_KEY` (`:163`) and `TARIFF_SIGNING_PRIVATE_KEY` (`:148-150`).
2. Guard `seed.py` behind `ENV != production`; stop printing credentials (`:327-333`).
3. Deploy doc: HTTPS required; uploads-volume backup; restore drill; log rotation; `--workers 1` warning until WS-7; remove demo-credential verification step.

### WS-4 · P0 — Rate limiting
**Files:** `main.py`, new `core/ratelimit.py`, `auth.py`/`fleet.py` (decorators only), `pyproject.toml`, new `tests/test_ratelimit.py`
slowapi or Redis token bucket reusing `REDIS_URL` + in-memory fallback. Apply to `auth.py:103,116,160`, `fleet.py:524`, `fleet.py:788`. Add a role gate to `verify-admin-pin`.

### WS-5 · P1 — Device commissioning lifecycle
**Files:** `api/v1/fleet.py` (402-820), `services/fleet.py`, `schemas/fleet.py`, `tests/test_fleet.py`
1. Close the revocation bypass (`services/fleet.py:149-156`, `api/v1/fleet.py:591-597`).
2. `POST /v1/fleet/devices/{id}/rotate-secret`, mirroring `duress_device.py:194`.
3. Audit-log every re-pair (`services/fleet.py:338`).
4. `GET /v1/fleet/devices/me` accepting `X-Device-Secret`, **and** the Android one-liner to read `DeviceDto.vehicleId` off the heartbeat.
5. `record_command_ack` (`services/fleet.py:433-445`) clears `force_update_pending` and `kiosk_locked`.
6. Prune consumed/expired pairing codes on next mint.
7. `?rego=` exact-match on `GET /v1/fleet/vehicles` (`fleet.py:90-117`).

### WS-6 · P1 — Missing background work (no new infra)
**Files:** `services/fatigue.py`, `services/compliance_expiry.py`, `services/duress.py`, `services/live_ops.py`, `api/v1/shifts.py`, related tests
1. Lazy fatigue/compliance checks on `POST /v1/fleet/positions` and shift reads, not only trip ticks.
2. Advance the duress escalation cascade lazily on any duress read.
3. Global position-history pruning + a recorded retention decision.
4. Close open shifts on driver delete / force-wipe; fix `_recompute_trip_aggregates` (`services/shift.py:68-117`).

### WS-7 · P1 — Ops: health, logging, error handling, multi-worker
**Files:** `main.py`, new `core/logging.py`, `entrypoint.sh`, `docker-compose.yml`
1. `/health` → `SELECT 1` + alembic-head + Redis probe; separate `/health/live`.
2. Exception handlers so 500s carry CORS headers.
3. Structured logging + request-id middleware.
4. Pin `--workers 1` with a comment, or move the three broadcasters to Redis pub/sub (seams documented at `services/live_ops.py:161-166`).

### WS-8 · P1 — Test-infrastructure parity
**Files:** `tests/conftest.py`, new `tests/test_migrations.py`, new `tests/test_tenant_isolation.py`, CI
1. `alembic upgrade head` in the session fixture; single-head assertion; model-vs-migration drift check.
2. Optional Postgres-backed run, env-gated.
3. Property test: every `TenantScopedMixin` subclass — tenant B cannot read/write tenant A's rows.

### WS-9 · P2 — Multi-jurisdiction seam
**Files:** `services/fare_engine.py`, `services/tariffs.py`, `models/tenant.py`, one migration, `tests/test_fare_engine_golden.py`
1. `Tenant.timezone`, `Tenant.currency`, `Tenant.jurisdiction` (defaults `Australia/Sydney` / `AUD` / `NSW`).
2. `FareRegion` protocol; `NSWRegion` sole implementation; parameterise `resolve_time_class_and_peak` (`fare_engine.py:343`).
3. Replace `/11` (`:611,673,763`) and `hour >= 22 or hour < 6` / `weekday in (4,5)` (`:407,418`) with region lookups.
4. Golden tests byte-identical for `NSWRegion` — pure refactor. Android must follow in lockstep.

### WS-10 · P2 — Tenant self-serve onboarding
**Files:** `api/v1/platform.py`, `services/platform.py`, `schemas/platform.py`, `tests/test_platform.py`, `dashboard/src/hooks/usePlatformConsole.ts`
`POST /v1/platform/tenants` creates tenant **+ owner user + default tariff** in one transaction, returning a one-time invite. Fix `GET/PATCH /v1/tenants/me` to accept `admin` (`tenants.py:36,50`).

### WS-11 · P3 — Dead-surface reconciliation
**Files:** `api/v1/payments.py`, `api/v1/audit_log.py`, `shared/API_SUMMARY.md`, `shared/openapi.json`
1. Decide `payments.py`: wire the meter's Close & Pay to it, or deprecate.
2. Remove `POST /v1/audit-log` (`audit_log.py:45`).
3. Regenerate `shared/openapi.json` + `API_SUMMARY.md` (last dumped 2026-08-26).
