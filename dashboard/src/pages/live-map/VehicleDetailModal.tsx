import { type ReactNode, useEffect, useMemo, useState } from "react";
import { Loader2 } from "lucide-react";
import { Badge, Modal, Table, type TableColumn } from "@/components/ui";
import type { PositionHistoryItem, VehicleShiftHistoryItem } from "./types";
import type { VehicleMapState } from "./FleetMapCanvas";
import {
  batteryColor,
  formatLatLng,
  formatRelativeTime,
  formatSpeed,
  idleLabel,
  isStale,
  networkBadgeVariant,
  staleLabel,
  statusBadgeVariant,
} from "./utils";
import { useDriverDetailQuery, useVehicleDetailQuery } from "./useVehicleDetail";
import { useVehicleShiftHistoryQuery } from "./useVehicleShiftHistory";
import { usePositionHistoryQuery } from "./useVehiclePositionHistory";

export interface VehicleDetailModalProps {
  vehicleId: string | null;
  open: boolean;
  onClose: () => void;
  /** This vehicle's idle/geofence state, as computed by LiveMapPage from its
   * own position-history buffer + fetched geofence list (see
   * FleetMapCanvas.VehicleMapState's own doc for why that computation lives
   * there and is threaded down rather than redone here). Null when this
   * modal is opened for a vehicle outside the map's own up-to-100-vehicle
   * snapshot (e.g. a table row beyond that fetch limit) -- the idle/geofence
   * section below is simply omitted in that case rather than guessing, same
   * honest-absence convention as every other optional field in this domain.
   */
  mapState?: VehicleMapState | null;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="font-medium text-foreground">{children}</div>
    </div>
  );
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function formatDuration(startAt: string, endAt: string | null): string {
  const start = new Date(startAt).getTime();
  if (Number.isNaN(start)) return "—";
  const end = endAt ? new Date(endAt).getTime() : Date.now();
  const minutes = Math.max(0, Math.round((end - start) / 60_000));
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return `${hours}h ${mins}m${endAt ? "" : " (ongoing)"}`;
}

const SHIFT_HISTORY_COLUMNS: TableColumn<VehicleShiftHistoryItem>[] = [
  {
    key: "driver_name",
    header: "Driver",
    render: (row) => row.driver_name ?? row.driver_id,
  },
  {
    key: "start_at",
    header: "Start",
    sortable: true,
    sortAccessor: (row) => new Date(row.start_at).getTime(),
    render: (row) => formatDateTime(row.start_at),
  },
  {
    key: "end_at",
    header: "End",
    render: (row) => (row.end_at ? formatDateTime(row.end_at) : "—"),
  },
  {
    key: "duration",
    header: "Duration",
    render: (row) => formatDuration(row.start_at, row.end_at),
  },
];

const REPLAY_MAP_WIDTH = 320;
const REPLAY_MAP_HEIGHT = 160;
const REPLAY_MAP_PADDING = 14;

/**
 * Tiny static SVG projection of a vehicle's position-history path, with a
 * marker at the currently-scrubbed index. Deliberately NOT wired into the
 * live FleetMapCanvas/Mapbox instance: that map is owned and rendered by
 * LiveMapPage, not by this modal, and threading a "replay position" prop
 * down through FleetMapCanvasProps and its Mapbox-vs-plain-SVG marker-sync
 * effects (see FleetMapCanvas.tsx) just to place one extra read-only dot
 * would mean restructuring a component three prior agents in this same
 * track already reworked substantially, for a payoff (one dot on the real
 * map) this much simpler self-contained view already delivers. Uses the
 * same plain lat/lng-to-local-bounding-box projection as FleetMapCanvas's
 * own PlainCanvasMap fallback, computed independently here since this is a
 * much smaller, separate data set (one vehicle's history, not the whole
 * fleet) that this modal owns on its own.
 */
