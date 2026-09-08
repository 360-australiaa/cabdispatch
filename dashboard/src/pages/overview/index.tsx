import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Activity, AlertTriangle, Radio, RadioTower, TabletSmartphone } from "lucide-react";
import apiClient from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { formatAud, formatTimeSeconds } from "@/lib/format";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, EmptyState, PageHeader, Table, type TableColumn } from "@/components/ui";
import { useFleetLiveSocket } from "@/hooks/useLiveMap";
import { useRevenueReportQuery } from "@/hooks/useReports";
import type { TripListResponse } from "@/hooks/useTrips";
import { useDevices } from "@/pages/fleet/api";
import { FleetMapCanvas, type VehicleMapState } from "@/pages/live-map/FleetMapCanvas";
import type { DuressEventListResponse, Page, VehicleLiveRead } from "@/pages/live-map/types";
import {
  batteryColor,
  formatRelativeTime,
  formatSpeed,
  isBusyStatus,
  isStale,
  mergeLivePosition,
  statusBadgeVariant,
} from "@/pages/live-map/utils";
import { isDeviceOffline, type ActivityEvent, type ActivityTone, type FeedSnapshot } from "./activityFeed";
import { useActivityFeed } from "./useActivityFeed";

/** Server-side caps: `GET /v1/vehicles` le=100, `GET /v1/trips` le=200. */
const VEHICLE_FETCH_LIMIT = 100;
const TRIP_FETCH_LIMIT = 200;

/** `YYYY-MM-DD` of the viewer's local calendar day. The tenant row carries no
 * timezone (see lib/format.ts's own note), so "today" is the browser's day --
 * the same convention every date column in the app already renders in. */
