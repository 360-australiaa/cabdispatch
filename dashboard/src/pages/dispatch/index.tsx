import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Send } from "lucide-react";
import { Badge, Button, Card, CardContent, PageHeader, Select, Table } from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { listJobs } from "./api";
import { CreateJobModal } from "./CreateJobModal";
import { JobDetailPanel } from "./JobDetailPanel";
import { formatDateTime, formatMoney, jobStatusBadgeVariant } from "./format";
import { isTerminalJobStatus, type Job } from "./types";

const PAGE_SIZE = 20;

const STATUS_FILTER_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "All statuses" },
  { value: "queued", label: "Queued" },
  { value: "offered", label: "Offered" },
  { value: "accepted", label: "Accepted" },
  { value: "expired", label: "Expired" },
  { value: "cancelled", label: "Cancelled" },
];

/**
 * Dispatch — creates jobs (`POST /v1/jobs`), which broadcasts a `JobOffer` to
 * every currently-available driver and shows up on the Android app's
 * Available Trips wheel slot in real time over `WS /v1/jobs/live`. This page
 * is the dashboard-side half of that loop: previously the jobs/messages
 * domain was backend-only, with no way to actually create a job to test the
 * driver-side accept/decline flow against.
 */
export default function DispatchPage() {
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const anyActiveJob = (jobs: Job[] | undefined) =>
    !!jobs && jobs.some((j) => !isTerminalJobStatus(j.status));

  const jobsQuery = useQuery({
    queryKey: ["dispatch-jobs", page, statusFilter],
    queryFn: () =>
      listJobs({ limit: PAGE_SIZE, skip: page * PAGE_SIZE, status: statusFilter || undefined }),
    placeholderData: (prev) => prev,
    refetchInterval: (query) => (anyActiveJob(query.state.data?.items) ? 3000 : false),
  });

  const total = jobsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<Job>[] = [
    {
      key: "id",
      header: "Job",
      render: (row) => <span className="font-mono text-xs">{row.id.slice(0, 8)}</span>,
    },
    { key: "origin_address", header: "Pickup", render: (row) => row.origin_address },
    { key: "dest_address", header: "Drop-off", render: (row) => row.dest_address },
    {
      key: "fare_estimate_low",
      header: "Est. fare",
      render: (row) => `${formatMoney(row.fare_estimate_low)} – ${formatMoney(row.fare_estimate_high)}`,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={jobStatusBadgeVariant(row.status)}>{row.status}</Badge>,
      sortable: true,
      sortAccessor: (row) => row.status,
    },
    {
      key: "requested_at",
      header: "Requested",
      render: (row) => formatDateTime(row.requested_at),
      sortable: true,
      sortAccessor: (row) => row.requested_at,
    },
  ];

  return (
    <div>
      <PageHeader
        title="Dispatch"
        description="Create jobs and watch them broadcast to available drivers in real time — accept/decline happens on the driver's Android app."
        actions={
          <Button variant="primary" onClick={() => setCreateOpen(true)}>
            <Send className="h-4 w-4" /> New job
          </Button>
        }
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="flex flex-col gap-4 lg:col-span-2">
          <Card>
            <CardContent className="flex flex-wrap items-end gap-3 pt-4">
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">Status</label>
                <Select
                  className="w-44"
                  options={STATUS_FILTER_OPTIONS}
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
              <span className="mb-2 ml-auto text-xs text-muted-foreground">
                {total} job{total === 1 ? "" : "s"}
              </span>
            </CardContent>
          </Card>

          {jobsQuery.isError && (
            <p className="text-sm text-destructive">
              Failed to load jobs. Check the backend connection and try again.
            </p>
          )}

          <Table
            columns={columns}
            data={jobsQuery.data?.items ?? []}
            rowKey={(row) => row.id}
            isLoading={jobsQuery.isLoading}
            onRowClick={(row) => setSelectedId(row.id)}
            emptyState="No jobs match these filters. Create one to broadcast it to available drivers."
          />

          {pageCount > 1 && (
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>
                Page {page + 1} of {pageCount}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
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
        </div>

        <div className="lg:col-span-1">
          {selectedId ? (
            <JobDetailPanel jobId={selectedId} onClose={() => setSelectedId(null)} />
          ) : (
            <Card>
              <CardContent className="flex h-40 items-center justify-center text-center text-sm text-muted-foreground">
                Select a job to see its live offer status.
              </CardContent>
            </Card>
          )}
        </div>
      </div>

      <CreateJobModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(job) => {
          setCreateOpen(false);
          setSelectedId(job.id);
          jobsQuery.refetch();
        }}
      />
    </div>
  );
}
