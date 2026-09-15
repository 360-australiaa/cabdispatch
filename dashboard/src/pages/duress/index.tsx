import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, BellOff, Clock, Cpu, Siren } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Checkbox,
  PageHeader,
  Pagination,
  Select,
  Table,
  Tabs,
  type TabItem,
} from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { useAuth } from "@/lib/auth";
import { closeDuressEvent, listDuressEvents } from "./api";
import { DevicesPanel } from "./DevicesPanel";
import { EventDetailPanel } from "./EventDetailPanel";
import { IdentityLabel } from "./IdentityLabel";
import { TriggerEventModal } from "./TriggerEventModal";
import { formatDateTime, isStaleEvent, statusBadgeVariant } from "./format";
import type { DuressEvent, DuressStatus } from "./types";
import { useDuressAlerts } from "./useDuressAlerts";
import { useDuressLookups } from "./useDuressLookups";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";

/** Same owner/admin/dispatcher gate the backend puts on `POST /{id}/close`
 * (and that `EventDetailPanel` already mirrors) -- the list-row Close button
 * below must not render for a role that would only ever get a 403. */
const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);

type ViewTab = "events" | "devices";

const VIEW_TABS: TabItem<ViewTab>[] = [
  { value: "events", label: "Events", icon: Siren },
  { value: "devices", label: "Devices", icon: Cpu },
];

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
    // There is no fleet-wide WS that pushes duress-event list changes today
    // (only the per-event live GPS socket, WS /v1/duress/{id}/live, opened
    // once an event is already selected) -- see
    // docs/followups/2026-09-08-redis-pubsub-broadcasters.md and this
    // module's own upstream doc comment in lib/pollIntervals.ts ("moving
    // them onto the socket is a separate change with its own risk, not a
    // rename"). Adding that endpoint is backend work, out of scope for this
    // (dashboard-only) workstream, so this polls at the fastest named band
    // instead of the plain list band it used before, and the alert hook
    // below is what actually gets a new event in front of an operator
    // without them staring at the table.
    ...pollingQueryOptions(POLL.REALTIME),
  });

  const lookups = useDuressLookups();
  const { armed, notifPermission, arm } = useDuressAlerts(eventsQuery.data?.items);

  const { user } = useAuth();
  const canManage = !!user && MANAGE_ROLES.has(user.role);
  const queryClient = useQueryClient();

  // Stale events (admin plan §1.4: four rows sat "dispatched" for two weeks)
  // get a Close button right on the list row, so tidying a forgotten test
  // event is one click rather than open-the-panel-then-close. Same
  // `POST /{id}/close` the detail panel calls; the note explains where the
  // close came from in the escalation log.
  const closeStaleMutation = useMutation({
    mutationFn: (eventId: string) =>
      closeDuressEvent(eventId, { note: "Closed from the Duress Desk list as stale" }),
    onSuccess: (_event, eventId) => {
      queryClient.invalidateQueries({ queryKey: ["duress-events"] });
      queryClient.invalidateQueries({ queryKey: ["duress-event", eventId] });
    },
  });
  const closingId = closeStaleMutation.isPending ? closeStaleMutation.variables : null;

  const total = eventsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<DuressEvent>[] = [
    {
      key: "id",
      header: "Event",
      render: (row) => <span className="font-mono text-xs">{row.id.slice(0, 8)}</span>,
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (row) => (
        <IdentityLabel
          id={row.vehicle_id}
          label={lookups.resolveVehicle(row.vehicle_id)?.rego ?? null}
          isLoading={lookups.isLoading}
          kind="vehicle"
        />
      ),
    },
    {
      key: "driver_id",
      header: "Driver",
      render: (row) =>
        // Server-joined name first (never a UUID for the operator), the
        // first-100 client lookup only when the backend didn't send one.
        row.driver_name ? (
          <span title={row.driver_id}>{row.driver_name}</span>
        ) : (
          <IdentityLabel
            id={row.driver_id}
            label={lookups.resolveDriver(row.driver_id)?.name ?? null}
            isLoading={lookups.isLoading}
            kind="driver"
          />
        ),
    },
    {
      key: "trigger",
      header: "Trigger",
      render: (row) => <span className="capitalize">{row.trigger}</span>,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge variant={statusBadgeVariant(row.status)}>{row.status}</Badge>
          {isStaleEvent(row) && (
            <Badge
              variant="outline"
              title="Open for more than 12 hours with no resolution — almost certainly a forgotten test or false alarm"
            >
              <Clock className="h-3 w-3" /> Stale
            </Badge>
          )}
        </span>
      ),
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
    ...(canManage
      ? [
          {
            key: "actions",
            header: "",
            className: "text-right",
            render: (row: DuressEvent) =>
              isStaleEvent(row) ? (
                <div className="flex justify-end" onClick={(e) => e.stopPropagation()}>
                  <Button
                    variant="outline"
                    size="sm"
                    title="Close / resolve this stale event without opening it"
                    disabled={closingId === row.id}
                    onClick={() => closeStaleMutation.mutate(row.id)}
                  >
                    {closingId === row.id ? "Closing…" : "Close"}
                  </Button>
                </div>
              ) : null,
          } satisfies TableColumn<DuressEvent>,
        ]
      : []),
  ];

  return (
    <div>
      <PageHeader
        title="Duress Desk"
        description="Open and escalating panic-button events across the fleet, with a live GPS trace while an event is under review."
        actions={
          tab === "events" ? (
            <div className="flex items-center gap-2">
              <Button
                variant={armed ? "outline" : "secondary"}
                onClick={arm}
                disabled={armed && notifPermission !== "default"}
                title={
                  armed
                    ? notifPermission === "granted"
                      ? "Alerts on: sound + desktop notification for new open events"
                      : notifPermission === "denied"
                        ? "Desktop notifications were denied in the browser — sound still plays for new open events"
                        : "Sound is on for new open events"
                    : "Turn on a sound (and, if you allow it, a desktop notification) for new open duress events"
                }
              >
                {armed ? <Bell className="h-4 w-4" /> : <BellOff className="h-4 w-4" />}
                {armed ? "Alerts on" : "Enable alerts"}
              </Button>
              <Button variant="destructive" onClick={() => setTriggerModalOpen(true)}>
                <Siren className="h-4 w-4" /> Trigger event
              </Button>
            </div>
          ) : undefined
        }
      />

      <Tabs
        items={VIEW_TABS}
        value={tab}
        onChange={setTab}
        variant="pill"
        label="Duress desk sections"
        className="mb-4"
      />

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
              <Checkbox
                label="Open events only"
                wrapperClassName="mb-2"
                checked={openOnly}
                onChange={(e) => {
                  setOpenOnly(e.target.checked);
                  setPage(0);
                }}
              />
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

          {closeStaleMutation.isError && (
            <p className="text-sm text-destructive">
              Could not close that event — it may already be closed, or another dispatcher got
              there first. Refresh and try again.
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
            <Pagination page={page} pageCount={pageCount} onPageChange={setPage} />
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
