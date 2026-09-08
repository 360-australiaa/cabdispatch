import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Archive, FileText, KeyRound, MapPin, Pencil, Sliders, Trash2 } from "lucide-react";
import { EntityPage, type EntityFact } from "@/components/layout/EntityPage";
import { EntityLink } from "@/components/EntityLink";
import { Badge, Button, Modal, Tooltip, useToast } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { errorMessage, formatRelativeTime } from "@/lib/format";
import { downloadVehicleDossierPdf, useVehicleDossierQuery } from "@/hooks/useComplianceVault";
import {
  downloadVehicleEvidencePack,
  useDeleteVehicle,
  useDeviceDetailQuery,
  useLocateDevice,
  useVehicle,
} from "@/pages/fleet/api";
import { VEHICLE_CLASS_OPTIONS, type Vehicle, type VehicleStatus } from "@/pages/fleet/types";
import { useVehicleDetailQuery } from "@/pages/live-map/useVehicleDetail";
import { statusBadgeVariant as liveStatusBadgeVariant } from "@/pages/live-map/utils";
import { EditVehicleSheet } from "./EditVehicleSheet";
import { PairTabletModal } from "./PairTabletModal";
import { SetStatusModal } from "./SetStatusModal";
import { LiveTab } from "./tabs/LiveTab";
import { TripsTab } from "./tabs/TripsTab";
import { ShiftsTab } from "./tabs/ShiftsTab";
import { ComplianceTab } from "./tabs/ComplianceTab";
import { ReportsTab } from "./tabs/ReportsTab";
import { TollsTab } from "./tabs/TollsTab";
import { ActivityTab } from "./tabs/ActivityTab";

/** Same mapping `VehiclesPanel` uses for its own status column -- not
 * exported there, so kept as a small local copy rather than reaching into
 * that file's internals. */
function vehicleStatusBadgeVariant(status: VehicleStatus) {
  switch (status) {
    case "active":
      return "success" as const;
    case "maintenance":
      return "accent" as const;
    case "suspended":
      return "destructive" as const;
    default:
      return "outline" as const;
  }
}

/**
 * `/vehicles/:vehicleId` -- dashboard command-centre plan §5.
 *
 * Ports `VehicleDetailModal`'s live status/device/driver/shift-history/
 * position-history-replay/driving-signals content and `VehicleReportsModal`'s
 * two report tabs into this page's tabs (see `tabs/LiveTab.tsx` and
 * `tabs/ReportsTab.tsx` for exactly what each ports vs. leaves alone).
 * Neither modal is deleted or repointed yet -- the map-dot click and the
 * Fleet vehicles-list row click still open them, per the plan's own
 * "don't repoint yet, a later workstream does that once this page is
 * proven" instruction. TODO(vehicle-page-cutover): once this page is
 * proven, repoint `FleetMapCanvas`'s non-duress marker click and
 * `VehiclesPanel`'s row click here, and delete `VehicleDetailModal` /
 * `VehicleReportsModal`.
 *
 * Maintenance (plan §5.7) is NOT built here -- it needs a new
 * `vehicle_maintenance` backend table/migration, out of scope for a
 * dashboard-only pass.
 */
