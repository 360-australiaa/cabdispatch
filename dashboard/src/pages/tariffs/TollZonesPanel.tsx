import { useState } from "react";
import { Pencil, Plane, Plus, Trash2 } from "lucide-react";
import {
  Button,
  Card,
  CardContent,
  EmptyState,
  Modal,
  Pagination,
  Table,
  Tooltip,
  useToast,
  type TableColumn,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { isPlatformOwner } from "@/lib/platformAdmin";
import {
  useAddAirportPresetsMutation,
  useAirportPresetsQuery,
  useDeleteGeofenceMutation,
  useGeofencesQuery,
  type AddAirportPresetsResult,
  type Geofence,
} from "@/hooks/useGeofences";
import { extractErrorMessage, formatMoney } from "./format";
import { TollZoneFormModal, type ZoneFormKind } from "./TollZoneFormModal";

const PAGE_SIZE = 15;

/** Airport zones are a handful of terminal ranks, not a paginated list --
 * fetched in one page like Live Map's geofence overlay. */
const AIRPORT_FETCH_LIMIT = 100;

const OWNER_ONLY_HINT = "Only the platform owner can change airport zones";

/** Toll & Airport Zones tab of Tariff Studio — CRUD over `/v1/geofences`
 * circular zones (name, center lat/lng, radius in meters, amount), in two
 * sections:
 *
 * - `kind: "toll"`: charged on entry -- a trip crossing into the radius
 *   auto-detects the toll from its GPS ticks.
 * - `kind: "airport"`: the airport ground-transport access fee (Sydney
 *   Airport: $6.43 GST inclusive, passed on to the passenger under the NSW
 *   Fares Order), charged ONCE when a hiring STARTS inside the zone -- a
 *   pickup at the rank. Never on a drop-off, and never added under the
 *   Sydney Airport fixed fare ($60 standard / $80 maxi to the CBD), which
 *   already includes it.
 *
 * Region-kind geofences (platform reference zones) are out of scope here.
 * Pricing is pricing (product decision, 2026): create/edit/delete for both
 * kinds are platform-owner gated server-side (`geofences.py`), the same gate
 * as the sibling Rate Cards tab (`pages/tariffs/index.tsx`) and the Platform
 * Admin console (`src/lib/platformAdmin.ts`). */
export function TollZonesPanel() {
  const { user } = useAuth();
  const canWrite = isPlatformOwner(user);

  const [deletingZone, setDeletingZone] = useState<Geofence | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const deleteMutation = useDeleteGeofenceMutation();

  const deleteKind: ZoneFormKind = deletingZone?.kind === "airport" ? "airport" : "toll";

  function requestDelete(zone: Geofence) {
    setDeleteError(null);
    setDeletingZone(zone);
  }

  return (
    <div className="flex flex-col gap-8">
      <TollZonesSection canWrite={canWrite} onDelete={requestDelete} />
      <AirportZonesSection canWrite={canWrite} onDelete={requestDelete} />

      <Modal
        open={deletingZone != null}
        onClose={() => {
          setDeletingZone(null);
          setDeleteError(null);
        }}
        title={deleteKind === "airport" ? "Delete airport pickup zone?" : "Delete toll zone?"}
        description={
          deleteKind === "airport"
            ? "This permanently removes the geofence. Hirings starting here will no longer have the airport access fee added."
            : "This permanently removes the geofence. Future trips will no longer auto-detect a toll crossing here."
        }
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingZone(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingZone) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deletingZone.id);
                  setDeletingZone(null);
                } catch (err) {
                  setDeleteError(extractErrorMessage(err));
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingZone && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingZone.name}</span> (
            {deletingZone.radius_m.toLocaleString()} m radius) will be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}

interface SectionProps {
  canWrite: boolean;
  onDelete: (zone: Geofence) => void;
}

/** The original toll-zone table, unchanged apart from moving under a section
 * heading now that it shares the tab with airport zones. */
