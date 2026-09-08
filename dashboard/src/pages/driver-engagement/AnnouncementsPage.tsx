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
import {
  useAnnouncementsQuery,
  useDeleteAnnouncementMutation,
  type Announcement,
  type AnnouncementKind,
} from "./hooks";
import { AnnouncementFormModal } from "./AnnouncementFormModal";
import { ANNOUNCEMENT_KIND_LABELS, extractErrorMessage, formatDateTime, windowStatus } from "./format";

const PAGE_SIZE = 15;

const ACTIVE_FILTER_OPTIONS = [
  { value: "", label: "All announcements" },
  { value: "true", label: "Active only" },
  { value: "false", label: "Inactive only" },
];

const KIND_FILTER_OPTIONS = [
  { value: "", label: "All kinds" },
  ...(Object.keys(ANNOUNCEMENT_KIND_LABELS) as AnnouncementKind[]).map((k) => ({
    value: k,
    label: ANNOUNCEMENT_KIND_LABELS[k],
  })),
];

/** Announcements — operator CRUD over `/v1/announcements`, the content behind
 * the driver tablet's "Announcements" tile (`GET /v1/me/announcements` shows
 * drivers only the rows that are active and inside their window). Mirrors
 * `pages/vouchers/index.tsx` in shape. */
export default function AnnouncementsPage() {
  const { user } = useAuth();
  const canWrite = user?.role === "owner" || user?.role === "admin";

  const [activeFilter, setActiveFilter] = useState<"" | "true" | "false">("");
  const [kindFilter, setKindFilter] = useState<AnnouncementKind | "">("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<Announcement | null>(null);
  const [deleting, setDeleting] = useState<Announcement | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const query = useAnnouncementsQuery({
    active: activeFilter === "" ? "" : activeFilter === "true",
    kind: kindFilter,
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const deleteMutation = useDeleteAnnouncementMutation();
  const rows = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const hasFilters = activeFilter !== "" || kindFilter !== "";

  const columns: TableColumn<Announcement>[] = [
    {
      key: "title",
      header: "Title",
      render: (row) => (
        <div className="max-w-md">
          <p className="font-medium">{row.title}</p>
          <p className="truncate text-xs text-muted-foreground">{row.body}</p>
        </div>
      ),
    },
    {
      key: "kind",
      header: "Kind",
      render: (row) => <Badge variant="outline">{ANNOUNCEMENT_KIND_LABELS[row.kind] ?? row.kind}</Badge>,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => {
        const { label, variant } = windowStatus(row);
        return <Badge variant={variant}>{label}</Badge>;
      },
    },
    { key: "starts_at", header: "Starts", render: (row) => formatDateTime(row.starts_at) },
    {
      key: "ends_at",
      header: "Ends",
      render: (row) => (row.ends_at ? formatDateTime(row.ends_at) : <span className="text-muted-foreground">Open-ended</span>),
    },
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
        title="Announcements"
        description="Notices pushed to every driver tablet in your fleet. Drivers see an announcement while it is active and inside its start/end window."
        actions={
          canWrite ? (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" /> New announcement
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
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Kind</label>
            <Select
              className="w-44"
              options={KIND_FILTER_OPTIONS}
              value={kindFilter}
              onChange={(e) => {
                setPage(0);
                setKindFilter(e.target.value as AnnouncementKind | "");
              }}
            />
          </div>
          {hasFilters && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setPage(0);
                setActiveFilter("");
                setKindFilter("");
              }}
            >
              Clear filters
            </Button>
          )}
        </CardContent>
      </Card>

      {query.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load announcements. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={rows}
        rowKey={(row) => row.id}
        isLoading={query.isLoading}
        emptyState="No announcements match these filters."
      />

      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount} ({total} announcements)
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

      <AnnouncementFormModal open={createOpen} onClose={() => setCreateOpen(false)} mode="create" />
      <AnnouncementFormModal
        open={editing != null}
        onClose={() => setEditing(null)}
        mode="edit"
        announcement={editing ?? undefined}
      />

      <Modal
        open={deleting != null}
        onClose={() => {
          setDeleting(null);
          setDeleteError(null);
        }}
        title="Delete announcement?"
        description="This permanently removes the announcement from every driver tablet. To hide it temporarily, edit it and untick Active instead."
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
