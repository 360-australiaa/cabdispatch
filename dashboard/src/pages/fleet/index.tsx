import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Car, Smartphone, Users } from "lucide-react";
import { PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { VehiclesPanel } from "./VehiclesPanel";
import { DriversPanel } from "./DriversPanel";
import { DevicesPanel } from "./DevicesPanel";
import { FatigueAlertsBanner } from "./FatigueAlertsBanner";
import { ComplianceExpiryBanner } from "./ComplianceExpiryBanner";

type FleetTab = "vehicles" | "drivers" | "devices";

const TABS: { key: FleetTab; label: string; icon: typeof Car }[] = [
  { key: "vehicles", label: "Vehicles", icon: Car },
  { key: "drivers", label: "Drivers", icon: Users },
  { key: "devices", label: "Devices", icon: Smartphone },
];

const VALID_TABS = new Set<string>(TABS.map((t) => t.key));

/** Fleet & Drivers — /fleet. Vehicle + device CRUD against the real backend,
 * plus a read-only driver rollup (see DriversPanel for why). Honors an
 * initial `?tab=` query param (e.g. `/fleet?tab=drivers`, used by the Getting
 * Started checklist to deep-link straight into a specific tab) — same
 * "read once, plain useState afterwards" convention as ShiftsPage's
 * `vehicle_id` param; the tab itself is not kept in sync with the URL after
 * that, matching how this page's other filters already behave. */
export default function FleetPage() {
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<FleetTab>(() => {
    const requested = searchParams.get("tab");
    return requested && VALID_TABS.has(requested) ? (requested as FleetTab) : "vehicles";
  });

  return (
    <div>
      <PageHeader
        title="Fleet & Drivers"
        description="Manage vehicles, their linked kiosk devices, and view driver live-status."
      />

      <FatigueAlertsBanner />
      <ComplianceExpiryBanner />

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

      {tab === "vehicles" && <VehiclesPanel />}
      {tab === "drivers" && <DriversPanel />}
      {tab === "devices" && <DevicesPanel />}
    </div>
  );
}
