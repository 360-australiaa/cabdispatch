import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Radio, Send } from "lucide-react";
import { Badge, Button, Card, CardContent, PageHeader, Pagination, Select, Table } from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { useDriverOptionsQuery } from "@/pages/driver-engagement/hooks";
import { listJobs } from "./api";
import { CreateJobModal } from "./CreateJobModal";
import { JobDetailPanel } from "./JobDetailPanel";
import { formatDateTime, formatMoney, jobStatusBadgeVariant } from "./format";
import { isTerminalJobStatus, type Job } from "./types";
import { useJobsLive } from "./useJobsLive";
import { POLL, pollingQueryOptions, whileActive } from "@/lib/pollIntervals";

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
 *
 * Live updates (admin plan §3): the page now subscribes to the same
 * `WS /v1/jobs/live` feed via `useJobsLive` and refetches on every frame.
 * Polling is the fallback, not the primary path -- see `pollInterval` below
 * for the exact policy and the driver-scoped-feed caveat.
 */
export default function DispatchPage() {
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const live = useJobsLive();
  const socketOpen = live.state === "open";
  // Only a frame that actually arrived counts as "the socket is doing its
  // job" -- see the policy on `pollInterval` below and `deliveringFrames` in
  // `useJobsLive.ts`.
  const socketDelivering = live.deliveringFrames;

  const anyActiveJob = (jobs: Job[] | undefined) =>
    !!jobs && jobs.some((j) => !isTerminalJobStatus(j.status));

  // Poll policy. 2026-09-19: this used to read `socketOpen ? ROSTER :
  // REALTIME`, which made the page TEN TIMES staler (3 s -> 30 s) the instant
  // a socket connected -- even though the backend feed is driver-scoped
  // (`app/api/v1/jobs.py::live` subscribes the connecting user's own id), so a
  // dispatcher's socket opens and then never delivers a frame. A useless
  // socket was buying a 30 s staleness penalty.
  //
  // The gate is now delivery, not connection: slow down only while a frame has
  // actually arrived recently (`FRAME_TRUST_WINDOW_MS`). Otherwise --
  // connecting, closed, error, no token, or open-but-silent -- keep the fast
  // poll, still only while a job is genuinely in flight (`whileActive`).
  //
  // This is a PERMANENT safety net, not a stopgap for the narrow feed. When
  // the parallel backend change widens the fan-out to dispatchers, frames
  // start arriving and this expression allows the slow band again on its own.
  // It must not be simplified back to `socketOpen` afterwards: an open socket
  // will never prove frames are flowing (half-open connections, a proxy
  // holding the socket up, a role or fan-out change), and the failure is
  // silent -- a green badge over data that stopped moving.
  const pollInterval = socketDelivering ? POLL.ROSTER : POLL.REALTIME;

  const jobsQuery = useQuery({
    queryKey: ["dispatch-jobs", page, statusFilter],
    queryFn: () =>
      listJobs({ limit: PAGE_SIZE, skip: page * PAGE_SIZE, status: statusFilter || undefined }),
    placeholderData: (prev) => prev,
    ...pollingQueryOptions(
      whileActive(pollInterval, (query: { state: { data?: { items?: Job[] } } }) =>
        anyActiveJob(query.state.data?.items),
      ),
    ),
  });

  const total = jobsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const driversQuery = useDriverOptionsQuery();
  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of driversQuery.data ?? []) map.set(d.id, d.name);
    return map;
  }, [driversQuery.data]);

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
      key: "accepted_by_driver_id",
      header: "Driver",
      render: (row) =>
        row.accepted_by_driver_id ? (
          driverNameById.get(row.accepted_by_driver_id) ?? `${row.accepted_by_driver_id.slice(0, 8)}…`
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
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
        description="Create jobs and watch them broadcast to available drivers in real time — drivers answer on the Android app, or dispatch can accept or decline an offer on their behalf."
        actions={
          <div className="flex items-center gap-2">
            <LiveFeedBadge state={live.state} />
            <Button variant="primary" onClick={() => setCreateOpen(true)}>
              <Send className="h-4 w-4" /> New job
            </Button>
          </div>
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
            <Pagination page={page} pageCount={pageCount} onPageChange={setPage} />
          )}
        </div>

        <div className="lg:col-span-1">
          {selectedId ? (
            <JobDetailPanel jobId={selectedId} onClose={() => setSelectedId(null)} live={socketOpen} />
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

/** Tells the operator which path is keeping the table fresh, so "why did
 * that offer take ten seconds to appear" has an answer on screen. */
function LiveFeedBadge({ state }: { state: ReturnType<typeof useJobsLive>["state"] }) {
  switch (state) {
    case "open":
      return (
        <Badge variant="success" title="Connected to the live job feed">
          <Radio className="h-3 w-3" /> Live
        </Badge>
      );
    case "connecting":
    case "closed":
      return (
        <Badge variant="outline" title="Reconnecting to the live job feed — polling meanwhile">
          Reconnecting…
        </Badge>
      );
    case "error":
      return (
        <Badge variant="accent" title="Live feed unavailable — polling instead">
          Polling
        </Badge>
      );
    default:
      return null;
  }
}
