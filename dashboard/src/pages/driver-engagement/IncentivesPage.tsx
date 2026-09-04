import { useState } from "react";
import { Pencil, Plus, Trash2 } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Modal,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useDeleteIncentiveMutation, useIncentivesQuery, type Incentive } from "./hooks";
import { IncentiveFormModal } from "./IncentiveFormModal";
import { extractErrorMessage, formatDateTime, formatMoney, windowStatus } from "./format";

const PAGE_SIZE = 15;

const ACTIVE_FILTER_OPTIONS = [
  { value: "", label: "All incentives" },
  { value: "true", label: "Active only" },
  { value: "false", label: "Inactive only" },
];

/** Incentives — operator CRUD over `/v1/incentives`, the campaigns behind
 * the driver tablet's "Incentive Progress" tile. Progress is never stored:
 * `GET /v1/me/incentives` counts each driver's closed trips inside the
 * window on every read. Mirrors `pages/vouchers/index.tsx` in shape. */
export default function IncentivesPage() {
  const { user } = useAuth();
  const canWrite = user?.role === "owner" || user?.role === "admin";

  const [activeFilter, setActiveFilter] = useState<"" | "true" | "false">("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<Incentive | null>(null);
  const [deleting, setDeleting] = useState<Incentive | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const query = useIncentivesQuery({
    active: activeFilter === "" ? "" : activeFilter === "true",
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const deleteMutation = useDeleteIncentiveMutation();
  const rows = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<Incentive>[] = [
    {
      key: "title",
      header: "Incentive",
      render: (row) => (
        <div className="max-w-md">
          <p className="font-medium">{row.title}</p>
          {row.description && <p className="truncate text-xs text-muted-foreground">{row.description}</p>}
        </div>
      ),
    },
    {
      key: "status",
      header: "Status",
      render: (row) => {
        const { label, variant } = windowStatus(row);
        return <Badge variant={variant}>{label}</Badge>;
      },
    },
    { key: "target_trips", header: "Target", render: (row) => `${row.target_trips} trips` },
    { key: "reward_aud", header: "Reward", render: (row) => formatMoney(row.reward_aud) },
    { key: "starts_at", header: "Starts", render: (row) => formatDateTime(row.starts_at) },
    { key: "ends_at", header: "Ends", render: (row) => formatDateTime(row.ends_at) },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) =>
        canWrite ? (
          <div className="flex justify-end gap-1">
            <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditing(row)}>
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Delete"
              onClick={() => {
                setDeleteError(null);
                setDeleting(row);
              }}
            >
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <div>
      <PageHeader
        title="Incentives"
        description="'Complete N trips in this window, earn $X' campaigns shown on the driver tablet. Each driver's progress is counted live from their closed trips — nothing to update by hand."
        actions={
          canWrite ? (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" /> New incentive
            </Button>
          ) : undefined
        }
      />

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Status</label>
            <Select
              className="w-44"
              options={ACTIVE_FILTER_OPTIONS}
              value={activeFilter}
              onChange={(e) => {
                setPage(0);
                setActiveFilter(e.target.value as "" | "true" | "false");
              }}
            />
          </div>
          {activeFilter && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setPage(0);
                setActiveFilter("");
              }}
            >
              Clear filters
            </Button>
          )}
        </CardContent>
      </Card>

      {query.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load incentives. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={rows}
        rowKey={(row) => row.id}
        isLoading={query.isLoading}
        emptyState="No incentives match these filters."
      />

      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount} ({total} incentives)
          </span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= pageCount - 1}
              onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      <IncentiveFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" />
      <IncentiveFormModal
        open={editing != null}
        onClose={() => setEditing(null)}
        mode="edit"
        incentive={editing ?? undefined}
      />

      <Modal
        open={deleting != null}
        onClose={() => {
          setDeleting(null);
          setDeleteError(null);
        }}
        title="Delete incentive?"
        description="This permanently removes the incentive from every driver tablet. To pause it instead, edit it and untick Active."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeleting(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deleting) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync(deleting.id);
                  setDeleting(null);
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
        {deleting && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deleting.title}</span> will be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
