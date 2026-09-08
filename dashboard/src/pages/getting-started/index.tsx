import { Link } from "react-router-dom";
import { AlertTriangle, CheckCircle2, Circle } from "lucide-react";
import { Card, CardContent, PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { useDrivers, useVehicles } from "@/pages/fleet/api";
import { useTariffsQuery } from "@/hooks/useTariffStudio";

/**
 * Getting Started — /getting-started. A plain checklist for a brand-new
 * tenant, not a wizard: every item reflects REAL state via the same
 * queries/hooks the real pages already use (no fabricated/static
 * checkmarks), and every item links to the actual page where the action
 * happens. Nothing here is enforced — a tenant can ignore this page
 * entirely and use the app normally; it's just a shortcut for a first run.
 */
export default function GettingStartedPage() {
  // Same rollups VehiclesPanel/DriversPanel/TariffsPage already query --
  // limit: 1 (or the panel's own default) is enough since only `total`
  // (not the returned rows) is used here.
  const vehiclesQuery = useVehicles(0, {});
  const driversQuery = useDrivers(0, {});
  const tariffsQuery = useTariffsQuery({ skip: 0, limit: 1 });

  const vehicleCount = vehiclesQuery.data?.total;
  const driverCount = driversQuery.data?.total;
  const tariffCount = tariffsQuery.data?.total;

  const checkItems: {
    key: string;
    label: string;
    description: string;
    href: string;
    linkLabel: string;
    isLoading: boolean;
    isError: boolean;
    done: boolean;
  }[] = [
    {
      key: "vehicle",
      label: "Add your first vehicle",
      description: "Rego, class, and the registration/insurance dates NSW Point to Point regulation expects.",
      href: "/fleet?tab=vehicles",
      linkLabel: "Go to Fleet & Drivers → Vehicles",
      isLoading: vehiclesQuery.isLoading,
      isError: vehiclesQuery.isError,
      done: (vehicleCount ?? 0) > 0,
    },
    {
      key: "driver",
      label: "Add your first driver",
      description: "Creates a driver-role login (the meter app PIN) and, optionally, their licence/authority expiry dates.",
      href: "/fleet?tab=drivers",
      linkLabel: "Go to Fleet & Drivers → Drivers",
      isLoading: driversQuery.isLoading,
      isError: driversQuery.isError,
      done: (driverCount ?? 0) > 0,
    },
    {
      key: "tariff",
      label: "Set your tariff",
      description: "A rate card the meter uses to calculate fares — start from the NSW Fares Order reference or build your own.",
      href: "/tariffs",
      linkLabel: "Go to Tariff Studio",
      isLoading: tariffsQuery.isLoading,
      isError: tariffsQuery.isError,
      done: (tariffCount ?? 0) > 0,
    },
  ];

  return (
    <div>
      <PageHeader
        title="Getting Started"
        description="A quick checklist for a new fleet — nothing here is required, and none of it needs to happen in order."
      />

      <Card>
        <CardContent className="pt-4">
          <ul className="flex flex-col divide-y divide-border">
            {checkItems.map((item) => (
              <li key={item.key} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="flex items-start gap-3">
                  {item.isLoading ? (
                    <Circle className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
                  ) : item.isError ? (
                    <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
                  ) : item.done ? (
                    <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600 dark:text-emerald-400" />
                  ) : (
                    <Circle className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
                  )}
                  <div>
                    <p className={cn("text-sm font-medium text-foreground", item.done && "text-muted-foreground")}>
                      {item.label}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {item.isError ? "Couldn't check this yet — the page below still works." : item.description}
                    </p>
                  </div>
                </div>
                <Link
                  to={item.href}
                  className="shrink-0 text-sm font-medium text-brand-primary hover:underline"
                >
                  {item.linkLabel}
                </Link>
              </li>
            ))}

            {/* Always available — a navigation link, not something to "complete". */}
            <li className="flex flex-wrap items-center justify-between gap-3 py-3">
              <div className="flex items-start gap-3">
                <Circle className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" />
                <div>
                  <p className="text-sm font-medium text-foreground">Review compliance requirements</p>
                  <p className="text-xs text-muted-foreground">
                    NSW Point to Point regulatory documents, expiry reminders, and export tools — always worth a
                    look, not a one-time task.
                  </p>
                </div>
              </div>
              <Link to="/compliance" className="shrink-0 text-sm font-medium text-brand-primary hover:underline">
                Go to Compliance Vault
              </Link>
            </li>
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
