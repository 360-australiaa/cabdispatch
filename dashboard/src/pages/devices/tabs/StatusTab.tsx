import { Badge } from "@/components/ui";
import type { Device } from "@/pages/fleet/types";
import { formatDateTime, relativeFromNow } from "@/pages/fleet/format";
import { DeviceLocationMap } from "../DeviceLocationMap";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="font-medium text-foreground">{children}</div>
    </div>
  );
}

/**
 * `/devices/:id?tab=status` -- the header facts expanded, per the plan's
 * §6.1: current location on a small map, the last locate response with
 * accuracy, the update-pending flag, and kiosk requested-vs-confirmed state.
 * Nothing here repeats a header fact verbatim (model/android id/app
 * version/battery/network/heartbeat age/paired vehicle/kiosk state all live
 * in the header row instead).
 */
export function StatusTab({ device }: { device: Device }) {
  const hasPosition = device.last_locate_lat != null && device.last_locate_lng != null;

  // Kiosk is a DESIRED STATE, not a one-shot command (see backend
  // `record_command_ack`'s own doc) -- `kiosk_locked` says what was asked
  // for, `last_acked_command`/`command_acked_at` say whether THIS tablet has
  // ever told the server it applied a kiosk-lock change, and the two can
  // disagree for a long time on a tablet that is switched off or offline.
  const kioskConfirmed = device.last_acked_command === "kiosk_lock" && device.command_acked_at != null;

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Command state
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-4">
          <Field label="Update">
            {device.force_update_pending ? (
              <Badge variant="accent">Pending</Badge>
            ) : (
              <span className="text-muted-foreground">Up to date with last push</span>
            )}
          </Field>
          <Field label="Kiosk requested">
            <Badge variant={device.kiosk_locked ? "success" : "outline"}>
              {device.kiosk_locked ? "Locked" : "Unlocked"}
            </Badge>
          </Field>
          <Field label="Kiosk confirmed by tablet">
            {kioskConfirmed ? (
              <span title={formatDateTime(device.command_acked_at)}>
                {relativeFromNow(device.command_acked_at)}
              </span>
            ) : (
              <span className="text-muted-foreground">Not yet confirmed</span>
            )}
          </Field>
          <Field label="Locate">
            {device.locate_requested ? (
              <Badge variant="accent">Waiting…</Badge>
            ) : (
              <span className="text-muted-foreground">No request outstanding</span>
            )}
          </Field>
        </div>
      </div>

      <div className="rounded-lg border border-border p-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Last known position
        </p>
        {!hasPosition ? (
          <p className="text-sm text-muted-foreground">
            This tablet has never answered a locate request. Use Locate above and it will report on
            its next heartbeat.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,240px)_1fr]">
            <DeviceLocationMap lat={device.last_locate_lat!} lng={device.last_locate_lng!} />
            <div className="grid grid-cols-2 gap-x-4 gap-y-3 self-start">
              <Field label="Position">
                <span className="font-mono text-xs">
                  {device.last_locate_lat!.toFixed(5)}, {device.last_locate_lng!.toFixed(5)}
                </span>
              </Field>
              <Field label="Accuracy">
                {device.last_locate_accuracy_m == null
                  ? "—"
                  : `±${Math.round(device.last_locate_accuracy_m)} m`}
              </Field>
              <Field label="Reported">
                <span title={formatDateTime(device.last_locate_at)}>
                  {relativeFromNow(device.last_locate_at)}
                </span>
              </Field>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