function ReplayMiniMap({ items, index }: { items: PositionHistoryItem[]; index: number }) {
  const bounds = useMemo(() => {
    let minLat = Infinity;
    let maxLat = -Infinity;
    let minLng = Infinity;
    let maxLng = -Infinity;
    for (const p of items) {
      minLat = Math.min(minLat, p.lat);
      maxLat = Math.max(maxLat, p.lat);
      minLng = Math.min(minLng, p.lng);
      maxLng = Math.max(maxLng, p.lng);
    }
    // Pad degenerate ranges (a vehicle that barely moved) so the path doesn't
    // collapse onto the viewport edge -- same 15% padding as PlainCanvasMap's
    // own bounds calc in FleetMapCanvas.tsx.
    const latSpan = maxLat - minLat || 0.001;
    const lngSpan = maxLng - minLng || 0.001;
    return {
      minLat: minLat - latSpan * 0.15,
      maxLat: maxLat + latSpan * 0.15,
      minLng: minLng - lngSpan * 0.15,
      maxLng: maxLng + lngSpan * 0.15,
    };
  }, [items]);

  function project(lat: number, lng: number): [number, number] {
    const x =
      REPLAY_MAP_PADDING +
      ((lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)) * (REPLAY_MAP_WIDTH - REPLAY_MAP_PADDING * 2);
    const y =
      REPLAY_MAP_PADDING +
      ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat)) * (REPLAY_MAP_HEIGHT - REPLAY_MAP_PADDING * 2);
    return [x, y];
  }

  const pathPoints = items.map((p) => project(p.lat, p.lng).join(",")).join(" ");
  const current = items[index] ?? items[items.length - 1];
  const [cx, cy] = project(current.lat, current.lng);

  return (
    <svg
      viewBox={`0 0 ${REPLAY_MAP_WIDTH} ${REPLAY_MAP_HEIGHT}`}
      className="w-full rounded-md border border-border bg-muted/40"
      role="img"
      aria-label="Position history path"
    >
      <polyline points={pathPoints} fill="none" stroke="var(--muted-foreground)" strokeWidth={1.5} strokeOpacity={0.6} />
      <circle cx={cx} cy={cy} r={5} fill="var(--brand-accent)" stroke="var(--card)" strokeWidth={1.5} />
    </svg>
  );
}

/**
 * Vehicle drill-down opened from either the Vehicles table (row click) or a
 * map marker (click on a non-duress vehicle -- a duress-active vehicle's
 * marker keeps deep-linking straight to its event instead, see
 * FleetMapCanvas). Fetches fresh via `GET /v1/vehicles/{id}` rather than
 * reusing whatever row triggered it, so the panel never shows a stale value
 * from a paused table/map poll.
 */
