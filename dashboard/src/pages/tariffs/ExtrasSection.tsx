import { useState } from "react";
import { Pencil, Plus, Receipt, Trash2 } from "lucide-react";
import { Badge, Button, Modal, Table, type TableColumn } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useDeleteExtraMutation, useExtrasQuery, type Extra } from "@/hooks/useTariffStudio";
import { extractErrorMessage, formatMoney } from "./format";
import { ExtraFormModal } from "./ExtraFormModal";

export interface ExtrasSectionProps {
  tariffId: string;
}

const EXTRA_TYPE_LABELS: Record<Extra["type"], string> = {
  fixed: "Fixed",
  passthrough: "Passthrough",
};

/** "Extras" section embedded in `TariffFormModal`'s edit-mode body — full
 * CRUD over the named fixed/passthrough fees scoped to this tariff
 * (`/v1/tariffs/{tariffId}/extras`, see backend/app/api/v1/tariffs.py).
 * Only rendered in edit mode: an Extra needs an existing tariff_id to
 * attach to, so a brand-new tariff being created has nowhere for one to go
 * yet. Create/edit/delete are owner/admin gated server-side — same
 * `canWrite` pattern the rest of this page (`index.tsx`, `TollZonesPanel`)
 * already uses; viewing the list stays open to every authenticated role.
 *
 * Every button here that isn't inside its own portal-rendered Modal is
 * given an explicit `type="button"` — this section renders inside the
 * outer `<form id="tariff-form">` in `TariffFormModal`, so an un-typed
 * `<button>` would default to `type="submit"` and prematurely submit the
 * tariff form. */
export function ExtrasSection({ tariffId }: ExtrasSectionProps) {
  const { user } = useAuth();
  const canWrite = user?.role === "owner" || user?.role === "admin";

  const [createOpen, setCreateOpen] = useState(false);
  const [editingExtra, setEditingExtra] = useState<Extra | null>(null);
  const [deletingExtra, setDeletingExtra] = useState<Extra | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const extrasQuery = useExtrasQuery(tariffId, { limit: 100 });
  const deleteMutation = useDeleteExtraMutation();
  const extras = extrasQuery.data?.items ?? [];

  const columns: TableColumn<Extra>[] = [
    { key: "name", header: "Name", render: (row) => <span className="font-medium">{row.name}</span> },
    { key: "amount", header: "Amount", render: (row) => formatMoney(row.amount) },
    {
      key: "type",
      header: "Type",
      render: (row) => <Badge variant="outline">{EXTRA_TYPE_LABELS[row.type]}</Badge>,
    },
  ];

  if (canWrite) {
    columns.push({
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            title="Edit extra"
            onClick={() => setEditingExtra(row)}
          >
            <Pencil className="h-4 w-4" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            title="Delete extra"
            onClick={() => {
              setDeleteError(null);
              setDeletingExtra(row);
            }}
          >
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
        </div>
      ),
    });
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-border p-3">
      <div className="flex items-center justify-between">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Receipt className="h-4 w-4" /> Extras
        </h3>
        {canWrite && (
          <Button type="button" size="sm" onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" /> Add extra
          </Button>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        Named fixed or passthrough fees scoped to this tariff (e.g. a cleaning fee or equipment surcharge).
      </p>

      {extrasQuery.isError && <p className="text-sm text-destructive">Failed to load extras.</p>}

      <Table
        columns={columns}
        data={extras}
        rowKey={(row) => row.id}
        isLoading={extrasQuery.isLoading}
        emptyState="No extras on this tariff yet."
      />

      <ExtraFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" tariffId={tariffId} />

      <ExtraFormModal
        open={editingExtra != null}
        onClose={() => setEditingExtra(null)}
        mode="edit"
        tariffId={tariffId}
        extra={editingExtra ?? undefined}
      />

      <Modal
        open={deletingExtra != null}
        onClose={() => {
          setDeletingExtra(null);
          setDeleteError(null);
        }}
        title="Delete extra?"
        description="This permanently removes the fee from this tariff."
        footer={
          <>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setDeletingExtra(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingExtra) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync({ tariffId, extraId: deletingExtra.id });
                  setDeletingExtra(null);
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
        {deletingExtra && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingExtra.name}</span> will be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
