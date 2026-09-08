import { useState } from "react";
import { Pencil, Plus, Trash2 } from "lucide-react";
import {
  Button,
  Card,
  CardContent,
  Modal,
  Pagination,
  Table,
  type TableColumn,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { isPlatformOwner } from "@/lib/platformAdmin";
import {
  useDeleteGeofenceMutation,
  useGeofencesQuery,
  type Geofence,
} from "@/hooks/useGeofences";
import { extractErrorMessage, formatMoney } from "./format";
import { TollZoneFormModal } from "./TollZoneFormModal";

const PAGE_SIZE = 15;

/** Toll Zones tab of Tariff Studio — CRUD over `/v1/geofences?kind=toll`
 * circular zones (name, center lat/lng, radius in meters, toll amount).
 * Region-kind geofences (platform reference zones) are out of scope here;
 * this panel only lists/creates `kind: "toll"`. Toll pricing is pricing
 * (product decision, 2026): create/edit/delete are platform-owner gated
 * server-side (`geofences.py`'s `_require_platform_owner_for_toll`), same
 * gate as the sibling Rate Cards tab (`pages/tariffs/index.tsx`) and the
 * Platform Admin console (`src/lib/platformAdmin.ts`). */
export function TollZonesPanel() {
  const { user } = useAuth();
  const canWrite = isPlatformOwner(user);

  const [page, setPage] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [editingZone, setEditingZone] = useState<Geofence | null>(null);
  const [deletingZone, setDeletingZone] = useState<Geofence | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const zonesQuery = useGeofencesQuery({ kind: "toll", skip: page * PAGE_SIZE, limit: PAGE_SIZE });
  const deleteMutation = useDeleteGeofenceMutation();

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
          <Button
            variant="ghost"
            size="icon"
            title="Delete"
            onClick={() => {
              setDeleteError(null);
              setDeletingZone(row);
            }}
          >
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
        </div>
      ),
    });
  }

  return (
    <div>
      {canWrite && (
        <div className="mb-4 flex items-center justify-end">
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" /> New toll zone
          </Button>
        </div>
      )}

      {zonesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load toll zones. Check the backend connection and try again.
        </p>
      )}

      <Card>
        <CardContent className="pt-4">
          <Table
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

      <TollZoneFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" />

      <TollZoneFormModal
        open={editingZone != null}
        onClose={() => setEditingZone(null)}
        mode="edit"
        zone={editingZone ?? undefined}
      />

      <Modal
        open={deletingZone != null}
        onClose={() => {
          setDeletingZone(null);
          setDeleteError(null);
        }}
        title="Delete toll zone?"
        description="This permanently removes the geofence. Future trips will no longer auto-detect a toll crossing here."
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
