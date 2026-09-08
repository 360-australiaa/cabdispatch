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
import { useDeleteZoneMutation, useZonesQuery, type Zone } from "@/hooks/useZones";
import { extractErrorMessage, formatCoords } from "./format";
import { ZoneFormModal } from "./ZoneFormModal";

const PAGE_SIZE = 20;

/** Zone list/CRUD tab -- name, number, center lat/lng, radius. Plotting is
 * platform-admin-only (product decision, 2026: "as an admin, we are setting
 * up the plotting, not the network") -- create/edit/delete are
 * platform-owner gated server-side (POST/PUT/DELETE /v1/zones), same gate
 * as the Platform Admin console (`src/lib/platformAdmin.ts`); the dashboard
 * hides the write affordances for other roles rather than letting them hit
 * a 403. */
export function ZonesPanel() {
  const { user } = useAuth();
  const canWrite = isPlatformOwner(user);

  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingZone, setEditingZone] = useState<Zone | null>(null);
  const [deletingZone, setDeletingZone] = useState<Zone | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const zonesQuery = useZonesQuery(page * PAGE_SIZE, PAGE_SIZE);
  const deleteMutation = useDeleteZoneMutation();

  const zones = zonesQuery.data?.items ?? [];
  const total = zonesQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<Zone>[] = [
    {
      key: "number",
      header: "#",
      className: "w-16",
      render: (row) => <span className="font-mono font-semibold">{row.number}</span>,
    },
    { key: "name", header: "Name", render: (row) => <span className="font-medium">{row.name}</span> },
    {
      key: "center",
      header: "Center",
      render: (row) => <span className="font-mono text-xs">{formatCoords(row.center_lat, row.center_lng)}</span>,
    },
    { key: "radius_m", header: "Radius", render: (row) => `${row.radius_m.toLocaleString()} m` },
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
            <Plus className="h-4 w-4" /> New zone
          </Button>
        </div>
      )}

      {zonesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load zones. Check the backend connection and try again.
        </p>
      )}

      <Card>
        <CardContent className="pt-4">
          <Table
            columns={columns}
            data={zones}
            rowKey={(row) => row.id}
            isLoading={zonesQuery.isLoading}
            emptyState="No dispatch zones yet -- create one so drivers can plot into it from their app."
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

      {canWrite && (
        <>
          <ZoneFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" />

          <ZoneFormModal
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
            title="Delete zone?"
            description="This permanently removes the dispatch zone. Drivers will no longer be able to plot into it."
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
                  {deleteMutation.isPending ? "Deleting..." : "Delete"}
                </Button>
              </>
            }
          >
            {deletingZone && (
              <p className="text-sm text-muted-foreground">
                <span className="font-medium text-foreground">
                  {deletingZone.number} - {deletingZone.name}
                </span>{" "}
                will be permanently removed.
              </p>
            )}
            {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
          </Modal>
        </>
      )}
    </div>
  );
}