function TollZonesSection({ canWrite, onDelete }: SectionProps) {
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingZone, setEditingZone] = useState<Geofence | null>(null);

  const zonesQuery = useGeofencesQuery({ kind: "toll", skip: page * PAGE_SIZE, limit: PAGE_SIZE });

  const zones = zonesQuery.data?.items ?? [];
  const total = zonesQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<Geofence>[] = [
    { key: "name", header: "Name", render: (row) => <span className="font-medium">{row.name}</span> },
    {
      key: "center",
      header: "Center",
      render: (row) => (
        <span className="font-mono text-xs">
          {row.center_lat.toFixed(5)}, {row.center_lng.toFixed(5)}
        </span>
      ),
    },
    { key: "radius_m", header: "Radius", render: (row) => `${row.radius_m.toLocaleString()} m` },
    { key: "toll_amount", header: "Toll amount", render: (row) => formatMoney(row.toll_amount) },
  ];

  if (canWrite) {
    columns.push({
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditingZone(row)}>
            <Pencil className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="icon" title="Delete" onClick={() => onDelete(row)}>
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
        </div>
      ),
    });
  }

  return (
    <section aria-labelledby="toll-zones-heading">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="toll-zones-heading" className="text-base font-semibold text-foreground">
            Toll zones (charged on entry)
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            A trip crossing into one of these circles auto-detects the toll from its GPS ticks.
          </p>
        </div>
        {canWrite && (
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" /> New toll zone
          </Button>
        )}
      </div>

      {zonesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load toll zones. Check the backend connection and try again.
        </p>
      )}

      <Card>
        <CardContent className="pt-4">
          <Table
            label="Toll zones"
            columns={columns}
            data={zones}
            rowKey={(row) => row.id}
            isLoading={zonesQuery.isLoading}
            emptyState="No toll zones yet — create one to start auto-detecting crossings from trip GPS ticks."
          />
        </CardContent>
      </Card>

      {pageCount > 1 && (
        <Pagination
          page={page}
          pageCount={pageCount}
          onPageChange={setPage}
          summary={
            <>
              Page {page + 1} of {pageCount} ({total} zone{total === 1 ? "" : "s"})
            </>
          }
        />
      )}

      <TollZoneFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" kind="toll" />

      <TollZoneFormModal
        open={editingZone != null}
        onClose={() => setEditingZone(null)}
        mode="edit"
        kind="toll"
        zone={editingZone ?? undefined}
      />
    </section>
  );
}

