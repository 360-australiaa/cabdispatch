import { Badge, Sheet } from "@/components/ui";
import { useDevices } from "@/pages/fleet/api";
import { RemoteActions } from "./VehicleDetailModal";
import { batteryColor, formatLatLng, formatRelativeTime, networkBadgeVariant } from "./utils";

interface TabletDetailSheetProps {
  deviceId: string | null;
  onClose: () => void;
}

/**
 * The sheet for a tablet that answers to no vehicle.
 *
 * Deliberately a separate, much smaller panel than [VehicleDetailModal] rather than
 * that component with half its sections hidden. A bare tablet genuinely has no
 * driver, no shift history, no trip and no driving signals — rendering those as
 * empty states would suggest they might fill in, when what they mean is "this is
 * not a vehicle". What it does have is an identity, two clocks, and the three
 * remote controls, which is exactly what someone who came here to find a missing
 * tablet needs.
 */
export function TabletDetailSheet({ deviceId, onClose }: TabletDetailSheetProps) {
  const devicesQuery = useDevices(0, {}, 100);
  const device = devicesQuery.data?.items?.find((d) => d.id === deviceId);

  return (
    <Sheet
      open={deviceId != null}
      onClose={onClose}
      title={device ? (device.model ?? "Tablet") : "Tablet"}
      description={device?.android_id}
    >
      {!device ? (
        <p className="text-sm text-muted-foreground">
          {devicesQuery.isLoading ? "Loading tablet…" : "This tablet is no longer in the device list."}
        </p>
      ) : (
        <div className="flex flex-col gap-5 text-sm">
          <div className="rounded-lg border border-border p-3">
            <div className="grid grid-cols-2 gap-x-4 gap-y-3">
              <Field label="App version">{device.app_version ?? "—"}</Field>
              <Field label="Kiosk">
                <Badge variant={device.kiosk_locked ? "success" : "outline"}>
                  {device.kiosk_locked ? "locked" : "unlocked"}
                </Badge>
              </Field>
              <Field label="Battery">
                {device.battery == null ? (
                  <span className="text-muted-foreground">—</span>
                ) : (
                  <span style={{ color: batteryColor(device.battery) }}>{device.battery}%</span>
                )}
              </Field>
              <Field label="Network">
                {device.network == null ? (
                  <span className="text-muted-foreground">—</span>
                ) : (
                  <Badge variant={networkBadgeVariant(device.network)}>{device.network}</Badge>
                )}
              </Field>
              {/* Two clocks with different meanings, both named rather than
                  collapsed into one "last activity". "Heartbeat" says the tablet is
                  switched on and has signal; "located" says when someone last asked
                  it where it was, which can be days older and is the age of the dot
                  on the map. */}
              <Field label="Heartbeat">{formatRelativeTime(device.last_seen_at)}</Field>
              <Field label="Located">{formatRelativeTime(device.last_locate_at)}</Field>
            </div>
            <RemoteActions deviceId={device.id} />
          </div>

          <div className="rounded-lg border border-border p-3">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Last known position
            </p>
            {device.last_locate_lat == null || device.last_locate_lng == null ? (
              <p className="text-sm text-muted-foreground">
                This tablet has never answered a locate. Press Locate above and it will report on its
                next heartbeat.
              </p>
            ) : (
              <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                <Field label="Position">
                  <span className="font-mono text-xs">
                    {formatLatLng(device.last_locate_lat, device.last_locate_lng)}
                  </span>
                </Field>
                <Field label="Accuracy">
                  {device.last_locate_accuracy_m == null
                    ? "—"
                    : `±${Math.round(device.last_locate_accuracy_m)} m`}
                </Field>
              </div>
            )}
          </div>

          <p className="text-xs text-muted-foreground">
            {/* The reason this panel exists at all, said plainly. */}
            This tablet is not bound to a vehicle, so it publishes no live position and appears on
            the map only where it last answered a locate. Bind it to a vehicle on Fleet &amp; Drivers
            to see it move.
          </p>
        </div>
      )}
    </Sheet>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="font-medium text-foreground">{children}</div>
    </div>
  );
}
