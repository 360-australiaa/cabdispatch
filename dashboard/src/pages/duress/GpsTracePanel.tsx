import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Radio } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatTime } from "./format";
import type { LiveGpsStatus } from "./useDuressLiveGps";
import type { DuressGpsPoint } from "./types";

const STATUS_LABEL: Record<LiveGpsStatus, string> = {
  idle: "Not connected",
  connecting: "Connecting…",
  open: "Live",
  closed: "Disconnected",
  error: "Connection error",
};

const TABLET_COLOR = "var(--brand-primary)";
const DEVICE_COLOR = "var(--brand-accent)";

/**
 * Live GPS trace for a selected duress event, fed by
 * `useDuressLiveGps` (WS /v1/duress/{id}/live). Points are relayed only,
 * never persisted server-side — this panel is the only visible record of
 * the trace while the socket is open.
 *
 * Points carry an optional `source` ("tablet" | "device"). When fixes from
 * both a tablet and a paired physical duress device are present, they're
 * plotted as two distinct traces (colour-coded, with a small legend); a
 * single-source feed renders exactly as before.
 */
export function GpsTracePanel({
  status,
  points,
  stale = false,
  lastFixAgeMs = null,
}: {
  status: LiveGpsStatus;
  points: DuressGpsPoint[];
  /** True when the socket reports `open` but no fix has landed within the
   * staleness window (`useDuressLiveGps`'s `STALE_AFTER_MS`) — the per-
   * process `GPSBroadcaster` frozen-position failure mode documented in
   * `docs/followups/2026-09-08-redis-pubsub-broadcasters.md`. A stale feed
   * must never read as "Live" — this is a life-safety surface. */
  stale?: boolean;
  lastFixAgeMs?: number | null;
}) {
  const latest = points[points.length - 1] ?? null;
  const hasDeviceSource = points.some((p) => p.source === "device");
  const hasTabletSource = points.some((p) => (p.source ?? "tablet") === "tablet");
  const hasBothSources = hasDeviceSource && hasTabletSource;

  const chartData = points.map((p, i) => {
    const source = p.source ?? "tablet";
    return {
      i,
      lat: p.lat,
      lng: p.lng,
      latTablet: source === "tablet" ? p.lat : undefined,
      latDevice: source === "device" ? p.lat : undefined,
      speed: p.speed_kmh ?? null,
      ts: p.ts,
    };
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-foreground">Live GPS trace</span>
        <span
          className={cn(
            "inline-flex items-center gap-1.5 text-xs font-medium",
            status === "open" && !stale && "text-success",
            status === "open" && stale && "text-destructive",
            status === "connecting" && "text-muted-foreground",
            (status === "closed" || status === "error") && "text-destructive",
            status === "idle" && "text-muted-foreground",
          )}
        >
          {/* The pulsing dot is the "this is live" signal — the calm-motion
              doctrine's own duress-exception (a duress-active indicator is
              exempt from reduce-motion, same as the Android side) applies
              specifically to a genuinely-live feed. A stale one must NOT
              pulse: that would tell the operator the frozen position is
              current, which is the exact honesty failure this panel exists
              to prevent. */}
          <Radio className={cn("h-3 w-3", status === "open" && !stale && "animate-pulse")} />
          {stale ? "Stale — position may not be current" : STATUS_LABEL[status]}
          {status === "open" &&
            !stale &&
            ` · ${points.length} fix${points.length === 1 ? "" : "es"}`}
        </span>
      </div>

      {stale && (
        <p className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs text-destructive">
          No GPS fix in {lastFixAgeMs != null ? Math.round(lastFixAgeMs / 1000) : "?"}s, though
          the connection is still open. Do not treat the position below as this driver's current
          location — check with the driver or another source before acting on it.
        </p>
      )}

      {hasBothSources && (
        <div className="flex items-center gap-4 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1.5">
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: TABLET_COLOR }}
              aria-hidden="true"
            />
            Tablet
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: DEVICE_COLOR }}
              aria-hidden="true"
            />
            Device
          </span>
        </div>
      )}

      {points.length === 0 ? (
        <div className="flex h-40 items-center justify-center rounded-md border border-dashed border-border text-xs text-muted-foreground">
          {status === "open"
            ? "Connected — waiting for the first GPS fix…"
            : "No GPS fixes received yet."}
        </div>
      ) : (
        <>
          <div className="h-40 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData} margin={{ top: 4, right: 8, bottom: 0, left: -16 }}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                <XAxis
                  dataKey="lng"
                  type="number"
                  domain={["dataMin", "dataMax"]}
                  tick={{ fontSize: 10 }}
                  tickFormatter={(v: number) => v.toFixed(3)}
                />
                <YAxis
                  dataKey="lat"
                  type="number"
                  domain={["dataMin", "dataMax"]}
                  tick={{ fontSize: 10 }}
                  width={56}
                  tickFormatter={(v: number) => v.toFixed(3)}
                />
                <Tooltip
                  formatter={(value: number, name: string) => [value.toFixed(5), name]}
                  labelFormatter={(_, payload) =>
                    payload?.[0]?.payload?.ts ? formatTime(payload[0].payload.ts as string) : ""
                  }
                />
                {hasBothSources ? (
                  <>
                    <Line
                      type="monotone"
                      dataKey="latTablet"
                      stroke={TABLET_COLOR}
                      strokeWidth={2}
                      dot={{ r: 2 }}
                      isAnimationActive={false}
                      connectNulls
                      name="Tablet"
                    />
                    <Line
                      type="monotone"
                      dataKey="latDevice"
                      stroke={DEVICE_COLOR}
                      strokeWidth={2}
                      dot={{ r: 2 }}
                      isAnimationActive={false}
                      connectNulls
                      name="Device"
                    />
                  </>
                ) : (
                  <Line
                    type="monotone"
                    dataKey="lat"
                    stroke="var(--brand-primary)"
                    strokeWidth={2}
                    dot={{ r: 2 }}
                    isAnimationActive={false}
                    name="lat"
                  />
                )}
              </LineChart>
            </ResponsiveContainer>
          </div>

          {latest && (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-4">
              <div>
                <dt className="text-muted-foreground">Lat</dt>
                <dd className="font-medium text-foreground">{latest.lat.toFixed(5)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Lng</dt>
                <dd className="font-medium text-foreground">{latest.lng.toFixed(5)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Speed</dt>
                <dd className="font-medium text-foreground">
                  {latest.speed_kmh != null ? `${latest.speed_kmh.toFixed(0)} km/h` : "—"}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Last fix</dt>
                <dd className={cn("font-medium", stale ? "text-destructive" : "text-foreground")}>
                  {formatTime(latest.ts)}
                  {stale && lastFixAgeMs != null && ` (${Math.round(lastFixAgeMs / 1000)}s ago)`}
                </dd>
              </div>
            </dl>
          )}
        </>
      )}
    </div>
  );
}
