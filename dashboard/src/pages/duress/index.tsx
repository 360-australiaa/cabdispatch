import { useState, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Cpu, Siren } from "lucide-react";
import { Badge, Button, Card, CardContent, PageHeader, Select, Table } from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { cn } from "@/lib/utils";
import { listDuressEvents } from "./api";
import { DevicesPanel } from "./DevicesPanel";
import { EventDetailPanel } from "./EventDetailPanel";
import { TriggerEventModal } from "./TriggerEventModal";
import { formatDateTime, statusBadgeVariant } from "./format";
import type { DuressEvent, DuressStatus } from "./types";

type ViewTab = "events" | "devices";

const PAGE_SIZE = 20;

const STATUS_FILTER_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "All statuses" },
  { value: "open", label: "Open" },
  { value: "escalating", label: "Escalating" },
  { value: "dispatched", label: "Dispatched" },
  { value: "resolved", label: "Resolved" },
  { value: "cancelled", label: "Cancelled" },
];

export default function DuressPage() {
  // Live Map's "jump to this incident" links (and any other deep link) land
  // here as /duress?event=<id> -- read it once on mount so the link actually
  // opens the event instead of dumping the dispatcher on the plain list.
  // This page's own row-click/close controls own selectedId from then on.
  const [searchParams] = useSearchParams();

  const [tab, setTab] = useState<ViewTab>("events");
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [openOnly, setOpenOnly] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(() => searchParams.get("event"));
  const [triggerModalOpen, setTriggerModalOpen] = useState(false);

  const eventsQuery = useQuery({
    queryKey: ["duress-events", page, statusFilter, openOnly],
    queryFn: () =>
      listDuressEvents({
        limit: PAGE_SIZE,
        offset: page * PAGE_SIZE,
        status: statusFilter || undefined,
        open_only: openOnly || undefined,
      }),
    placeholderData: (prev) => prev,
    // This is a safety desk: a new incident (or a status change from another
    // dispatcher's action) must show up without anyone touching a filter.
    refetchInterval: 10_000,
  });

  const total = eventsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<DuressEvent>[] = [
    {
      key: "id",
      header: "Event",
      render: (row) => <span className="font-mono text-xs">{row.id.slice(0, 8)}</span>,
    },
    { key: "vehicle_id", header: "Vehicle", render: (row) => row.vehicle_id },
    { key: "driver_id", header: "Driver", render: (row) => row.driver_id },
    {
      key: "trigger",
      header: "Trigger",
      render: (row) => <span className="capitalize">{row.trigger}</span>,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={statusBadgeVariant(row.status)}>{row.status}</Badge>,
      sortable: true,
      sortAccessor: (row) => row.status,
    },
    {
      key: "opened_at",
      header: "Opened",
      render: (row) => formatDateTime(row.opened_at),
      sortable: true,
      sortAccessor: (row) => row.opened_at,
    },
  ];

  return (
    <div>
      <PageHeader
        title="Duress Desk"
        description="Open and escalating panic-button events across the fleet, with a live GPS trace while an event is under review."
        actions={
          tab === "events" ? (
            <Button variant="destructive" onClick={() => setTriggerModalOpen(true)}>
              <Siren className="h-4 w-4" /> Trigger event
            </Button>
          ) : undefined
        }
      />

      <div className="mb-4 inline-flex rounded-md border border-border bg-muted p-1">
        <TabButton active={tab === "events"} onClick={() => setTab("events")} icon={<Siren className="h-4 w-4" />}>
          Events
        </TabButton>
        <TabButton active={tab === "devices"} onClick={() => setTab("devices")} icon={<Cpu className="h-4 w-4" />}>
          Devices
        </TabButton>
      </div>

      {tab === "devices" && <DevicesPanel />}

      {tab === "events" && (
      <>
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
                    setStatusFilter(e.target.value as DuressStatus | "");
                    setPage(0);
                  }}
                />
              </div>
              <label className="mb-2 flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-border"
                  checked={openOnly}
                  onChange={(e) => {
                    setOpenOnly(e.target.checked);
                    setPage(0);
                  }}
                />
                Open events only
              </label>
              <span className="mb-2 ml-auto text-xs text-muted-foreground">
                {total} event{total === 1 ? "" : "s"}
              </span>
            </CardContent>
          </Card>

          {eventsQuery.isError && (
            <p className="text-sm text-destructive">
              Failed to load duress events. Check the backend connection and try again.
            </p>
          )}

          <Table
            columns={columns}
            data={eventsQuery.data?.items ?? []}
            rowKey={(row) => row.id}
            isLoading={eventsQuery.isLoading}
            onRowClick={(row) => setSelectedId(row.id)}
            emptyState="No duress events match these filters."
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
            <EventDetailPanel
              eventId={selectedId}
              onClose={() => setSelectedId(null)}
              onDeleted={() => setSelectedId(null)}
            />
          ) : (
            <Card>
              <CardContent className="flex h-40 items-center justify-center text-center text-sm text-muted-foreground">
                Select an event to view its escalation timeline and live GPS trace.
              </CardContent>
            </Card>
          )}
        </div>
      </div>

      <TriggerEventModal
        open={triggerModalOpen}
        onClose={() => setTriggerModalOpen(false)}
        onCreated={(event) => {
          setTriggerModalOpen(false);
          setSelectedId(event.id);
          eventsQuery.refetch();
        }}
      />
      </>
      )}
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
  icon,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-medium transition-colors",
        active ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
      )}
    >
      {icon}
      {children}
    </button>
  );
}
