import { useParams } from "react-router-dom";
import { EntityPage } from "@/components/layout/EntityPage";

/**
 * `/drivers/:driverId` -- placeholder shell (dashboard command-centre plan,
 * F1). Only the route and the `EntityPage` scaffold land in this workstream;
 * the real header facts, actions and tabs (Overview, Shifts, Trips,
 * Earnings & wallet, Ratings, Compliance, Messages, Devices & vehicles,
 * Activity -- plan §4) are built by the driver-page workstream that follows
 * it. A 404 body for a truly missing driver is deliberately out of scope
 * here too (see the plan) -- there is no lookup yet to be missing from.
 */
export default function DriverPage() {
  const { driverId } = useParams<{ driverId: string }>();
  const title = driverId ? `Driver ${driverId}` : "Driver";

  return (
    <EntityPage
      kind="driver"
      title={title}
      subtitle="Driver record"
      backTo={{ label: "Fleet & Drivers › Drivers", to: "/fleet?tab=drivers" }}
      tabs={[
        {
          value: "overview",
          label: "Overview",
          content: (
            <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
              Coming soon. This driver's overview, shifts, trips, earnings and wallet, ratings, compliance,
              messages, devices &amp; vehicles, and activity land here as the dashboard command-centre plan's
              driver-page workstream ships.
            </div>
          ),
        },
      ]}
    />
  );
}
