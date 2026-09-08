import { useMemo, useState } from "react";
import { Search, Tablet } from "lucide-react";
import { Badge, Input } from "@/components/ui";
import { cn } from "@/lib/utils";
import type { VehicleMapState } from "./FleetMapCanvas";
import { formatRelativeTime, formatSpeed, isStale, statusBadgeVariant, statusColor } from "./utils";

/** Which vehicles the list is showing. "stale" is deliberately its own filter rather
 * than a flavour of offline: a stale vehicle is one that WAS reporting and stopped,
 * which is a different problem from one that never checked in, and it is usually the
 * one an operator is hunting for. */
type StatusFilter = "all" | "available" | "on_trip" | "offline" | "stale";

const FILTERS: { key: StatusFilter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "available", label: "Available" },
  { key: "on_trip", label: "On trip" },
  { key: "stale", label: "Stale" },
  { key: "offline", label: "Offline" },
];

/** A tablet that answers to no vehicle, as the list needs to show it. */
export interface UnpairedTablet {
  id: string;
  androidId: string;
  model: string | null;
  lastSeenAt: string | null;
  /** Where it last answered a locate from, if it ever has. Null means it can be
   * listed but not flown to -- which is still worth saying out loud. */
  lat: number | null;
  lng: number | null;
  locatedAt: string | null;
}

interface FleetLocateListProps {
  vehicles: VehicleMapState[];
  selectedVehicleId: string | null;
  onSelect: (vehicleId: string) => void;
  /** Tablets bound to no vehicle. Listed in their own group -- see the section's
   * own comment below for why they are not merged into the vehicle list. */
  unpairedTablets?: UnpairedTablet[];
  selectedTabletId?: string | null;
  onSelectTablet?: (deviceId: string) => void;
}

/**
 * The find-a-vehicle half of the locate view.
 *
 * Sits beside the map rather than under it, and searches the three things an operator
 * actually knows when they go looking: the rego, the driver's name, or the tablet's
 * Android ID (someone reading a device row on Fleet ▸ Devices has only the last of
 * those). The previous list searched rego alone, was paginated below the fold, and
 * filtering it did not filter the map — so narrowing the list to one car still left
 * every pin on screen.
 *
 * Selecting a row is what flies the map there; the parent owns that, so this component
 * stays a pure list.
 */
