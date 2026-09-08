import { useState } from "react";
import { useParams } from "react-router-dom";
import { Download, Pencil, Square } from "lucide-react";
import { EntityPage } from "@/components/layout/EntityPage";
import { EntityLink } from "@/components/EntityLink";
import { Badge, Button, Tooltip } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { errorMessage, formatDateTime, formatDurationMinutes } from "@/lib/format";
import { useDriverUser } from "@/pages/drivers/api";
import { useVehicle } from "@/pages/fleet/api";
import {
  downloadShiftReportCsv,
  downloadShiftReportPdf,
  useDriversLookupQuery,
  useShiftQuery,
  useUpdateShiftMutation,
  useVehiclesLookupQuery,
} from "./api";
import { EditShiftModal } from "./EditShiftModal";
import { EndShiftModal } from "./EndShiftModal";
import { reconciledBadgeVariant, shiftStatusBadgeVariant, shiftStatusLabel } from "./format";
import { SummaryTab } from "./tabs/SummaryTab";
import { TripsTab } from "./tabs/TripsTab";
import { BreaksTab } from "./tabs/BreaksTab";
import { TimelineTab } from "./tabs/TimelineTab";
import { ReconciliationTab } from "./tabs/ReconciliationTab";

const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);

function durationMinutes(startAt: string, endAt: string | null): number {
  const start = new Date(startAt).getTime();
  const end = endAt ? new Date(endAt).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return 0;
  return (end - start) / 60000;
}

/**
 * `/shifts/:shiftId` -- the shift detail page (dashboard command-centre
 * plan §7). Replaces the "Coming soon" placeholder and `ShiftReportModal`
 * -- the PDF/CSV download functions and the report figures are reused
 * as-is (`downloadShiftReportPdf`/`downloadShiftReportCsv`, `SummaryTab`),
 * not rewritten. Permission gating follows the Shifts list page's own
 * `MANAGE_ROLES` (owner/admin/dispatcher) for Edit/End/reconcile, same
 * convention that page already used.
 */