export function VehicleDetailModal({ vehicleId, open, onClose, mapState }: VehicleDetailModalProps) {
  const vehicleQuery = useVehicleDetailQuery(vehicleId);
  const vehicle = vehicleQuery.data;

  // Only fetched for the phone number -- id/name/on-shift-since already come
  // straight off VehicleLiveRead.current_driver_* below, no second lookup
  // needed for those.
  const driverQuery = useDriverDetailQuery(open ? vehicle?.current_driver_id ?? null : null);
  const driver = driverQuery.data;

  const shiftHistoryQuery = useVehicleShiftHistoryQuery(open ? vehicleId : null);

  const positionHistoryQuery = usePositionHistoryQuery(open ? vehicleId : null);
  const historyItems = positionHistoryQuery.data?.items;

  // Defaults the scrubber to the most recent point whenever a new history
  // array arrives (first load for this vehicle, or a refetch) -- a
  // dispatcher opening Replay wants "where is it now / where has it just
  // been", not the oldest point in the retained window. Runs in an effect
  // (after render) rather than a lazy useState initializer since
  // `historyItems` only exists once the query resolves.
  const [scrubIndex, setScrubIndex] = useState(0);
  useEffect(() => {
    if (historyItems && historyItems.length > 0) {
      setScrubIndex(historyItems.length - 1);
    }
  }, [historyItems]);

  const scrubbedPoint = historyItems?.[scrubIndex];

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={vehicle ? `Vehicle ${vehicle.rego}` : "Vehicle detail"}
      description={vehicleId ?? undefined}
      className="max-w-lg"
    >
      {vehicleQuery.isLoading && (
        <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading vehicle…
        </div>
      )}

      {vehicleQuery.isError && (
        <p className="py-4 text-sm text-destructive">Failed to load this vehicle.</p>
      )}

      {vehicle && (
        <div className="flex flex-col gap-5">
          <div className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
            <Field label="Rego">{vehicle.rego}</Field>
            <Field label="Class">{vehicle.vehicle_class}</Field>
            <Field label="Vehicle status">
              <Badge variant="outline">{vehicle.vehicle_status}</Badge>
            </Field>
            <Field label="Live status">
              <Badge variant={statusBadgeVariant(vehicle.live_status)}>{vehicle.live_status}</Badge>
            </Field>
            <Field label="Position">
              <span className="font-mono text-xs">{formatLatLng(vehicle.lat, vehicle.lng)}</span>
            </Field>
            <Field label="Position source">
              <Badge variant="outline">{vehicle.position_source}</Badge>
            </Field>
            <Field label="Position updated">
              <span
                className={isStale(vehicle.position_updated_at) ? "text-destructive" : undefined}
                title={
                  isStale(vehicle.position_updated_at)
                    ? "No update in over 15s -- may have lost connectivity"
                    : undefined
                }
              >
                {formatRelativeTime(vehicle.position_updated_at)}
              </span>
            </Field>
            <Field label="Current trip">
              <span className="font-mono text-xs">{vehicle.current_trip_id ?? "—"}</span>
            </Field>
            <Field label="Speed">{formatSpeed(vehicle.speed_kmh)}</Field>
            <Field label="Heading">
              {vehicle.heading == null ? (
                <span className="text-muted-foreground">—</span>
              ) : (
                `${Math.round(vehicle.heading)}°`
              )}
            </Field>
          </div>

          {mapState &&
            (staleLabel(vehicle.position_updated_at) ||
              idleLabel(mapState.idleInfo) ||
              mapState.insideGeofences.length > 0) && (
              <div className="rounded-lg border border-border p-3">
                <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Activity
                </p>
                <div className="flex flex-col gap-1.5 text-sm">
                  {staleLabel(vehicle.position_updated_at) && (
                    <p className="text-muted-foreground">{staleLabel(vehicle.position_updated_at)}</p>
                  )}
                  {idleLabel(mapState.idleInfo) && (
                    <p style={{ color: "var(--warning, #d97706)" }}>{idleLabel(mapState.idleInfo)}</p>
                  )}
                  {mapState.insideGeofences.length > 0 && (
                    <p style={{ color: "var(--brand-accent)" }}>
                      Inside {mapState.insideGeofences.map((g) => g.name).join(", ")}
                    </p>
                  )}
                </div>
              </div>
            )}

          <div className="rounded-lg border border-border p-3">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Device
            </p>
            <div className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
              <Field label="Device ID">
                <span className="break-all font-mono text-xs">{vehicle.device_id ?? "—"}</span>
              </Field>
              <Field label="Last seen">{formatRelativeTime(vehicle.device_last_seen_at)}</Field>
              <Field label="Battery">
                {vehicle.battery == null ? (
                  <span className="text-muted-foreground">—</span>
                ) : (
                  <span style={{ color: batteryColor(vehicle.battery) }}>{vehicle.battery}%</span>
                )}
              </Field>
              <Field label="Network">
                {vehicle.network == null ? (
                  <span className="text-muted-foreground">—</span>
                ) : (
                  <Badge variant={networkBadgeVariant(vehicle.network)}>{vehicle.network}</Badge>
                )}
              </Field>
            </div>
          </div>

          <div className="rounded-lg border border-border p-3">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Current driver
            </p>
            {!vehicle.current_driver_id ? (
              <p className="text-sm text-muted-foreground">No driver currently on shift in this vehicle.</p>
            ) : (
              <div className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3">
                <Field label="Name">{vehicle.current_driver_name ?? vehicle.current_driver_id}</Field>
                <Field label="Phone">
                  {driverQuery.isLoading ? (
                    <span className="text-muted-foreground">Loading…</span>
                  ) : (
                    driver?.phone ?? "—"
                  )}
                </Field>
                <Field label="Shift ID">
                  <span className="break-all font-mono text-xs">{vehicle.current_shift_id ?? "—"}</span>
                </Field>
                <Field label="On since">{formatRelativeTime(vehicle.current_shift_start_at)}</Field>
              </div>
            )}
          </div>

          <div className="rounded-lg border border-border p-3">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Shift history
            </p>
            <Table
              columns={SHIFT_HISTORY_COLUMNS}
              data={shiftHistoryQuery.data?.items ?? []}
              rowKey={(row) => row.shift_id}
              pageSize={5}
              isLoading={shiftHistoryQuery.isLoading}
              emptyState="No past shifts recorded for this vehicle."
            />
          </div>

          {positionHistoryQuery.isLoading && (
            <div className="flex items-center gap-2 py-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> Loading position history…
            </div>
          )}
          {positionHistoryQuery.isError && (
            <p className="text-sm text-destructive">Failed to load position history.</p>
          )}

          {/* Replay -- only shown once history has actually loaded AND has at
              least 2 points: a brand-new vehicle (or one whose device just
              started publishing) has nothing to scrub through yet, so a
              1-point or empty history renders no scrubber at all rather than
              a broken/empty one. */}
          {historyItems && historyItems.length >= 2 && scrubbedPoint && (
            <div className="rounded-lg border border-border p-3">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Replay
              </p>
              <p className="mb-2 text-xs text-muted-foreground">
                {historyItems.length} recorded positions, last 72h (default retention, not a fixed
                policy).
              </p>
              <ReplayMiniMap items={historyItems} index={scrubIndex} />
              <input
                type="range"
                min={0}
                max={historyItems.length - 1}
                step={1}
                value={scrubIndex}
                onChange={(e) => setScrubIndex(Number(e.target.value))}
                className="mt-3 w-full"
                aria-label="Scrub through this vehicle's recorded position history"
              />
              <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-4">
                <Field label="Recorded at">{formatDateTime(scrubbedPoint.recorded_at)}</Field>
                <Field label="Position">
                  <span className="font-mono text-xs">{formatLatLng(scrubbedPoint.lat, scrubbedPoint.lng)}</span>
                </Field>
                <Field label="Speed">{formatSpeed(scrubbedPoint.speed_kmh)}</Field>
                <Field label="Heading">
                  {scrubbedPoint.heading == null ? (
                    <span className="text-muted-foreground">—</span>
                  ) : (
                    `${Math.round(scrubbedPoint.heading)}°`
                  )}
                </Field>
              </div>
            </div>
          )}

          {/* Driving signals -- plain telematics counts, shown alongside the
              exact threshold the backend used to compute them (never
              hardcoded/guessed here -- see VehiclePositionHistoryResponse's
              own doc in types.ts) and captioned as informational only. This
              is real GPS data about a real person driving; nothing here is a
              certified safety score or a disciplinary rating, and the
              caption below says so explicitly rather than letting the raw
              counts imply more authority than they carry. */}
          {positionHistoryQuery.data && (
            <div className="rounded-lg border border-border p-3">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Driving signals
              </p>
              <div className="grid grid-cols-1 gap-x-4 gap-y-3 text-sm sm:grid-cols-2">
                <Field label="Harsh braking">
                  {positionHistoryQuery.data.harsh_brake_events} event
                  {positionHistoryQuery.data.harsh_brake_events === 1 ? "" : "s"} (delta greater than{" "}
                  {positionHistoryQuery.data.threshold_kmh_per_s} km/h/s)
                </Field>
                <Field label="Rapid acceleration">
                  {positionHistoryQuery.data.rapid_accel_events} event
                  {positionHistoryQuery.data.rapid_accel_events === 1 ? "" : "s"} (delta greater than{" "}
                  {positionHistoryQuery.data.threshold_kmh_per_s} km/h/s)
                </Field>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Based on a standard telematics threshold, not a certified safety rating.
              </p>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
