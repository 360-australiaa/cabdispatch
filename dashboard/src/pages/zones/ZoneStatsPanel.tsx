import { useMemo, useState } from "react";
import { Car, CircleDot, Clock3, Hand, MapPin, PhoneCall, Users, X } from "lucide-react";
import { Button, Card, CardContent, Modal, Select, Spinner } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import {
  useActiveShiftsQuery,
  usePlotVehicleMutation,
  useUnplotVehicleMutation,
  useZoneStatsQuery,
  type PlottedShift,
  type ZonePlotTarget,
  type ZoneStats,
} from "@/hooks/useZones";
import { useDriversLookupQuery, useVehiclesLookupQuery } from "@/hooks/useTrips";
import { extractErrorMessage } from "./format";

/** Owner/admin/dispatcher may plot a vehicle on a driver's behalf from this
 * desk. A `driver` account plots from the tablet, not here. */
const PLOT_ROLES = new Set(["owner", "admin", "dispatcher"]);

/** Live per-zone supply/demand grid -- mirrors the "Statistics" screen a
 * driver already sees on their own meter/app (plotted, vacant, busy, jobs
 * holding, bookings + street hails in the last hour), just viewed from the
 * dispatcher's side across every zone at once. Auto-refetches every 20s via
 * `useZoneStatsQuery` (GET /v1/zones/stats), same live-polling pattern as
 * `useOpenFatigueAlerts`.
 *
 * Each card now also carries the actual plotted roster (from the open
 * shifts' `plotted_zone_id`) with an Unplot per row, and a "Plot vehicle"
 * action for dispatch to plot an on-shift vehicle into the zone
 * (`POST /v1/zones/{id}/plot` / `POST /v1/zones/unplot` -- admin plan §3:
 * "today every zone shows 0 plotted"). */
