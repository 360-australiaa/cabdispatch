import { useState } from "react";
import { BarChart3, MapPinned } from "lucide-react";
import { PageHeader, Tabs, type TabItem } from "@/components/ui";
import { ZonesPanel } from "./ZonesPanel";
import { ZoneStatsPanel } from "./ZoneStatsPanel";

type ZonesTab = "stats" | "zones";

const TABS: TabItem<ZonesTab>[] = [
  { value: "stats", label: "Live Stats", icon: BarChart3 },
  { value: "zones", label: "Zones", icon: MapPinned },
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

      <Tabs
        items={TABS}
        value={tab}
        onChange={setTab}
        variant="underline"
        label="Zones sections"
        className="mb-6"
      />

      {tab === "stats" && <ZoneStatsPanel />}
      {tab === "zones" && <ZonesPanel />}
    </div>
  );
}
