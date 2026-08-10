import { useState } from "react";
import { BarChart3, MapPinned } from "lucide-react";
import { PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { ZonesPanel } from "./ZonesPanel";
import { ZoneStatsPanel } from "./ZoneStatsPanel";

type ZonesTab = "stats" | "zones";

const TABS: { key: ZonesTab; label: string; icon: typeof BarChart3 }[] = [
  { key: "stats", label: "Live Stats", icon: BarChart3 },
  { key: "zones", label: "Zones", icon: MapPinned },
];

/** Zones & Demand -- named dispatch zones (drivers plot into a zone by its
 * short number while waiting for work) plus a live per-zone supply/demand
 * stats screen, matching a real competitor taxi meter's "Statistics" screen
 * from the dispatcher's side (GET /v1/zones, GET /v1/zones/stats). */
export default function ZonesPage() {
  const [tab, setTab] = useState<ZonesTab>("stats");

  return (
    <div>
      <PageHeader
        title="Zones & Demand"
        description="Dispatch zones drivers plot into while waiting for work, and a live per-zone supply/demand snapshot."
      />

      <div className="mb-6 flex gap-1 border-b border-border">
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              "flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors",
              tab === key
                ? "border-brand-primary text-brand-primary"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === "stats" && <ZoneStatsPanel />}
      {tab === "zones" && <ZonesPanel />}
    </div>
  );
}
