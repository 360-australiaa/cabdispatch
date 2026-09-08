import { useMemo } from "react";
import { Badge, Button } from "@/components/ui";
import { useDeviceOptions } from "@/pages/fleet/api";
import type { Device } from "@/pages/fleet/types";
import { errorMessage } from "@/pages/fleet/format";

export interface VersionsTabProps {
  device: Device;
  canManage: boolean;
  onPushUpdate: () => void;
  isPushing: boolean;
}

/**
 * `/devices/:id?tab=versions` -- this device's own app version, plus a
 * client-side tally of every OTHER registered device's `app_version`
 * (dashboard command-centre plan §6.4: "if reachable, how many other
 * tablets in the fleet are on each version"). Real data, not a
 * placeholder -- `GET /v1/fleet/devices` (via the same `useDeviceOptions`
 * lookup `DevicesPanel` already uses to cross-reference labels) is a real
 * endpoint, this just groups its own response by `app_version` instead of
 * needing a new backend aggregation.
 *
 * Deliberately NOT the per-device app-version HISTORY the plan's §6.4 text
 * also mentions ("app version history from heartbeats") -- that reads from
 * `device_version_history`, which has no list endpoint today (only the
 * vehicle evidence-pack reads it internally). Out of scope for this
 * workstream; the fleet-wide tally below is what's actually reachable.
 */
export function VersionsTab({ device, canManage, onPushUpdate, isPushing }: VersionsTabProps) {
  const optionsQuery = useDeviceOptions();

  const tally = useMemo(() => {
    const counts = new Map<string, number>();
    for (const d of optionsQuery.data ?? []) {
      const key = d.app_version ?? "Unknown";
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  }, [optionsQuery.data]);

  const fleetTotal = optionsQuery.data?.length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs text-muted-foreground">This device's app version</p>
            <p className="text-lg font-semibold text-foreground">{device.app_version ?? "—"}</p>
          </div>
          <Button
            variant="outline"
            onClick={onPushUpdate}
            disabled={!canManage || isPushing || device.force_update_pending}
            title={
              !canManage
                ? "Requires an owner or admin account"
                : device.force_update_pending
                  ? "An update is already queued for this device"
                  : "Push a forced app update to this one device"
            }
          >
            {device.force_update_pending ? "Update pending…" : "Push update to this device"}
          </Button>
        </div>
      </div>

      <div className="rounded-lg border border-border p-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Fleet-wide version spread
        </p>
        {optionsQuery.isError ? (
          <p className="text-sm text-destructive">
            Could not load the fleet's device list: {errorMessage(optionsQuery.error)}
          </p>
        ) : optionsQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : fleetTotal === 0 ? (
          <p className="text-sm text-muted-foreground">No registered devices found.</p>
        ) : (
          <div className="flex flex-col gap-1.5">
            {tally.map(([version, count]) => (
              <div key={version} className="flex items-center justify-between gap-3 text-sm">
                <span className={version === (device.app_version ?? "Unknown") ? "font-medium" : undefined}>
                  {version}
                  {version === (device.app_version ?? "Unknown") && (
                    <Badge variant="outline" className="ml-2">
                      this device
                    </Badge>
                  )}
                </span>
                <span className="text-muted-foreground">
                  {count} of {fleetTotal}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