export default function VehiclePage() {
  const { vehicleId } = useParams<{ vehicleId: string }>();
  const navigate = useNavigate();
  const toast = useToast();
  const { user } = useAuth();
  const isOwner = user?.role === "owner";

  const vehicleQuery = useVehicle(vehicleId ?? null);
  const vehicle = vehicleQuery.data;

  const liveQuery = useVehicleDetailQuery(vehicleId ?? null, { poll: true });
  const live = liveQuery.data;

  const deviceQuery = useDeviceDetailQuery(live?.device_id ?? null);
  const device = deviceQuery.data;

  const dossierQuery = useVehicleDossierQuery(vehicleId ?? null);

  const locate = useLocateDevice();
  const [exporting, setExporting] = useState(false);
  const [dossierDownloading, setDossierDownloading] = useState(false);

  const [editOpen, setEditOpen] = useState(false);
  const [pairTarget, setPairTarget] = useState<Vehicle | null>(null);
  const [statusTarget, setStatusTarget] = useState<Vehicle | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const deleteVehicle = useDeleteVehicle();

  async function handleExportEvidencePack() {
    if (!vehicle) return;
    setExporting(true);
    try {
      await downloadVehicleEvidencePack(vehicle);
    } catch (err) {
      toast.error("Failed to export evidence pack", { description: errorMessage(err) });
    } finally {
      setExporting(false);
    }
  }

  async function handleDownloadDossier() {
    if (!vehicleId) return;
    setDossierDownloading(true);
    try {
      await downloadVehicleDossierPdf(vehicleId);
    } catch (err) {
      toast.error("Failed to download compliance dossier", { description: errorMessage(err) });
    } finally {
      setDossierDownloading(false);
    }
  }

  function handleLocate() {
    if (!live?.device_id) return;
    locate.mutate(live.device_id, {
      onSuccess: () =>
        toast.success("Location requested", {
          description: "The tablet reports back on its next heartbeat, within about a minute.",
        }),
      onError: (err) => toast.error("Failed to request location", { description: errorMessage(err) }),
    });
  }

  async function handleDelete() {
    if (!vehicle) return;
    try {
      await deleteVehicle.mutateAsync(vehicle.id);
      toast.success("Vehicle deleted", { description: vehicle.rego });
      navigate("/fleet?tab=vehicles");
    } catch (err) {
      toast.error("Failed to delete vehicle", { description: errorMessage(err) });
    }
  }

  const title = vehicle ? vehicle.rego : vehicleId ? `Vehicle ${vehicleId}` : "Vehicle";

  const facts: EntityFact[] = vehicle
    ? [
        { label: "Make/Model", value: [vehicle.make, vehicle.model].filter(Boolean).join(" ") || "—" },
        { label: "VIN", value: vehicle.vin || "—" },
        {
          label: "Current driver",
          value: live?.current_driver_id ? (
            <EntityLink kind="driver" id={live.current_driver_id} name={live.current_driver_name ?? live.current_driver_id} />
          ) : (
            "Unassigned"
          ),
        },
        {
          label: "Paired tablet",
          value: live?.device_id ? (
            <EntityLink kind="device" id={live.device_id} name={device?.android_id ?? live.device_id} />
          ) : (
            "Not paired"
          ),
        },
        {
          label: "Position age",
          value: live?.position_updated_at ? formatRelativeTime(live.position_updated_at) : "—",
        },
      ]
    : [];

  const statusBadge = vehicle ? (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Badge variant="outline">{VEHICLE_CLASS_OPTIONS.find((o) => o.value === vehicle.vehicle_class)?.label ?? vehicle.vehicle_class}</Badge>
      <Badge variant={vehicleStatusBadgeVariant(vehicle.status)}>{vehicle.status}</Badge>
      {live && <Badge variant={liveStatusBadgeVariant(live.live_status)}>{live.live_status}</Badge>}
    </span>
  ) : undefined;

  const actions = vehicle ? (
    <>
      <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
        <Pencil className="h-3.5 w-3.5" /> Edit
      </Button>
      <Button variant="outline" size="sm" onClick={() => setPairTarget(vehicle)}>
        <KeyRound className="h-3.5 w-3.5" /> Pair tablet
      </Button>
      <Tooltip content={live?.device_id ? "Ask this vehicle's tablet to report its location" : "No tablet paired to this vehicle"}>
        <Button variant="outline" size="sm" onClick={handleLocate} disabled={!live?.device_id || locate.isPending}>
          <MapPin className="h-3.5 w-3.5" /> {locate.isPending ? "Locating…" : "Locate"}
        </Button>
      </Tooltip>
      <Button variant="outline" size="sm" onClick={handleExportEvidencePack} disabled={exporting}>
        <Archive className="h-3.5 w-3.5" /> {exporting ? "Exporting…" : "Evidence pack"}
      </Button>
      <Tooltip
        content={
          dossierQuery.isError
            ? "Compliance dossier isn't available for this vehicle right now"
            : "Download the cl.14 compliance dossier PDF"
        }
      >
        <Button variant="outline" size="sm" onClick={handleDownloadDossier} disabled={dossierQuery.isError || dossierDownloading}>
          <FileText className="h-3.5 w-3.5" /> {dossierDownloading ? "Downloading…" : "Compliance dossier"}
        </Button>
      </Tooltip>
      <Button variant="outline" size="sm" onClick={() => setStatusTarget(vehicle)}>
        <Sliders className="h-3.5 w-3.5" /> Set status
      </Button>
      <Tooltip content={isOwner ? "Delete this vehicle" : "Only an owner can delete a vehicle"}>
        <Button variant="destructive" size="sm" onClick={() => setDeleteOpen(true)} disabled={!isOwner}>
          <Trash2 className="h-3.5 w-3.5" /> Delete
        </Button>
      </Tooltip>
    </>
  ) : undefined;

  return (
    <>
      <EntityPage
        kind="vehicle"
        title={title}
        avatar={vehicle ? <MapPin className="h-8 w-8 text-muted-foreground" aria-hidden="true" /> : undefined}
        statusBadge={statusBadge}
        facts={facts}
        actions={actions}
        backTo={{ label: "Fleet & Drivers › Vehicles", to: "/fleet?tab=vehicles" }}
        isLoading={vehicleQuery.isLoading}
        error={vehicleQuery.isError ? `Failed to load this vehicle: ${errorMessage(vehicleQuery.error)}` : undefined}
        tabs={
          vehicle
            ? [
                { value: "live", label: "Live", content: <LiveTab vehicleId={vehicle.id} /> },
                { value: "trips", label: "Trips", content: <TripsTab vehicleId={vehicle.id} /> },
                { value: "shifts", label: "Shifts", content: <ShiftsTab vehicleId={vehicle.id} /> },
                { value: "compliance", label: "Compliance", content: <ComplianceTab vehicle={vehicle} /> },
                { value: "reports", label: "Reports", content: <ReportsTab vehicle={vehicle} /> },
                { value: "tolls", label: "Tolls", content: <TollsTab vehicleId={vehicle.id} /> },
                { value: "activity", label: "Activity", content: <ActivityTab vehicleId={vehicle.id} /> },
              ]
            : undefined
        }
      />

      <EditVehicleSheet vehicle={vehicle ?? null} open={editOpen} onClose={() => setEditOpen(false)} />
      <PairTabletModal vehicle={pairTarget} onClose={() => setPairTarget(null)} />
      <SetStatusModal vehicle={statusTarget} onClose={() => setStatusTarget(null)} />

      <Modal
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title="Delete vehicle"
        description={vehicle ? `This permanently removes ${vehicle.rego}. This can't be undone.` : ""}
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteOpen(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleDelete} disabled={deleteVehicle.isPending || !isOwner}>
              Delete
            </Button>
          </>
        }
      />
    </>
  );
}
