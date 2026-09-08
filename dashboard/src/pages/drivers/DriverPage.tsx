import { useMemo, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { Camera, Check, Copy, Play, Square, UserX } from "lucide-react";
import { EntityPage } from "@/components/layout/EntityPage";
import { EntityLink } from "@/components/EntityLink";
import { Badge, Button, Modal, Tooltip, useToast } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { errorMessage, formatDateTime } from "@/lib/format";
import { useUploadDriverPhoto, useVehicleOptions } from "@/pages/fleet/api";
import { DriverAvatar } from "@/pages/fleet/DriverAvatar";
import { StartShiftModal } from "@/pages/shifts/StartShiftModal";
import { EndShiftModal } from "@/pages/shifts/EndShiftModal";
import { useShiftsQuery } from "@/pages/shifts/api";
import { useDriverLive, useDriverUser, useUpdateDriverUser } from "./api";
import { EditDriverSheet } from "./EditDriverSheet";
import { ResetPinDialog } from "./ResetPinDialog";
import { OverviewTab } from "./tabs/OverviewTab";
import { ShiftsTab } from "./tabs/ShiftsTab";
import { TripsTab } from "./tabs/TripsTab";
import { EarningsTab } from "./tabs/EarningsTab";
import { RatingsTab } from "./tabs/RatingsTab";
import { ComplianceTab } from "./tabs/ComplianceTab";
import { MessagesTab } from "./tabs/MessagesTab";
import { ActivityTab } from "./tabs/ActivityTab";

const STAFF_ROLES = new Set(["owner", "admin", "dispatcher"]);

/** Shift/status derivation for the header badge. `DriverLiveRead` (the live
 * on-shift rollup, see `useDriverLive`) carries no "on break" flag of its
 * own -- that lives on the `Shift` row, not the driver rollup -- so the
 * three states this can honestly tell apart are on trip / on shift
 * (available) / off shift. The account's own `status` (active/inactive/
 * suspended) takes priority when it isn't "active": a suspended driver
 * being technically on_shift is a data-integrity question, not the thing an
 * operator opening this page needs to see first. */
function statusBadge(userStatus: string, live: { on_shift: boolean; current_trip_id: string | null } | undefined) {
  if (userStatus !== "active") {
    return <Badge variant="destructive">{userStatus}</Badge>;
  }
  if (!live) return <Badge variant="outline">—</Badge>;
  if (live.on_shift && live.current_trip_id) return <Badge variant="accent">On trip</Badge>;
  if (live.on_shift) return <Badge variant="success">On shift</Badge>;
  return <Badge variant="outline">Off shift</Badge>;
}

/**
 * `/drivers/:driverId` -- the driver detail page (dashboard command-centre
 * plan §4). Replaces the "Coming soon" placeholder.
 *
 * Live status: `GET /v1/drivers/{id}` polled on the ROSTER band (30s), not a
 * live socket -- see `useDriverLive`'s own doc comment for why wiring a
 * fourth consumer onto the shared realtime hub (F6) is out of scope here.
 *
 * Assign vehicle: the plan asks for this action only "if fleet.py has a
 * bind-driver-to-vehicle endpoint reachable from existing hooks" -- it does
 * not. A driver's vehicle is only ever set as a side effect of
 * `POST /v1/shifts/start` (see `backend/app/api/v1/fleet.py` / `shifts.py`),
 * so "Start shift" below is the real assign-vehicle action; a separate
 * button that could only duplicate that same call is omitted.
 */
export default function DriverPage() {
  const { driverId } = useParams<{ driverId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user } = useAuth();
  const toast = useToast();
  const photoInputRef = useRef<HTMLInputElement | null>(null);

  const canManage = user?.role === "owner" || user?.role === "admin";
  const canUploadPhoto = Boolean(user && STAFF_ROLES.has(user.role));
  const canManageShifts = Boolean(user && STAFF_ROLES.has(user.role));

  const driverUserQuery = useDriverUser(driverId ?? null);
  const driverLiveQuery = useDriverLive(driverId ?? null);
  const vehicleOptionsQuery = useVehicleOptions();
  const openShiftQuery = useShiftsQuery({ driver_id: driverId, active_only: true, limit: 1 });

  const updateDriver = useUpdateDriverUser();
  const uploadPhoto = useUploadDriverPhoto();

  const [editOpen, setEditOpen] = useState(false);
  const [resetPinOpen, setResetPinOpen] = useState(false);
  const [startShiftOpen, setStartShiftOpen] = useState(false);
  const [endShiftOpen, setEndShiftOpen] = useState(false);
  const [statusConfirmOpen, setStatusConfirmOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const driver = driverUserQuery.data;
  const live = driverLiveQuery.data;
  const openShift = openShiftQuery.data?.items?.[0] ?? null;

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicleOptionsQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehicleOptionsQuery.data]);

  // `fleet/api`'s `Vehicle` and `shifts/types`'s `VehicleLite` name the
  // status field differently (`status` vs `vehicle_status`) -- both mirror
  // the same backend column under each domain's own read model, so this is
  // a rename, not a data gap.
  const vehicleLiteOptions = useMemo(
    () =>
      (vehicleOptionsQuery.data ?? []).map((v) => ({
        id: v.id,
        rego: v.rego,
        vehicle_class: v.vehicle_class,
        vehicle_status: v.status,
      })),
    [vehicleOptionsQuery.data],
  );

  function goToMessages() {
    const next = new URLSearchParams(searchParams);
    next.set("tab", "messages");
    setSearchParams(next, { replace: true });
  }

  function handleCopyPhone() {
    if (!driver?.phone) return;
    navigator.clipboard.writeText(driver.phone).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  async function handlePhotoSelected(file: File) {
    if (!driverId) return;
    try {
      await uploadPhoto.mutateAsync({ userId: driverId, file });
      toast.success("Driver photo uploaded");
    } catch (err) {
      toast.error("Failed to upload driver photo", { description: errorMessage(err) });
    }
  }

  async function handleToggleStatus() {
    if (!driver) return;
    const nextStatus = driver.status === "active" ? "inactive" : "active";
    try {
      await updateDriver.mutateAsync({ id: driver.id, values: { status: nextStatus } });
      toast.success(nextStatus === "active" ? "Driver reactivated" : "Driver deactivated");
      setStatusConfirmOpen(false);
    } catch (err) {
      toast.error("Failed to change driver status", { description: errorMessage(err) });
    }
  }

  if (!driverId) {
    return (
      <EntityPage
        kind="driver"
        title="Driver"
        backTo={{ label: "Fleet & Drivers › Drivers", to: "/fleet?tab=drivers" }}
        error="No driver id in the URL."
      />
    );
  }

  const isLoading = driverUserQuery.isLoading;
  const loadError = driverUserQuery.isError
    ? `Failed to load this driver (GET /v1/users/${driverId}): ${errorMessage(driverUserQuery.error)}`
    : undefined;

  const disabledReason = canManage ? undefined : "Owner/admin only";

  return (
    <>
      <EntityPage
        kind="driver"
        title={driver?.name ?? `Driver ${driverId.slice(0, 8)}`}
        subtitle={driver?.driver_code ? `Driver code ${driver.driver_code}` : "Driver record"}
        avatar={driver ? <DriverAvatar userId={driver.id} name={driver.name} size="h-14 w-14" /> : undefined}
        statusBadge={driver ? statusBadge(driver.status, live) : undefined}
        isLoading={isLoading}
        error={loadError}
        backTo={{ label: "Fleet & Drivers › Drivers", to: "/fleet?tab=drivers" }}
        facts={
          driver
            ? [
                {
                  label: "Phone",
                  value: driver.phone ? (
                    <button
                      type="button"
                      onClick={handleCopyPhone}
                      className="inline-flex items-center gap-1 hover:underline"
                      aria-label="Copy phone number"
                    >
                      {driver.phone}
                      {copied ? <Check className="h-3 w-3 text-emerald-600" /> : <Copy className="h-3 w-3" />}
                    </button>
                  ) : (
                    "—"
                  ),
                },
                { label: "On shift since", value: live?.shift_start_at ? formatDateTime(live.shift_start_at) : "—" },
                {
                  label: "Current vehicle",
                  value: live?.vehicle_id ? (
                    <EntityLink
                      kind="vehicle"
                      id={live.vehicle_id}
                      name={vehicleRegoById.get(live.vehicle_id) ?? live.vehicle_id.slice(0, 8)}
                    />
                  ) : (
                    "—"
                  ),
                },
                {
                  label: "Current trip",
                  value: live?.current_trip_id ? (
                    <EntityLink kind="trip" id={live.current_trip_id} name={live.current_trip_id.slice(0, 8)} />
                  ) : (
                    "—"
                  ),
                },
              ]
            : []
        }
        actions={
          driver && (
            <>
              <Button variant="outline" size="sm" onClick={goToMessages}>
                Message
              </Button>

              <Tooltip content={disabledReason ?? "Edit this driver"}>
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)} disabled={!canManage}>
                  Edit
                </Button>
              </Tooltip>

              {canManageShifts &&
                (live?.on_shift ? (
                  <Button variant="outline" size="sm" onClick={() => setEndShiftOpen(true)} disabled={!openShift}>
                    <Square className="h-3.5 w-3.5" /> End shift
                  </Button>
                ) : (
                  <Button variant="outline" size="sm" onClick={() => setStartShiftOpen(true)}>
                    <Play className="h-3.5 w-3.5" /> Start shift
                  </Button>
                ))}

              <Tooltip content={disabledReason ?? "Mint a new meter PIN for this driver"}>
                <Button variant="outline" size="sm" onClick={() => setResetPinOpen(true)} disabled={!canManage}>
                  Reset meter PIN
                </Button>
              </Tooltip>

              <Tooltip
                content={
                  disabledReason ?? (driver.status === "active" ? "Deactivate this driver" : "Reactivate this driver")
                }
              >
                <Button
                  variant={driver.status === "active" ? "destructive" : "outline"}
                  size="sm"
                  onClick={() => setStatusConfirmOpen(true)}
                  disabled={!canManage}
                >
                  <UserX className="h-3.5 w-3.5" /> {driver.status === "active" ? "Deactivate" : "Reactivate"}
                </Button>
              </Tooltip>

              {canUploadPhoto && (
                <>
                  <input
                    ref={photoInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      e.target.value = "";
                      if (file) handlePhotoSelected(file);
                    }}
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={uploadPhoto.isPending}
                    onClick={() => photoInputRef.current?.click()}
                  >
                    <Camera className="h-3.5 w-3.5" /> {uploadPhoto.isPending ? "Uploading…" : "Upload photo"}
                  </Button>
                </>
              )}
            </>
          )
        }
        tabs={
          driver
            ? [
                { value: "overview", label: "Overview", content: <OverviewTab driverId={driver.id} /> },
                {
                  value: "shifts",
                  label: "Shifts",
                  content: <ShiftsTab driverId={driver.id} canRecordBreak={canManageShifts} />,
                },
                { value: "trips", label: "Trips", content: <TripsTab driverId={driver.id} /> },
                {
                  value: "earnings",
                  label: "Earnings & wallet",
                  content: <EarningsTab driver={driver} canAdjustWallet={canManage} />,
                },
                { value: "ratings", label: "Ratings", content: <RatingsTab driverId={driver.id} /> },
                {
                  value: "compliance",
                  label: "Compliance",
                  content: <ComplianceTab driverId={driver.id} canEdit={canManage} />,
                },
                {
                  value: "messages",
                  label: "Messages",
                  content: (
                    <MessagesTab
                      id={driver.id}
                      name={driver.name}
                      phone={driver.phone}
                      onShift={live?.on_shift ?? false}
                    />
                  ),
                },
                { value: "activity", label: "Activity", content: <ActivityTab driverId={driver.id} /> },
              ]
            : []
        }
      />

      <EditDriverSheet driver={driver ?? null} open={editOpen} onClose={() => setEditOpen(false)} />
      <ResetPinDialog
        driverId={driver?.id ?? null}
        driverName={driver?.name ?? ""}
        open={resetPinOpen}
        onClose={() => setResetPinOpen(false)}
      />
      {driver && (
        <StartShiftModal
          open={startShiftOpen}
          onClose={() => setStartShiftOpen(false)}
          drivers={[{ id: driver.id, name: driver.name, phone: driver.phone, user_status: driver.status, on_shift: false }]}
          vehicles={vehicleLiteOptions}
        />
      )}
      <EndShiftModal shift={openShift} open={endShiftOpen && Boolean(openShift)} onClose={() => setEndShiftOpen(false)} />

      <Modal
        open={statusConfirmOpen}
        onClose={() => setStatusConfirmOpen(false)}
        title={driver?.status === "active" ? "Deactivate driver" : "Reactivate driver"}
        description={
          driver?.status === "active"
            ? `${driver?.name} will no longer be able to sign in to the meter app.`
            : `${driver?.name} will be able to sign in to the meter app again.`
        }
        footer={
          <>
            <Button variant="outline" onClick={() => setStatusConfirmOpen(false)}>
              Cancel
            </Button>
            <Button
              variant={driver?.status === "active" ? "destructive" : "primary"}
              onClick={handleToggleStatus}
              disabled={updateDriver.isPending}
            >
              {updateDriver.isPending ? "Saving…" : driver?.status === "active" ? "Deactivate" : "Reactivate"}
            </Button>
          </>
        }
      />
    </>
  );
}
