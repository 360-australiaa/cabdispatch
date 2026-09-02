# Dashboard: Tariffs "Extras" sub-resource UI

Adds full CRUD dashboard UI for the Extras sub-resource (`/v1/tariffs/{tariff_id}/extras[/{extra_id}]`),
which previously had zero dashboard surface. Backend was not modified.

## Backend shape verified (read-only reference, unmodified)

- `backend/app/api/v1/tariffs.py:353-434` — `list_extras` / `create_extra` / `get_extra` /
  `update_extra` / `delete_extra`. Write endpoints (`create_extra:386`, `update_extra:414`,
  `delete_extra:430`) are gated with `_admin=Depends(_require_admin)` (`_require_admin =
  require_role("owner", "admin")`, defined at `tariffs.py:80`). Read endpoints have no role
  dependency.
- `backend/app/schemas/tariffs.py:11` — `ExtraType = Literal["fixed", "passthrough"]`.
- `backend/app/schemas/tariffs.py:112-135` — `ExtraBase` (`name: str`, `amount: Decimal`,
  `type: ExtraType`), `ExtraCreate`, `ExtraUpdate` (all fields optional), `ExtraRead`
  (adds `id`, `tariff_id`, `tenant_id`, `created_at`, `updated_at`).
- `backend/app/models/tariffs.py:85-99` — `Extra` model confirms the same field set
  (`name`, `amount` as `_MONEY` decimal, `type` as a plain string column documented
  `# fixed|passthrough`).

Confirmed live against a running backend (`http://127.0.0.1:8001`) as `owner@lillycabs.test`:
create (201), list (200), update (200), delete (204) against tariff
`f9e83b59-a47a-4427-8a32-678135500a21` — payload shapes matched the hooks below exactly.

## Data layer

`dashboard/src/hooks/useTariffStudio.ts:340-433` (appended to the existing file, mirroring
its Tariff/change-log conventions exactly — decimal fields as wire strings, fuzzy
`invalidateQueries` keys):

- `ExtraType`, `Extra`, `ExtraCreateInput`, `ExtraUpdateInput` (`useTariffStudio.ts:348-370`)
- `useExtrasQuery(tariffId, opts?)` — `GET /v1/tariffs/{tariffId}/extras` (`:374-385`)
- `useCreateExtraMutation()` — `POST /v1/tariffs/{tariffId}/extras`, invalidates
  `[EXTRAS_KEY, tariffId]` (`:387-398`)
- `useUpdateExtraMutation()` — `PATCH /v1/tariffs/{tariffId}/extras/{extraId}` (`:400-419`)
- `useDeleteExtraMutation()` — `DELETE /v1/tariffs/{tariffId}/extras/{extraId}` (`:421-431`)

## UI

- `dashboard/src/pages/tariffs/ExtraFormModal.tsx` (new) — small create/edit modal for one
  Extra: name text input, amount decimal input, type `<Select>` with the two real literal
  values (`fixed`/`passthrough`). Mirrors `TollZoneFormModal.tsx`'s structure/conventions.
- `dashboard/src/pages/tariffs/ExtrasSection.tsx` (new) — the "Extras" section: a
  `components/ui/Table` listing existing extras (name, amount via `formatMoney`, type badge)
  with edit/delete icon actions, an "Add extra" button, and a delete-confirmation `Modal`.
  Gated with the same `canWrite = user?.role === "owner" || user?.role === "admin"` pattern
  already used in `dashboard/src/pages/tariffs/index.tsx:49-50` and
  `TollZonesPanel.tsx:23-24` — viewing stays open to any authenticated user; only
  add/edit/delete are hidden for non-owner/admin roles. Every trigger button that isn't
  inside its own portal-rendered `Modal` is explicitly `type="button"` since this section
  renders inside `TariffFormModal`'s outer `<form>`.
- `dashboard/src/pages/tariffs/TariffFormModal.tsx` — wired in:
  - import at `TariffFormModal.tsx:27` (`import { ExtrasSection } from "./ExtrasSection";`)
  - rendered at `TariffFormModal.tsx:417-421`:
    ```tsx
    {mode === "edit" && tariff && (
      <div className="sm:col-span-2">
        <ExtrasSection tariffId={tariff.id} />
      </div>
    )}
    ```
    Placed after the "Other rate settings" fields, before the error banner. Only shown in
    **edit** mode — a brand-new tariff has no `id` yet for an Extra to attach to.

## Verification

- `cd dashboard && npm run lint` (`tsc --noEmit`) — clean, zero errors (ran after
  `npm install`, since `node_modules` didn't exist in this worktree).
- Backend reachable at `http://127.0.0.1:8001`; full create/list/update/delete curl round
  trip against a real tariff succeeded with the exact payload/response shapes the new hooks
  use (see "Backend shape verified" above).

## Files touched

- `dashboard/src/hooks/useTariffStudio.ts` (extended)
- `dashboard/src/pages/tariffs/ExtraFormModal.tsx` (new)
- `dashboard/src/pages/tariffs/ExtrasSection.tsx` (new)
- `dashboard/src/pages/tariffs/TariffFormModal.tsx` (extended)
