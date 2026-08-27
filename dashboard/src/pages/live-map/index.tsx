import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { AlertTriangle, BatteryFull, BatteryLow, BatteryMedium, BatteryWarning, Radio, RadioTower, WifiOff } from "lucide-react";
import apiClient from "@/lib/apiClient";
import { useAuth } from "@/lib/auth";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { useFleetLiveSocket } from "@/hooks/useLiveMap";
import { FleetMapCanvas } from "./FleetMapCanvas";
import { PublishPositionModal } from "./PublishPositionModal";
import type { DuressEventListResponse, DuressEventRead, Page, VehicleLiveRead } from "./types";
import {
  batteryColor,
  formatLatLng,
  formatRelativeTime,
  isStale,
  mergeLivePosition,
  networkBadgeVariant,
  statusBadgeVariant,
} from "./utils";

const TABLE_PAGE_SIZE = 10;
const MAP_FETCH_LIMIT = 100; // GET /v1/vehicles caps `limit` at 100 server-side.

const LIVE_STATUS_OPTIONS = [
  { value: "available", label: "Available" },
  { value: "on_trip", label: "On trip" },
  { value: "break", label: "Break" },
  { value: "offline", label: "Offline" },
];

const CAN_PUBLISH_ROLES = new Set(["owner", "admin", "dispatcher"]);

/** Battery-level icon matching batteryColor's own red/amber/green thresholds
 * (utils.ts) -- kept local to this page since nothing else needs it yet. */
function BatteryIcon({ pct }: { pct: number }) {
  const className = "h-3.5 w-3.5";
  if (pct < 20) return <BatteryWarning className={className} />;
  if (pct < 40) return <BatteryLow className={className} />;
  if (pct < 75) return <BatteryMedium className={className} />;
  return <BatteryFull className={className} />;
}

