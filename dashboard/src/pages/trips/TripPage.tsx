import { useParams } from "react-router-dom";
import { EntityPage } from "@/components/layout/EntityPage";

/**
 * `/trips/:tripId` -- placeholder shell (dashboard command-centre plan,
 * F1). Only the route and the `EntityPage` scaffold land in this
 * workstream; the real header facts, actions and tabs (Fare, Route,
 * Payments, Receipt, Rating, Audit -- plan §7) are built by the trip/shift
 * page workstream that follows it. `TripDetailModal` stays the working
 * trip detail surface until that workstream replaces it.
 */
export default function TripPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const title = tripId ? `Trip ${tripId}` : "Trip";

  return (
    <EntityPage
      kind="trip"
      title={title}
      subtitle="Trip record"
      backTo={{ label: "Trips", to: "/trips" }}
      tabs={[
        {
          value: "overview",
          label: "Overview",
          content: (
            <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
              Coming soon. This trip's fare breakdown, route, payments, receipt, rating, and audit trail
              land here as the dashboard command-centre plan's trip-page workstream ships.
            </div>
          ),
        },
      ]}
    />
  );
}
