import { useParams } from "react-router-dom";
import { EntityPage } from "@/components/layout/EntityPage";

/**
 * `/vehicles/:vehicleId` -- placeholder shell (dashboard command-centre
 * plan, F1). Only the route and the `EntityPage` scaffold land in this
 * workstream; the real header facts, actions and tabs (Live, Trips, Shifts,
 * Compliance, Reports, Tolls, Maintenance, Activity -- plan §5) are built by
 * the vehicle-page workstream that follows it.
 */
export default function VehiclePage() {
  const { vehicleId } = useParams<{ vehicleId: string }>();
  const title = vehicleId ? `Vehicle ${vehicleId}` : "Vehicle";

  return (
    <EntityPage
      kind="vehicle"
      title={title}
      subtitle="Vehicle record"
      backTo={{ label: "Fleet & Drivers › Vehicles", to: "/fleet?tab=vehicles" }}
      tabs={[
        {
          value: "overview",
          label: "Overview",
          content: (
            <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
              Coming soon. This vehicle's live position, trips, shifts, compliance, reports, tolls,
              maintenance, and activity land here as the dashboard command-centre plan's vehicle-page
              workstream ships.
            </div>
          ),
        },
      ]}
    />
  );
}
