import { useMemo, useState } from "react";
import { Search } from "lucide-react";
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

interface FleetLocateListProps {
  vehicles: VehicleMapState[];
  selectedVehicleId: string | null;
  onSelect: (vehicleId: string) => void;
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
export function FleetLocateList({ vehicles, selectedVehicleId, onSelect }: FleetLocateListProps) {
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
    </div>
  );
}
