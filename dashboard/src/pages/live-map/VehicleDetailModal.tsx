import type { ReactNode } from "react";
import { Loader2 } from "lucide-react";
import { Badge, Modal } from "@/components/ui";
import {
  batteryColor,
  formatLatLng,
  formatRelativeTime,
  isStale,
  networkBadgeVariant,
  statusBadgeVariant,
} from "./utils";
import { useDriverDetailQuery, useVehicleDetailQuery } from "./useVehicleDetail";

export interface VehicleDetailModalProps {
  vehicleId: string | null;
  open: boolean;
  onClose: () => void;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="font-medium text-foreground">{children}</div>
    </div>
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
export function VehicleDetailModal({ vehicleId, open, onClose }: VehicleDetailModalProps) {
  const vehicleQuery = useVehicleDetailQuery(vehicleId);
  const vehicle = vehicleQuery.data;

  // Only fetched for the phone number -- id/name/on-shift-since already come
  // straight off VehicleLiveRead.current_driver_* below, no second lookup
  // needed for those.
  const driverQuery = useDriverDetailQuery(open ? vehicle?.current_driver_id ?? null : null);
  const driver = driverQuery.data;

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
                    ? "No update in over 90s -- may have lost connectivity"
                    : undefined
                }
              >
                {formatRelativeTime(vehicle.position_updated_at)}
              </span>
            </Field>
            <Field label="Current trip">
              <span className="font-mono text-xs">{vehicle.current_trip_id ?? "—"}</span>
            </Field>
          </div>

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
        </div>
      )}
    </Modal>
  );
}