export function ZoneStatsPanel() {
  const { user } = useAuth();
  const canPlot = !!user && PLOT_ROLES.has(user.role);

  const statsQuery = useZoneStatsQuery();
  const shiftsQuery = useActiveShiftsQuery();
  const driversQuery = useDriversLookupQuery();
  const vehiclesQuery = useVehiclesLookupQuery();
  const unplotMutation = useUnplotVehicleMutation();

  const [plotFor, setPlotFor] = useState<ZoneStats | null>(null);
  const [unplotError, setUnplotError] = useState<string | null>(null);

  const stats = statsQuery.data ?? [];
  const shifts = useMemo(() => shiftsQuery.data ?? [], [shiftsQuery.data]);

  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of driversQuery.data ?? []) map.set(d.id, d.name);
    return map;
  }, [driversQuery.data]);
  const regoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehiclesQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehiclesQuery.data]);

  const plottedByZone = useMemo(() => {
    const map = new Map<string, PlottedShift[]>();
    for (const shift of shifts) {
      if (!shift.plotted_zone_id) continue;
      const list = map.get(shift.plotted_zone_id) ?? [];
      list.push(shift);
      map.set(shift.plotted_zone_id, list);
    }
    return map;
  }, [shifts]);

  function shiftLabel(shift: PlottedShift): string {
    const rego = regoById.get(shift.vehicle_id) ?? shift.vehicle_id.slice(0, 8);
    const driver = driverNameById.get(shift.driver_id) ?? shift.driver_id.slice(0, 8);
    return `${rego} · ${driver}`;
  }

  async function handleUnplot(shift: PlottedShift) {
    setUnplotError(null);
    const target: ZonePlotTarget = { shift_id: shift.id, driver_id: shift.driver_id, vehicle_id: shift.vehicle_id };
    try {
      await unplotMutation.mutateAsync(target);
    } catch (err) {
      setUnplotError(extractErrorMessage(err));
    }
  }

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Live supply and demand per zone. Refreshes automatically every 20 seconds.
        </p>
        {statsQuery.isFetching && !statsQuery.isLoading && (
          <span className="text-xs text-muted-foreground">Refreshing...</span>
        )}
      </div>

      {statsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load zone stats. Check the backend connection and try again.
        </p>
      )}
      {shiftsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load open shifts, so the plotted roster per zone is unavailable.
        </p>
      )}
      {unplotError && <p className="mb-3 text-sm text-destructive">Could not unplot: {unplotError}</p>}

      {statsQuery.isLoading ? (
        <p className="py-6 text-center text-sm text-muted-foreground">
          <Spinner size="sm" label="Loading zone stats" />
        </p>
      ) : stats.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">
          No zones configured yet -- add a zone in the Zones tab to see live stats here.
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {stats.map((row) => {
            const plotted = plottedByZone.get(row.zone_id) ?? [];
            return (
              <Card key={row.zone_id}>
                <CardContent className="pt-4">
                  <div className="mb-3 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className="flex h-7 w-7 items-center justify-center rounded-md bg-brand-lavender font-mono text-sm font-bold text-brand-lavender-foreground">
                        {row.zone_number}
                      </span>
                      <span className="font-medium text-foreground">{row.zone_name}</span>
                    </div>
                    {canPlot && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setPlotFor(row)}
                        aria-label={`Plot vehicle into ${row.zone_name}`}
                      >
                        <MapPin className="h-3.5 w-3.5" /> Plot vehicle
                      </Button>
                    )}
                  </div>

                  <div className="grid grid-cols-3 gap-2 text-center">
                    <StatCell icon={Users} label="Plotted" value={row.plotted_vehicles} />
                    <StatCell icon={CircleDot} label="Vacant" value={row.vacant_vehicles} tone="success" />
                    <StatCell icon={Car} label="Busy" value={row.busy_vehicles} tone="destructive" />
                    <StatCell icon={Clock3} label="Jobs holding" value={row.jobs_holding} />
                    <StatCell icon={PhoneCall} label="Bookings/hr" value={row.bookings_last_hour} />
                    <StatCell icon={Hand} label="Street hails/hr" value={row.street_hails_last_hour} />
                  </div>

                  {plotted.length > 0 && (
                    <ul className="mt-3 flex flex-col gap-1" aria-label={`Vehicles plotted into ${row.zone_name}`}>
                      {plotted.map((shift) => (
                        <li
                          key={shift.id}
                          className="flex items-center justify-between gap-2 rounded-md bg-muted px-2 py-1 text-xs"
                        >
                          <span className="truncate">{shiftLabel(shift)}</span>
                          {canPlot && (
                            <Button
                              variant="ghost"
                              size="icon"
                              className="h-6 w-6"
                              title={`Unplot ${shiftLabel(shift)}`}
                              aria-label={`Unplot ${shiftLabel(shift)}`}
                              disabled={unplotMutation.isPending}
                              onClick={() => handleUnplot(shift)}
                            >
                              <X className="h-3.5 w-3.5" />
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <PlotVehicleModal
        zone={plotFor}
        onClose={() => setPlotFor(null)}
        shifts={shifts}
        shiftLabel={shiftLabel}
      />
    </div>
  );
}

/** Pick an on-shift vehicle (an open shift not already plotted into this
 * zone) and plot it. Shifts plotted elsewhere are offered too -- plotting
 * moves them, since a shift has exactly one `plotted_zone_id`. */
function PlotVehicleModal({
  zone,
  onClose,
  shifts,
  shiftLabel,
}: {
  zone: ZoneStats | null;
  onClose: () => void;
  shifts: PlottedShift[];
  shiftLabel: (shift: PlottedShift) => string;
}) {
  const plotMutation = usePlotVehicleMutation();
  const [shiftId, setShiftId] = useState("");
  const [error, setError] = useState<string | null>(null);

  const candidates = zone ? shifts.filter((s) => s.plotted_zone_id !== zone.zone_id) : [];
  const options = [
    { value: "", label: candidates.length === 0 ? "No vehicles on shift to plot" : "Select a vehicle" },
    ...candidates.map((s) => ({
      value: s.id,
      label: s.plotted_zone_id ? `${shiftLabel(s)} (plotted elsewhere)` : shiftLabel(s),
    })),
  ];

  function close() {
    setShiftId("");
    setError(null);
    onClose();
  }

  async function submit() {
    const shift = candidates.find((s) => s.id === shiftId);
    if (!zone || !shift) return;
    setError(null);
    try {
      await plotMutation.mutateAsync({
        zoneId: zone.zone_id,
        target: { shift_id: shift.id, driver_id: shift.driver_id, vehicle_id: shift.vehicle_id },
      });
      close();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={zone != null}
      onClose={close}
      title={zone ? `Plot a vehicle into ${zone.zone_number} — ${zone.zone_name}` : "Plot a vehicle"}
      description="Only vehicles with an open shift can be plotted. The driver's tablet shows the zone on its next read."
      footer={
        <>
          <Button variant="outline" onClick={close}>
            Cancel
          </Button>
          <Button disabled={!shiftId || plotMutation.isPending} onClick={submit}>
            {plotMutation.isPending ? "Plotting…" : "Plot vehicle"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-1.5">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="plot-vehicle-shift">
          Vehicle
        </label>
        <Select
          id="plot-vehicle-shift"
          options={options}
          value={shiftId}
          onChange={(e) => setShiftId(e.target.value)}
          disabled={candidates.length === 0}
        />
      </div>
      {error && (
        <p className="mt-3 text-sm text-destructive">
          Could not plot: {error}
          {/409|no currently-open shift/i.test(error) && (
            <span className="mt-1 block text-xs text-muted-foreground">
              This backend plots the calling user's own shift only — plotting on a driver's behalf
              needs the backend update that accepts a target shift.
            </span>
          )}
        </p>
      )}
    </Modal>
  );
}

function StatCell({
  icon: Icon,
  label,
  value,
  tone,
}: {
  icon: typeof Users;
  label: string;
  value: number;
  tone?: "success" | "destructive";
}) {
  return (
    <div className="flex flex-col items-center gap-1 rounded-md bg-muted px-2 py-2.5">
      <Icon
        className={
          "h-4 w-4 " +
          (tone === "success"
            ? "text-success"
            : tone === "destructive"
              ? "text-destructive"
              : "text-muted-foreground")
        }
      />
      <span className="text-lg font-semibold text-foreground">{value}</span>
      <span className="text-[11px] leading-tight text-muted-foreground">{label}</span>
    </div>
  );
}
