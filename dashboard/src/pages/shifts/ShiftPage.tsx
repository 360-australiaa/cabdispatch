import { useParams } from "react-router-dom";
import { EntityPage } from "@/components/layout/EntityPage";

/**
 * `/shifts/:shiftId` -- placeholder shell (dashboard command-centre plan,
 * F1). Only the route and the `EntityPage` scaffold land in this
 * workstream; the real header facts, actions and tabs (Summary, Trips,
 * Breaks, Timeline, Reconciliation -- plan §7) are built by the trip/shift
 * page workstream that follows it. `ShiftReportModal` stays the working
 * shift detail surface until that workstream replaces it.
 */
export default function ShiftPage() {
  const { shiftId } = useParams<{ shiftId: string }>();
  const title = shiftId ? `Shift ${shiftId}` : "Shift";

  return (
    <EntityPage
      kind="shift"
      title={title}
      subtitle="Shift record"
      backTo={{ label: "Shifts", to: "/shifts" }}
      tabs={[
        {
          value: "overview",
          label: "Overview",
          content: (
            <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
              Coming soon. This shift's summary, trips, breaks, timeline, and reconciliation land here
              as the dashboard command-centre plan's trip/shift-page workstream ships.
            </div>
          ),
        },
      ]}
    />
  );
}
