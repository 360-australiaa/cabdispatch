# Live Map — vehicle detail drill-down

Adds a vehicle detail view to the dashboard's Live Map page (`dashboard/src/pages/live-map/`),
reachable from both the Vehicles table and the map itself, closing the CRUD-completeness
audit gap where a non-duress vehicle marker/row had no click action at all.

## New files

- `dashboard/src/pages/live-map/useVehicleDetail.ts`
  - `useVehicleDetailQuery(vehicleId)` (lines 12–21) — `GET /v1/vehicles/{id}`, same
    `VehicleLiveRead` shape as one row of `GET /v1/vehicles`, resolved fresh for a single
    vehicle. Mirrors this page's existing inline `useQuery` + `apiClient.get` convention
    (see `index.tsx`'s `vehiclesMapQuery`/`vehiclesTableQuery`), `enabled: vehicleId != null`.
  - `useDriverDetailQuery(driverId)` (lines 27–34) — `GET /v1/drivers/{id}`, only ever
    invoked with the vehicle's `current_driver_id`, to surface the phone number that
    `VehicleLiveRead.current_driver_*` doesn't carry.

- `dashboard/src/pages/live-map/VehicleDetailModal.tsx`
  - `VehicleDetailModal` (lines 37–147) — modal opened from either the table or the map.
    Fetches via `useVehicleDetailQuery`/`useDriverDetailQuery` rather than reusing the
    row/marker that triggered it, so it never shows a stale value from a paused
    table/map poll. Fields shown, all read directly off the real `VehicleLiveRead` /
    `DriverLiveRead` schemas (`backend/app/schemas/live_ops.py`) — nothing fabricated:
    - Identity: `rego`, `vehicle_class`, `vehicle_status`
    - Live state: `live_status`, `lat`/`lng` (via `formatLatLng`), `position_source`,
      `position_updated_at` (via `formatRelativeTime`/`isStale`), `current_trip_id`
    - Device: `device_id`, `device_last_seen_at`, `battery`, `network`
    - Current driver (shown only if `current_driver_id` is set): `current_driver_name`,
      `driver.phone` (from the `GET /v1/drivers/{id}` call), `current_shift_id`,
      `current_shift_start_at`

## Edited files

- `dashboard/src/pages/live-map/types.ts` (lines 50–63) — added a local `DriverLiveRead`
  mirror of `backend/app/schemas/live_ops.py::DriverLiveRead`, matching the existing
  header comment's convention of hand-mirroring backend read schemas in this domain.

- `dashboard/src/pages/live-map/index.tsx`
  - Added `selectedVehicleId` state (line 62) and rendered `<VehicleDetailModal>`
    (lines ~392–396) alongside the existing `PublishPositionModal`.
  - Vehicles `<Table>` now has `onRowClick={(v) => setSelectedVehicleId(v.id)}`
    (line ~365), the same `onRowClick` convention already used by
    `dashboard/src/pages/dispatch/index.tsx` and `dashboard/src/pages/duress/index.tsx`
    for their own row-click-opens-detail pattern.
  - `<FleetMapCanvas>` now receives `onSelectVehicle={setSelectedVehicleId}` (line ~301).
  - Card description copy updated to mention non-duress vehicles are clickable too.

- `dashboard/src/pages/live-map/FleetMapCanvas.tsx`
  - `FleetMapCanvasProps`/`MapDataProps` gained an `onSelectVehicle: (vehicleId: string) => void`
    prop, threaded through `FleetMapCanvas` → `MapboxFleetMap` and `PlainCanvasMap`.
  - `buildMarkerElement` (~line 82) now takes `onSelectVehicle` and every marker gets
    `cursor: pointer` (previously only duress markers did). Duress-priority behavior is
    unchanged — a duress-active vehicle's marker still calls
    `navigate(/duress?event=...)`; the `else` branch (previously no listener at all) now
    calls `onSelectVehicle(vehicle.id)`.
  - `PlainCanvasMap`'s SVG fallback (~line 296) got the equivalent change: every `<g>`
    is now `cursor-pointer` and clickable, branching the same way
    (`duressEvent ? navigate(...) : onSelectVehicle(v.id)`).

## Verification

- `cd dashboard && npm run lint` (`tsc --noEmit`) — clean, zero errors, after `npm install`
  (the worktree's `dashboard/node_modules` was missing).
- Backend was reachable at `http://127.0.0.1:8001`. Logged in as
  `owner@lillycabs.test` / `ChangeMe123!`, then:
  - `GET /v1/vehicles?limit=3` and `GET /v1/vehicles/{id}` for that same vehicle
    returned byte-identical JSON shapes, confirming `useVehicleDetailQuery`'s expected
    response shape (`VehicleLiveRead`) is exactly what the backend returns.
  - `GET /v1/drivers?limit=3` and `GET /v1/drivers/{id}` for that same driver likewise
    matched, confirming `useDriverDetailQuery`'s expected shape (`DriverLiveRead`).
  - Note: the seed vehicle's `current_driver_id` pointed at a driver id that
    `GET /v1/drivers/{id}` 404s on (seed-data inconsistency, not a bug introduced here) —
    exercised this path too and confirmed `VehicleDetailModal` degrades gracefully
    (driver phone renders as "—", the rest of the panel still renders from the vehicle
    row's own `current_driver_name`/`current_shift_*` fields).

## Not touched

- Nothing under `backend/`.
- `dashboard/src/pages/duress/` — only the existing `/duress?event=<id>` navigation from
  a duress-active marker is preserved; the duress page itself is untouched.