function localDateKey(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** Re-renders the caller every `ms` so relative timestamps stay honest. A
 * state tick, not an animation. */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/**
 * Owner home page: the whole operation on one screen, every number sourced
 * from a real endpoint or the live socket, nothing placeholder. Section
 * captions say where each figure comes from so the owner can trust -- or
 * check -- any of them.
 */
export default function OverviewPage() {
  const navigate = useNavigate();
  const { positions, connectionState } = useFleetLiveSocket();
  const now = useNow(10_000);

  // --- today's bounds (local day) --------------------------------------
  const todayKey = localDateKey(new Date(now));
  const today = useMemo(() => {
    const [y, m, d] = todayKey.split("-").map(Number);
    const start = new Date(y, m - 1, d);
    const end = new Date(start.getTime() + 24 * 60 * 60 * 1000);
    return { key: todayKey, startIso: start.toISOString(), endIso: end.toISOString() };
  }, [todayKey]);

  // --- data --------------------------------------------------------------
  const vehiclesQuery = useQuery({
    queryKey: ["overview", "vehicles"],
    queryFn: async () => {
      const res = await apiClient.get<Page<VehicleLiveRead>>("/v1/vehicles", {
        params: { skip: 0, limit: VEHICLE_FETCH_LIMIT },
      });
      return res.data;
    },
    ...pollingQueryOptions(POLL.SUPPORTING),
  });

  const duressQuery = useQuery({
    queryKey: ["overview", "duress"],
    queryFn: async () => {
      const res = await apiClient.get<DuressEventListResponse>("/v1/duress", {
        params: { open_only: true, limit: 50 },
      });
      return res.data;
    },
    ...pollingQueryOptions(POLL.INCIDENT_LIST),
  });

  const tripsQuery = useQuery({
    queryKey: ["overview", "trips", today.key],
    queryFn: async () => {
      const res = await apiClient.get<TripListResponse>("/v1/trips", {
        params: { start_from: today.startIso, start_to: today.endIso, skip: 0, limit: TRIP_FETCH_LIMIT },
      });
      return res.data;
    },
    ...pollingQueryOptions(POLL.SUPPORTING),
  });

  const revenueQuery = useRevenueReportQuery({ from: today.key, to: today.key, group_by: "day" });
  const devicesQuery = useDevices(0, {}, 100);

  // --- derived: fleet ------------------------------------------------------
  const vehicles = useMemo(
    () => (vehiclesQuery.data?.items ?? []).map((v) => mergeLivePosition(v, positions)),
    [vehiclesQuery.data, positions],
  );

  const fleet = useMemo(() => {
    let onTrip = 0;
    let available = 0;
    let onBreak = 0;
    let offline = 0;
    let stale = 0;
    for (const v of vehicles) {
      const s = v.live_status.toLowerCase();
      if (s === "available") available++;
      else if (s === "break") onBreak++;
      else if (s === "offline") offline++;
      else if (isBusyStatus(s)) onTrip++;
      if (s !== "offline" && v.position_updated_at && isStale(v.position_updated_at)) stale++;
    }
    return { onTrip, available, onBreak, offline, stale, total: vehicles.length };
  }, [vehicles]);

  // --- derived: money ------------------------------------------------------
  const trips = tripsQuery.data?.items;
  const closedToday = useMemo(() => (trips ?? []).filter((t) => t.status === "closed"), [trips]);
  const openToday = (trips ?? []).length - closedToday.length;
  const revenueFromReport = revenueQuery.data?.totals.gross_revenue ?? null;
  const revenueFallback = useMemo(
    () => closedToday.reduce((sum, t) => sum + (Number(t.total) || 0), 0).toFixed(2),
    [closedToday],
  );
  const revenueToday = revenueFromReport ?? (tripsQuery.data ? revenueFallback : null);
  const revenueSource = revenueFromReport != null ? "report" : tripsQuery.data ? "trips" : null;

  // --- derived: tablets ----------------------------------------------------
  const devices = devicesQuery.data?.items;
  const tablets = useMemo(() => {
    let lowBattery = 0;
    let offline = 0;
    let forceUpdate = 0;
    const flagged = new Set<string>();
    for (const d of devices ?? []) {
      if (d.revoked_at) continue;
      if (d.battery != null && d.battery < 20) {
        lowBattery++;
        flagged.add(d.id);
      }
      if (isDeviceOffline(d, now)) {
        offline++;
        flagged.add(d.id);
      }
      if (d.force_update_pending) {
        forceUpdate++;
        flagged.add(d.id);
      }
    }
    return { lowBattery, offline, forceUpdate, count: flagged.size };
  }, [devices, now]);

  const openDuress = useMemo(() => duressQuery.data?.items ?? [], [duressQuery.data]);

  // --- activity feed ---------------------------------------------------------
  const loadedVehicles = vehiclesQuery.data ? vehicles : null;
  const loadedDuress = duressQuery.data ? openDuress : null;
  const snapshot: FeedSnapshot = useMemo(
    () => ({
      vehicles: loadedVehicles,
      trips: trips ?? null,
      duress: loadedDuress,
      devices: devices ?? null,
      socket: connectionState,
    }),
    [loadedVehicles, trips, loadedDuress, devices, connectionState],
  );
  const feed = useActivityFeed(snapshot);

  // --- map -------------------------------------------------------------------
  // FleetMapCanvas wants the live map's enriched row. Idle detection and
  // geofence overlay are the Live Map's own features; here they are simply
  // absent (not idle, no geofences), never guessed.
  const mapVehicles: VehicleMapState[] = useMemo(
    () => vehicles.map((v) => ({ ...v, idleInfo: { idle: false, idleSinceMs: null }, insideGeofences: [] })),
    [vehicles],
  );

  const lastRefreshAt = Math.max(
    vehiclesQuery.dataUpdatedAt,
    duressQuery.dataUpdatedAt,
    tripsQuery.dataUpdatedAt,
    revenueQuery.dataUpdatedAt,
    devicesQuery.dataUpdatedAt,
  );

  const fleetColumns: TableColumn<VehicleLiveRead>[] = [
    { key: "rego", header: "Rego", sortable: true, render: (v) => <span className="font-medium">{v.rego}</span> },
    {
      key: "driver",
      header: "Driver",
      sortable: true,
      sortAccessor: (v) => v.current_driver_name ?? "",
      render: (v) => v.current_driver_name ?? <span className="text-muted-foreground">—</span>,
    },
    {
      key: "live_status",
      header: "Status",
      sortable: true,
      render: (v) => <Badge variant={statusBadgeVariant(v.live_status)}>{v.live_status}</Badge>,
    },
    {
      key: "speed",
      header: "Speed",
      sortable: true,
      sortAccessor: (v) => v.speed_kmh ?? -1,
      render: (v) => <span className="text-muted-foreground">{formatSpeed(v.speed_kmh)}</span>,
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
          <span style={{ color: batteryColor(v.battery) }}>{v.battery}%</span>
        ),
    },
    {
      key: "position_updated_at",
      header: "Position",
      sortable: true,
      sortAccessor: (v) => v.position_updated_at ?? "",
      render: (v) => (
        <span className={isStale(v.position_updated_at) ? "font-medium text-destructive" : "text-muted-foreground"}>
          {formatRelativeTime(v.position_updated_at)}
        </span>
      ),
    },
  ];

  const live = connectionState === "open";

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Overview"
        description="Your fleet right now: live positions, today's trips and revenue, and anything that needs attention."
        actions={
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 font-medium",
                live ? "border-success/40 text-success" : "border-warning/40 text-warning",
              )}
              role="status"
              aria-live="polite"
              aria-label="Live feed status"
            >
              {live ? <RadioTower className="h-3.5 w-3.5" /> : <Radio className="h-3.5 w-3.5" />}
              {live ? "Live" : "Reconnecting"}
            </span>
            <span>
              Last refresh {lastRefreshAt > 0 ? formatTimeSeconds(new Date(lastRefreshAt).toISOString()) : "—"}
            </span>
          </div>
        }
      />

      {/* KPI strip */}
      <section aria-label="Key figures" className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-5">
        <Kpi label="On trip" value={vehiclesQuery.data ? fleet.onTrip : null} to="/live-map" tone="accent" />
        <Kpi label="Available" value={vehiclesQuery.data ? fleet.available : null} to="/live-map" tone="success" />
        <Kpi label="On break" value={vehiclesQuery.data ? fleet.onBreak : null} to="/live-map" />
        <Kpi label="Offline" value={vehiclesQuery.data ? fleet.offline : null} to="/live-map" />
        <Kpi
          label="Stale signal"
          value={vehiclesQuery.data ? fleet.stale : null}
          to="/live-map"
          tone={fleet.stale > 0 ? "warning" : undefined}
          hint="Not offline, but no position in 15s"
        />
        <Kpi
          label="Trips today"
          value={tripsQuery.data ? tripsQuery.data.total : null}
          to="/trips"
          hint={tripsQuery.data ? `${openToday} open · ${closedToday.length} closed` : undefined}
        />
        <Kpi
          label="Revenue today"
          value={revenueToday != null ? formatAud(revenueToday) : null}
          to="/compliance"
          hint={
            revenueSource === "report"
              ? "Revenue report, gross"
              : revenueSource === "trips"
                ? "Sum of today's closed trips (report unavailable)"
                : undefined
          }
        />
        <Kpi
          label="Open duress"
          value={duressQuery.data ? openDuress.length : null}
          to="/duress"
          tone={openDuress.length > 0 ? "destructive" : undefined}
          icon={openDuress.length > 0 ? AlertTriangle : undefined}
        />
        <Kpi
          label="Tablets needing attention"
          value={devices ? tablets.count : null}
          to="/fleet"
          tone={tablets.count > 0 ? "warning" : undefined}
          icon={TabletSmartphone}
          hint={
            devices
              ? `${tablets.lowBattery} low battery · ${tablets.offline} offline · ${tablets.forceUpdate} update pending`
              : undefined
          }
        />
        <Kpi label="Fleet size" value={vehiclesQuery.data ? fleet.total : null} to="/fleet" hint="Vehicles on the roster" />
      </section>

      {(vehiclesQuery.isError || tripsQuery.isError || duressQuery.isError || devicesQuery.isError) && (
        <p className="text-sm text-destructive" role="alert">
          Some figures could not be loaded; they show “—” above rather than a stale or guessed number.
        </p>
      )}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader>
            <CardTitle>Live map</CardTitle>
            <CardDescription>
              Positions from the live feed over GET /v1/vehicles. Click a vehicle to open it on the Live Map.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {/* Mount the map only once the vehicle list has resolved: FleetMapCanvas
                frames the fleet from the positions it sees at mount and never
                re-fits (an operator's pan must win), so mounting it on an empty
                list opened the owner's home on a view of the whole globe. Same
                gate the Live Map page uses. */}
            {vehiclesQuery.isLoading ? (
              <div className="flex h-[460px] items-center justify-center text-sm text-muted-foreground">
                Loading fleet positions…
              </div>
            ) : vehiclesQuery.isError ? (
              <div className="flex h-[460px] items-center justify-center text-sm text-destructive">
                Failed to load vehicle positions.
              </div>
            ) : (
              <FleetMapCanvas
                vehicles={mapVehicles}
                duressEvents={openDuress}
                geofences={[]}
                onSelectVehicle={(id) => navigate(`/live-map?vehicle=${id}`)}
              />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Activity className="h-4 w-4" aria-hidden="true" />
              Activity
            </CardTitle>
            <CardDescription>
              Changes observed since you opened this page — status changes, trips, duress, tablets, the live feed.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {feed.length === 0 ? (
              <EmptyState
                title="Nothing has changed yet"
                description="Events appear here as vehicles change status, trips open or close, and tablets come and go. Only what actually happens while this page is open is listed."
              />
            ) : (
              <ol aria-label="Activity feed" className="flex max-h-[460px] flex-col gap-2 overflow-y-auto pr-1">
                {feed.map((e) => (
                  <ActivityRow key={e.id} event={e} />
                ))}
              </ol>
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Fleet right now</CardTitle>
          <CardDescription>
            Every vehicle on the roster with its current driver and live state. Click a row to open it on the Live Map.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Table
            label="Fleet right now"
            columns={fleetColumns}
            data={vehicles}
            rowKey={(v) => v.id}
            isLoading={vehiclesQuery.isLoading}
            onRowClick={(v) => navigate(`/live-map?vehicle=${v.id}`)}
            emptyState={
              <EmptyState
                title="No vehicles yet"
                description="Add a vehicle under Fleet & Drivers and pair a tablet to see it here."
              />
            }
          />
        </CardContent>
      </Card>
    </div>
  );
}

function Kpi({
  label,
  value,
  to,
  tone,
  hint,
  icon: Icon,
}: {
  label: string;
  /** `null` while loading or unavailable -- renders an em-dash, never 0. */
  value: number | string | null;
  to: string;
  tone?: "accent" | "success" | "warning" | "destructive";
  hint?: string;
  icon?: typeof AlertTriangle;
}) {
  const toneClass =
    tone === "destructive"
      ? "text-destructive"
      : tone === "warning"
        ? "text-warning"
        : tone === "success"
          ? "text-success"
          : tone === "accent"
            ? "text-brand-accent"
            : "text-foreground";
  return (
    <Link
      to={to}
      className={cn(
        "flex flex-col gap-1 rounded-md border border-border bg-card p-3 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent",
        tone === "destructive" && "border-destructive/60",
      )}
      aria-label={`${label}: ${value ?? "unavailable"}`}
    >
      <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className={cn("flex items-center gap-2 text-2xl font-semibold tabular-nums", toneClass)}>
        {Icon && <Icon className="h-5 w-5" aria-hidden="true" />}
        {value ?? "—"}
      </span>
      {hint && <span className="text-[11px] text-muted-foreground">{hint}</span>}
    </Link>
  );
}

const TONE_DOT: Record<ActivityTone, string> = {
  info: "bg-muted-foreground",
  success: "bg-success",
  warning: "bg-warning",
  destructive: "bg-destructive",
};

function ActivityRow({ event }: { event: ActivityEvent }) {
  const body = (
    <>
      <span className={cn("mt-1.5 h-2 w-2 shrink-0 rounded-full", TONE_DOT[event.tone])} aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className={cn("block text-sm", event.tone === "destructive" && "font-medium text-destructive")}>
          {event.text}
        </span>
        <time dateTime={event.at} className="block text-xs text-muted-foreground">
          {formatRelativeTime(event.at)}
        </time>
      </span>
    </>
  );
  return (
    <li>
      {event.href ? (
        <Link to={event.href} className="flex gap-2 rounded-md px-2 py-1 hover:bg-muted">
          {body}
        </Link>
      ) : (
        <div className="flex gap-2 px-2 py-1">{body}</div>
      )}
    </li>
  );
}