export function FleetLocateList({
  vehicles,
  selectedVehicleId,
  onSelect,
  unpairedTablets = [],
  selectedTabletId = null,
  onSelectTablet = () => {},
}: FleetLocateListProps) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<StatusFilter>("all");

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return vehicles
      .filter((v) => {
        if (q) {
          const haystack = [v.rego, v.current_driver_name, v.device_id].filter(Boolean).join(" ").toLowerCase();
          if (!haystack.includes(q)) return false;
        }
        const stale = isStale(v.position_updated_at);
        switch (filter) {
          case "all":
            return true;
          case "stale":
            return stale;
          case "offline":
            return v.live_status === "offline";
          case "available":
            return v.live_status === "available" && !stale;
          case "on_trip":
            return v.live_status === "on_trip" && !stale;
        }
      })
      // Whatever is most likely to be the thing being hunted, first: the ones that
      // stopped reporting, then the ones working, then the rest.
      .sort((a, b) => {
        const rank = (v: VehicleMapState) =>
          isStale(v.position_updated_at) ? 0 : v.live_status === "on_trip" ? 1 : v.live_status === "available" ? 2 : 3;
        const byRank = rank(a) - rank(b);
        return byRank !== 0 ? byRank : a.rego.localeCompare(b.rego);
      });
  }, [vehicles, query, filter]);

  // The same search box, against the two things anyone knows about a bare tablet.
  // Status filters deliberately do NOT apply: a tablet has no live_status, and
  // silently emptying this group when someone clicks "Available" would recreate
  // the exact "locate found nothing" confusion the group exists to prevent.
  const shownTablets = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return unpairedTablets;
    return unpairedTablets.filter((t) =>
      [t.androidId, t.model].filter(Boolean).join(" ").toLowerCase().includes(q),
    );
  }, [unpairedTablets, query]);

  return (
    <div className="flex w-full shrink-0 flex-col gap-3 lg:w-[340px]">
      <div className="relative">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Rego, driver or tablet ID"
          className="pl-8"
          aria-label="Search vehicles by rego, driver or tablet ID"
        />
      </div>

      <div className="flex flex-wrap gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            onClick={() => setFilter(f.key)}
            className={cn(
              "rounded-full border px-2.5 py-1 text-xs transition-colors",
              filter === f.key
                ? "border-transparent bg-primary text-primary-foreground"
                : "border-border text-muted-foreground hover:text-foreground",
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="max-h-[420px] overflow-y-auto rounded-md border border-border">
        {shown.length === 0 ? (
          <p className="p-4 text-sm text-muted-foreground">
            {vehicles.length === 0 ? "No vehicles reporting yet." : "Nothing matches that search."}
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {shown.map((v) => {
              const stale = isStale(v.position_updated_at);
              const selected = v.id === selectedVehicleId;
              return (
                <li key={v.id}>
                  <button
                    type="button"
                    onClick={() => onSelect(v.id)}
                    aria-current={selected}
                    className={cn(
                      "flex w-full items-start gap-2.5 px-3 py-2.5 text-left transition-colors",
                      selected ? "bg-accent/15" : "hover:bg-muted/40",
                    )}
                  >
                    <span
                      className="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{ background: statusColor(v.live_status) }}
                      aria-hidden
                    />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate font-medium">{v.rego}</span>
                        <Badge variant={statusBadgeVariant(v.live_status)}>{v.live_status.replace("_", " ")}</Badge>
                        {/* Stale is called out on the row, not just on the pin -- an
                            operator scanning the list is exactly who needs to know a
                            position is old before they trust it. */}
                        {stale && <Badge variant="destructive">stale</Badge>}
                      </span>
                      <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                        {v.current_driver_name ?? "No driver on shift"}
                      </span>
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {formatSpeed(v.speed_kmh)} · {formatRelativeTime(v.position_updated_at)}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <p className="text-xs text-muted-foreground">
        {shown.length} of {vehicles.length} shown
      </p>

      {/* Their own group, never merged into the vehicle list. A device and a
          vehicle are different things: a tablet has no rego, no driver, no live
          status and no live position -- only whatever it last answered a locate
          with. Before this, such a tablet could not appear on this page at all,
          so an operator searching for one found nothing and had no way to tell
          "not here" from "not paired". */}
      {shownTablets.length > 0 && (
        <div>
          <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Tablets with no vehicle
          </p>
          <ul className="divide-y divide-border rounded-md border border-border">
            {shownTablets.map((t) => {
              const locatable = t.lat != null && t.lng != null;
              const selected = t.id === selectedTabletId;
              return (
                <li key={t.id}>
                  <button
                    type="button"
                    onClick={() => onSelectTablet(t.id)}
                    aria-current={selected}
                    className={cn(
                      "flex w-full items-start gap-2.5 px-3 py-2.5 text-left transition-colors",
                      selected ? "bg-accent/15" : "hover:bg-muted/40",
                    )}
                  >
                    <Tablet className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium">{t.model ?? "Tablet"}</span>
                      <span className="mt-0.5 block truncate font-mono text-xs text-muted-foreground">
                        {t.androidId}
                      </span>
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {/* Two clocks, both named. "Seen" is the 60s heartbeat, which
                            says the tablet is on; "located" is the last time anyone
                            asked it where it was, which can be days older. */}
                        Seen {formatRelativeTime(t.lastSeenAt)} ·{" "}
                        {locatable ? `located ${formatRelativeTime(t.locatedAt)}` : "never located"}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