export default function LiveMapPage() {
  const { user } = useAuth();
  const { positions, connectionState } = useFleetLiveSocket();

  const [publishOpen, setPublishOpen] = useState(false);

  // --- table filters (debounced rego search) -----------------------------
  const [regoInput, setRegoInput] = useState("");
  const [rego, setRego] = useState("");
  const [liveStatus, setLiveStatus] = useState("");
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => setRego(regoInput.trim()), 300);
    return () => clearTimeout(t);
  }, [regoInput]);

  useEffect(() => {
    setPage(0);
  }, [rego, liveStatus]);

  // --- data: unfiltered snapshot used to plot the map & resolve duress pins
  const vehiclesMapQuery = useQuery({
    queryKey: ["live-map", "vehicles", "map"],
    queryFn: async () => {
      const res = await apiClient.get<Page<VehicleLiveRead>>("/v1/vehicles", {
        params: { skip: 0, limit: MAP_FETCH_LIMIT },
      });
      return res.data;
    },
    refetchInterval: 20000,
  });

  // --- data: filtered/paginated vehicle list for the table ----------------
  const vehiclesTableQuery = useQuery({
    queryKey: ["live-map", "vehicles", "table", { rego, liveStatus, page }],
    queryFn: async () => {
      const res = await apiClient.get<Page<VehicleLiveRead>>("/v1/vehicles", {
        params: {
          skip: page * TABLE_PAGE_SIZE,
          limit: TABLE_PAGE_SIZE,
          rego: rego || undefined,
          live_status: liveStatus || undefined,
        },
      });
      return res.data;
    },
    placeholderData: keepPreviousData,
  });

  // --- data: open duress events, polled --------------------------------
  const duressQuery = useQuery({
    queryKey: ["live-map", "duress"],
    queryFn: async () => {
      const res = await apiClient.get<DuressEventListResponse>("/v1/duress", {
        params: { open_only: true, limit: 50 },
      });
      return res.data;
    },
    refetchInterval: 5000,
  });

  const mapVehicles = useMemo(
    () => (vehiclesMapQuery.data?.items ?? []).map((v) => mergeLivePosition(v, positions)),
    [vehiclesMapQuery.data, positions],
  );

  const tableVehicles = useMemo(
    () => (vehiclesTableQuery.data?.items ?? []).map((v) => mergeLivePosition(v, positions)),
    [vehiclesTableQuery.data, positions],
  );

  const duressEvents: DuressEventRead[] = duressQuery.data?.items ?? [];

  const duressVehicleIds = useMemo(() => new Set(duressEvents.map((e) => e.vehicle_id)), [duressEvents]);

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of mapVehicles) map.set(v.id, v.rego);
    return map;
  }, [mapVehicles]);

  const total = vehiclesTableQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / TABLE_PAGE_SIZE));

  const columns: TableColumn<VehicleLiveRead>[] = [
    {
      key: "rego",
      header: "Rego",
      sortable: true,
      render: (v) => (
        <span className="flex items-center gap-2 font-medium">
          {v.rego}
          {duressVehicleIds.has(v.id) && (
            <span title="Active duress event">
              <AlertTriangle className="h-3.5 w-3.5 text-destructive" />
            </span>
          )}
        </span>
      ),
    },
    { key: "vehicle_class", header: "Class", sortable: true },
    {
      key: "live_status",
      header: "Status",
      sortable: true,
      render: (v) => <Badge variant={statusBadgeVariant(v.live_status)}>{v.live_status}</Badge>,
    },
    {
      key: "position",
      header: "Last position",
      render: (v) => <span className="font-mono text-xs">{formatLatLng(v.lat, v.lng)}</span>,
    },
    {
      key: "position_updated_at",
      header: "Updated",
      sortable: true,
      sortAccessor: (v) => v.position_updated_at ?? "",
      render: (v) => (
        <span
          className={isStale(v.position_updated_at) ? "font-medium text-destructive" : "text-muted-foreground"}
          title={isStale(v.position_updated_at) ? "No update in over 90s -- may have lost connectivity" : undefined}
        >
          {formatRelativeTime(v.position_updated_at)}
        </span>
      ),
    },
    {
      key: "position_source",
      header: "Source",
      render: (v) => <Badge variant="outline">{v.position_source}</Badge>,
    },
    {
      key: "battery",
      header: "Battery",
      sortable: true,
      sortAccessor: (v) => v.battery ?? -1,
      render: (v) =>
        v.battery == null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span className="flex items-center gap-1.5" style={{ color: batteryColor(v.battery) }}>
            <BatteryIcon pct={v.battery} />
            {v.battery}%
          </span>
        ),
    },
    {
      key: "network",
      header: "Connectivity",
      render: (v) =>
        v.network == null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <Badge variant={networkBadgeVariant(v.network)} className="gap-1">
            {v.network === "offline" && <WifiOff className="h-3 w-3" />}
            {v.network}
          </Badge>
        ),
    },
  ];

  const duressColumns: TableColumn<DuressEventRead>[] = [
    { key: "vehicle_id", header: "Vehicle", render: (e) => vehicleRegoById.get(e.vehicle_id) ?? e.vehicle_id },
    { key: "driver_id", header: "Driver" },
    { key: "trigger", header: "Trigger", render: (e) => <Badge variant="outline">{e.trigger}</Badge> },
    { key: "status", header: "Status", render: (e) => <Badge variant="destructive">{e.status}</Badge> },
    {
      key: "opened_at",
      header: "Opened",
      render: (e) => <span className="text-muted-foreground">{formatRelativeTime(e.opened_at)}</span>,
    },
    {
      key: "actions",
      header: "",
      render: (e) => (
        <Link to={`/duress?event=${e.id}`}>
          <Button size="sm" variant="destructive">
            View
          </Button>
        </Link>
      ),
    },
  ];

  const connectionBadge = (() => {
    switch (connectionState) {
      case "open":
        return (
          <Badge variant="success" className="gap-1">
            <Radio className="h-3 w-3" /> Live
          </Badge>
        );
      case "connecting":
        return (
          <Badge variant="outline" className="gap-1">
            <RadioTower className="h-3 w-3" /> Connecting…
          </Badge>
        );
      default:
        return (
          <Badge variant="destructive" className="gap-1">
            <RadioTower className="h-3 w-3" /> Reconnecting…
          </Badge>
        );
    }
  })();

  return (
    <div>
      <PageHeader
        title="Live Map"
        description="Real-time vehicle positions and active duress events across the fleet."
        actions={
          <>
            {connectionBadge}
            {user && CAN_PUBLISH_ROLES.has(user.role) && (
              <Button onClick={() => setPublishOpen(true)}>Publish position</Button>
            )}
          </>
        }
      />

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Fleet map</CardTitle>
          <CardDescription>
            Vehicles plotted by last-known lat/lng, colored by status. Vehicles with an active duress
            event are shown oversized in red — click one to open its event.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {vehiclesMapQuery.isLoading ? (
            <div className="flex h-[460px] items-center justify-center text-sm text-muted-foreground">
              Loading fleet positions…
            </div>
          ) : vehiclesMapQuery.isError ? (
            <div className="flex h-[460px] items-center justify-center text-sm text-destructive">
              Failed to load vehicle positions.
            </div>
          ) : (
            <FleetMapCanvas vehicles={mapVehicles} duressEvents={duressEvents} />
          )}
          <div className="mt-4 flex flex-wrap gap-4 text-xs text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: "var(--success)" }} /> Available
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: "var(--brand-accent)" }} /> On
              trip / hired
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: "var(--muted-foreground)" }} /> Off
              / offline
            </span>
            <span className="flex items-center gap-1.5">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: "var(--destructive)" }} /> Duress
            </span>
          </div>
        </CardContent>
      </Card>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Active duress events</CardTitle>
          <CardDescription>Polled every 5s from GET /v1/duress?open_only=true.</CardDescription>
        </CardHeader>
        <CardContent>
          <Table
            columns={duressColumns}
            data={duressEvents}
            rowKey={(e) => e.id}
            isLoading={duressQuery.isLoading}
            emptyState={duressQuery.isError ? "Failed to load duress events." : "No active duress events."}
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Vehicles</CardTitle>
          <CardDescription>{total} vehicle{total === 1 ? "" : "s"} in fleet.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="mb-4 flex flex-wrap gap-3">
            <Input
              placeholder="Search rego…"
              value={regoInput}
              onChange={(e) => setRegoInput(e.target.value)}
              className="max-w-xs"
            />
            <Select
              options={LIVE_STATUS_OPTIONS}
              placeholder="All statuses"
              value={liveStatus}
              onChange={(e) => setLiveStatus(e.target.value)}
              className="max-w-xs"
            />
          </div>

          <Table
            columns={columns}
            data={tableVehicles}
            rowKey={(v) => v.id}
            isLoading={vehiclesTableQuery.isLoading}
            emptyState={vehiclesTableQuery.isError ? "Failed to load vehicles." : "No vehicles match these filters."}
          />

          {pageCount > 1 && (
            <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
              <span>
                Page {page + 1} of {pageCount}
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
        </CardContent>
      </Card>

      <PublishPositionModal open={publishOpen} onClose={() => setPublishOpen(false)} vehicles={mapVehicles} />
    </div>
  );
}