export default function ShiftPage() {
  const { shiftId } = useParams<{ shiftId: string }>();
  const { user } = useAuth();
  const canManage = Boolean(user && MANAGE_ROLES.has(user.role));
  const disabledReason = canManage ? undefined : "Owner/admin/dispatcher only";

  const shiftQuery = useShiftQuery(shiftId ?? null);
  const shift = shiftQuery.data;

  const driverQuery = useDriverUser(shift?.driver_id ?? null);
  const vehicleQuery = useVehicle(shift?.vehicle_id ?? null);
  const driversQuery = useDriversLookupQuery();
  const vehiclesQuery = useVehiclesLookupQuery();
  const updateMutation = useUpdateShiftMutation();

  const [editOpen, setEditOpen] = useState(false);
  const [endOpen, setEndOpen] = useState(false);
  const [downloading, setDownloading] = useState<"pdf" | "csv" | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [reconcileError, setReconcileError] = useState<string | null>(null);

  if (!shiftId) {
    return (
      <EntityPage kind="shift" title="Shift" backTo={{ label: "Shifts", to: "/shifts" }} error="No shift id in the URL." />
    );
  }

  const isLoading = shiftQuery.isLoading;
  const loadError = shiftQuery.isError
    ? `Failed to load this shift (GET /v1/shifts/${shiftId}): ${errorMessage(shiftQuery.error)}`
    : undefined;

  async function handleDownload(kind: "pdf" | "csv") {
    if (!shift) return;
    setDownloadError(null);
    setDownloading(kind);
    try {
      if (kind === "pdf") await downloadShiftReportPdf(shift.id);
      else await downloadShiftReportCsv(shift.id);
    } catch {
      setDownloadError(`Could not download the ${kind.toUpperCase()}. Try again.`);
    } finally {
      setDownloading(null);
    }
  }

  async function handleToggleReconciled() {
    if (!shift) return;
    setReconcileError(null);
    try {
      await updateMutation.mutateAsync({ id: shift.id, body: { reconciled: !shift.reconciled } });
    } catch (err) {
      setReconcileError(errorMessage(err));
    }
  }

  return (
    <>
      <EntityPage
        kind="shift"
        title={shift ? `Shift ${shift.id.slice(0, 8)}` : `Shift ${shiftId.slice(0, 8)}`}
        subtitle={shift ? formatDateTime(shift.start_at) : "Shift record"}
        isLoading={isLoading}
        error={loadError}
        backTo={{ label: "Shifts", to: "/shifts" }}
        statusBadge={
          shift ? (
            <div className="flex items-center gap-1.5">
              <Badge variant={shiftStatusBadgeVariant(shift)}>{shiftStatusLabel(shift)}</Badge>
              <Badge variant={reconciledBadgeVariant(shift.reconciled)}>
                {shift.reconciled ? "Reconciled" : "Not reconciled"}
              </Badge>
            </div>
          ) : undefined
        }
        facts={
          shift
            ? [
                {
                  label: "Driver",
                  value: (
                    <EntityLink
                      kind="driver"
                      id={shift.driver_id}
                      name={driverQuery.data?.name ?? shift.driver_id.slice(0, 8)}
                    />
                  ),
                },
                {
                  label: "Vehicle",
                  value: (
                    <EntityLink
                      kind="vehicle"
                      id={shift.vehicle_id}
                      name={vehicleQuery.data?.rego ?? shift.vehicle_id.slice(0, 8)}
                    />
                  ),
                },
                { label: "Started", value: formatDateTime(shift.start_at) },
                { label: "Ended", value: shift.end_at ? formatDateTime(shift.end_at) : "—" },
                {
                  label: "Duration",
                  value: formatDurationMinutes(durationMinutes(shift.start_at, shift.end_at)),
                },
              ]
            : []
        }
        actions={
          shift && (
            <>
              {!shift.end_at && (
                <Tooltip content={disabledReason ?? "End this shift"}>
                  <Button variant="outline" size="sm" onClick={() => setEndOpen(true)} disabled={!canManage}>
                    <Square className="h-3.5 w-3.5" /> End shift
                  </Button>
                </Tooltip>
              )}

              <Tooltip content={disabledReason ?? "Edit this shift"}>
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)} disabled={!canManage}>
                  <Pencil className="h-3.5 w-3.5" /> Edit
                </Button>
              </Tooltip>

              <Button
                variant="outline"
                size="sm"
                disabled={downloading != null}
                onClick={() => handleDownload("csv")}
              >
                <Download className="h-3.5 w-3.5" /> {downloading === "csv" ? "Downloading…" : "CSV"}
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={downloading != null}
                onClick={() => handleDownload("pdf")}
              >
                <Download className="h-3.5 w-3.5" /> {downloading === "pdf" ? "Downloading…" : "PDF"}
              </Button>

              <Tooltip content={disabledReason ?? (shift.reconciled ? "Mark not reconciled" : "Mark reconciled")}>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!canManage || updateMutation.isPending}
                  onClick={handleToggleReconciled}
                >
                  {updateMutation.isPending
                    ? "Saving…"
                    : shift.reconciled
                      ? "Mark not reconciled"
                      : "Mark reconciled"}
                </Button>
              </Tooltip>
            </>
          )
        }
        tabs={
          shift
            ? [
                { value: "summary", label: "Summary", content: <SummaryTab shiftId={shift.id} /> },
                { value: "trips", label: "Trips", content: <TripsTab shift={shift} /> },
                { value: "breaks", label: "Breaks", content: <BreaksTab shift={shift} canRecord={canManage} /> },
                { value: "timeline", label: "Timeline", content: <TimelineTab shift={shift} /> },
                {
                  value: "reconciliation",
                  label: "Reconciliation",
                  content: <ReconciliationTab shiftId={shift.id} canReconcile={canManage} />,
                },
              ]
            : []
        }
      />

      {downloadError && <p className="text-sm text-destructive">{downloadError}</p>}
      {reconcileError && <p className="text-sm text-destructive">{reconcileError}</p>}

      {shift && (
        <>
          <EditShiftModal
            shift={shift}
            open={editOpen}
            onClose={() => setEditOpen(false)}
            drivers={driversQuery.data ?? []}
            vehicles={vehiclesQuery.data ?? []}
          />
          <EndShiftModal shift={shift} open={endOpen} onClose={() => setEndOpen(false)} />
        </>
      )}
    </>
  );
}