function AirportZonesSection({ canWrite, onDelete }: SectionProps) {
  const toast = useToast();
  const [createOpen, setCreateOpen] = useState(false);
  const [editingZone, setEditingZone] = useState<Geofence | null>(null);
  const [presetsOutcome, setPresetsOutcome] = useState<AddAirportPresetsResult | null>(null);
  const [presetsError, setPresetsError] = useState<string | null>(null);

  const zonesQuery = useGeofencesQuery({ kind: "airport", skip: 0, limit: AIRPORT_FETCH_LIMIT });
  // Only needed to decide whether the terminals are "already added"; a
  // non-owner cannot add them, so skip the request entirely.
  const presetsQuery = useAirportPresetsQuery({ enabled: canWrite });
  const addPresets = useAddAirportPresetsMutation();

  const zones = zonesQuery.data?.items ?? [];
  const hasZones = zones.length > 0;
  const existingNames = new Set(zones.map((z) => z.name.trim().toLowerCase()));
  const presets = presetsQuery.data ?? [];
  const allPresetsPresent =
    presets.length > 0 && presets.every((p) => existingNames.has(p.name.trim().toLowerCase()));

  async function handleAddPresets() {
    setPresetsError(null);
    setPresetsOutcome(null);
    try {
      const outcome = await addPresets.mutateAsync(zones.map((z) => z.name));
      setPresetsOutcome(outcome);
      if (outcome.failed.length === 0) {
        toast.success("Sydney Airport terminals added", {
          description: outcome.created.map((z) => z.name).join(", "),
        });
      } else {
        toast.error("Some airport zones were not added", {
          description: outcome.failed.map((f) => f.name).join(", "),
        });
      }
    } catch (err) {
      // The presets fetch itself failed -- nothing was created.
      setPresetsError(extractErrorMessage(err));
    }
  }

  const columns: TableColumn<Geofence>[] = [
    { key: "name", header: "Name", render: (row) => <span className="font-medium">{row.name}</span> },
    {
      key: "center",
      header: "Centre",
      render: (row) => (
        <span className="font-mono text-xs">
          {row.center_lat.toFixed(4)}, {row.center_lng.toFixed(4)}
        </span>
      ),
    },
    { key: "radius_m", header: "Radius", render: (row) => `${row.radius_m.toLocaleString()} m` },
    { key: "toll_amount", header: "Access fee", render: (row) => formatMoney(row.toll_amount) },
    {
      key: "scope",
      header: "Scope",
      render: (row) => (row.tenant_id == null ? "Global" : "This fleet"),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <OwnerOnly enabled={canWrite}>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Edit ${row.name}`}
              disabled={!canWrite}
              onClick={() => setEditingZone(row)}
            >
              <Pencil className="h-4 w-4" />
            </Button>
          </OwnerOnly>
          <OwnerOnly enabled={canWrite}>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Delete ${row.name}`}
              disabled={!canWrite}
              onClick={() => onDelete(row)}
            >
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </OwnerOnly>
        </div>
      ),
    },
  ];

  const presetsButton = (
    <OwnerOnly enabled={canWrite} hint={allPresetsPresent ? "Terminals already added" : undefined}>
      <Button
        onClick={handleAddPresets}
        disabled={!canWrite || allPresetsPresent || addPresets.isPending}
        aria-disabled={allPresetsPresent || undefined}
      >
        <Plane className="h-4 w-4" />
        {addPresets.isPending ? "Adding terminals…" : "Add Sydney Airport terminals (T1, T2, T3)"}
      </Button>
    </OwnerOnly>
  );

  const addCustomButton = (
    <OwnerOnly enabled={canWrite}>
      <Button variant={hasZones ? "primary" : "outline"} disabled={!canWrite} onClick={() => setCreateOpen(true)}>
        <Plus className="h-4 w-4" /> Add airport zone
      </Button>
    </OwnerOnly>
  );

  return (
    <section aria-labelledby="airport-zones-heading">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="max-w-3xl">
          <h2 id="airport-zones-heading" className="text-base font-semibold text-foreground">
            Airport pickup zones (charged when a hiring starts inside)
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            The airport access fee (Sydney Airport ground transport access fee, $6.43 GST inclusive, passed on to
            the passenger under the NSW Fares Order) is charged once when a hiring starts inside the zone — a
            pickup at the rank. Never on a drop-off (driving into the airport mid-trip), and never added under the
            Sydney Airport fixed fare ($60 standard / $80 maxi to the CBD), which already includes it.
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            Amount set by Sydney Airport; update it here when the airport changes its fee.
          </p>
        </div>
        {hasZones && (
          <div className="flex flex-wrap items-center gap-2">
            {/* Once presets are known: enabled if a terminal is still
                missing, disabled with "Terminals already added" if not. */}
            {presetsQuery.isSuccess && presetsButton}
            {addCustomButton}
          </div>
        )}
      </div>

      {zonesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load airport zones. Check the backend connection and try again.
        </p>
      )}

      {presetsError && (
        <p className="mb-3 text-sm text-destructive" role="alert">
          Could not load the Sydney Airport presets: {presetsError}
        </p>
      )}

      {presetsOutcome && presetsOutcome.failed.length > 0 && (
        <div className="mb-3 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
          <p className="font-medium">
            {presetsOutcome.created.length} of {presetsOutcome.created.length + presetsOutcome.failed.length} terminal
            zones added. Failed:
          </p>
          <ul className="mt-1 list-disc pl-5">
            {presetsOutcome.failed.map((f) => (
              <li key={f.name}>
                {f.name} — {f.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {presetsOutcome && presetsOutcome.failed.length === 0 && presetsOutcome.created.length > 0 && (
        <p className="mb-3 text-sm text-success" role="status">
          Added {presetsOutcome.created.map((z) => z.name).join(", ")}.
        </p>
      )}

      {!hasZones && !zonesQuery.isLoading && !zonesQuery.isError ? (
        <EmptyState
          icon={Plane}
          title="No airport pickup zones yet"
          description="Without a zone, no hiring picks up the airport access fee. Add the Sydney Airport terminal ranks in one go, or place a custom zone for another airport."
          action={
            <div className="flex flex-wrap items-center justify-center gap-2">
              {presetsButton}
              {addCustomButton}
            </div>
          }
        />
      ) : (
        <Card>
          <CardContent className="pt-4">
            <Table
              label="Airport pickup zones"
              columns={columns}
              data={zones}
              rowKey={(row) => row.id}
              isLoading={zonesQuery.isLoading}
              emptyState="No airport pickup zones."
            />
          </CardContent>
        </Card>
      )}

      <TollZoneFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        mode="create"
        kind="airport"
        siblingZones={zones}
      />

      <TollZoneFormModal
        open={editingZone != null}
        onClose={() => setEditingZone(null)}
        mode="edit"
        kind="airport"
        zone={editingZone ?? undefined}
        siblingZones={zones}
      />
    </section>
  );
}

/** Wraps a control that only the platform owner may use. When `enabled` is
 * false (or a `hint` is given) the child is shown disabled with a tooltip
 * saying why, rather than hidden -- an operator should see the action
 * exists and who can take it. */
function OwnerOnly({ enabled, hint, children }: { enabled: boolean; hint?: string; children: React.ReactNode }) {
  const content = !enabled ? OWNER_ONLY_HINT : hint;
  if (!content) return <>{children}</>;
  return <Tooltip content={content}>{children}</Tooltip>;
}
