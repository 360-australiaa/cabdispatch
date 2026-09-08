import { useParams } from "react-router-dom";
import { EntityPage } from "@/components/layout/EntityPage";

/**
 * `/devices/:deviceId` -- placeholder shell (dashboard command-centre plan,
 * F1). Only the route and the `EntityPage` scaffold land in this workstream;
 * the real header facts, actions and tabs (Status, Heartbeats, Commands,
 * Versions, Activity -- plan §6) are built by the device-page workstream
 * that follows it.
 */
export default function DevicePage() {
  const { deviceId } = useParams<{ deviceId: string }>();
  const title = deviceId ? `Device ${deviceId}` : "Device";

  return (
    <EntityPage
      kind="device"
      title={title}
      subtitle="Tablet record"
      backTo={{ label: "Fleet & Drivers › Devices", to: "/fleet?tab=devices" }}
      tabs={[
        {
          value: "overview",
          label: "Overview",
          content: (
            <div className="rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
              Coming soon. This tablet's status, heartbeat history, command log, version rollout, and
              activity land here as the dashboard command-centre plan's device-page workstream ships.
            </div>
          ),
        },
      ]}
    />
  );
}
