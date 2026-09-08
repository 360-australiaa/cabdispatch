import { useState } from "react";
import { useParams } from "react-router-dom";
import { Flag, Lock, Pencil, Trash2 } from "lucide-react";
import { EntityPage } from "@/components/layout/EntityPage";
import { EntityLink } from "@/components/EntityLink";
import { Badge, Button, Modal, Tooltip } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { errorMessage, formatDateTime } from "@/lib/format";
import {
  useDeleteTripMutation,
  useDriversLookupQuery,
  useFlagTripMutation,
  useTariffsLookupQuery,
  useTripQuery,
  useVehiclesLookupQuery,
} from "@/hooks/useTrips";
import { useDriverUser } from "@/pages/drivers/api";
import { useVehicle } from "@/pages/fleet/api";
import { CloseTripSheet } from "./CloseTripSheet";
import { FlagTripModal } from "./FlagTripModal";
import { TripFormModal } from "./TripFormModal";
import { PAYMENT_METHOD_LABELS, statusBadgeVariant } from "./format";
import { FareTab } from "./tabs/FareTab";
import { RouteTab } from "./tabs/RouteTab";
import { PaymentsTab } from "./tabs/PaymentsTab";
import { ReceiptTab } from "./tabs/ReceiptTab";
import { RatingTab } from "./tabs/RatingTab";
import { ActivityTab } from "./tabs/ActivityTab";

/**
 * `/trips/:tripId` -- the trip detail page (dashboard command-centre plan
 * §7). Replaces the "Coming soon" placeholder and `TripDetailModal` (ported
 * into this page's tabs/actions rather than rewritten -- see each tab
 * file's own doc comment for exactly what moved where and what's new).
 *
 * Permission gating: `TripDetailModal` itself never disabled Edit/Delete/
 * Flag by role (the backend enforces server-side; the dashboard-wide F5
 * permissions matrix hasn't landed yet). This page follows the same
 * owner/admin convention `DriverPage`/`VehiclePage` already use for their
 * own destructive actions -- a dispatcher sees Edit/Delete disabled with a
 * tooltip rather than a live but silently-403ing button. Close and Flag
 * stay enabled for a dispatcher (day-to-day trip handling, not a
 * destructive/financial-record action), matching `TripDetailModal`'s own
 * posture of gating those only on trip status, not role.
 */
export default function TripPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const { user } = useAuth();
  const canManage = user?.role === "owner" || user?.role === "admin";
  const disabledReason = canManage ? undefined : "Owner/admin only";

  const tripQuery = useTripQuery(tripId ?? null);
  const trip = tripQuery.data;

  const driverQuery = useDriverUser(trip?.driver_id ?? null);
  const vehicleQuery = useVehicle(trip?.vehicle_id ?? null);

  const vehiclesQuery = useVehiclesLookupQuery();
  const driversQuery = useDriversLookupQuery();
  const tariffsQuery = useTariffsLookupQuery();

  const deleteMutation = useDeleteTripMutation();
  const flagMutation = useFlagTripMutation();

  const [closeOpen, setCloseOpen] = useState(false);
  const [flagOpen, setFlagOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  if (!tripId) {
    return (
      <EntityPage kind="trip" title="Trip" backTo={{ label: "Trips", to: "/trips" }} error="No trip id in the URL." />
    );
  }

  const isLoading = tripQuery.isLoading;
  const loadError = tripQuery.isError
    ? `Failed to load this trip (GET /v1/trips/${tripId}): ${errorMessage(tripQuery.error)}`
    : undefined;

  async function handleUnflag() {
    if (!trip) return;
    try {
      await flagMutation.mutateAsync({ id: trip.id, input: { flagged: false } });
    } catch {
      // No dedicated error slot in the action bar for this -- a failed clear
      // simply leaves the flag showing, which is an honest outcome on its own.
    }
  }

  async function handleDelete() {
    if (!trip) return;
    setDeleteError(null);
    try {
      await deleteMutation.mutateAsync(trip.id);
      setDeleteOpen(false);
    } catch (err) {
      setDeleteError(errorMessage(err));
    }
  }

  return (
    <>
      <EntityPage
        kind="trip"
        title={trip ? `Trip ${trip.id.slice(0, 8)}` : `Trip ${tripId.slice(0, 8)}`}
        subtitle={trip ? formatDateTime(trip.start_at) : "Trip record"}
        isLoading={isLoading}
        error={loadError}
        backTo={{ label: "Trips", to: "/trips" }}
        statusBadge={
          trip ? (
            <div className="flex items-center gap-1.5">
              <Badge variant={statusBadgeVariant(trip.status)}>{trip.status}</Badge>
              {trip.flagged_for_review && (
                <Badge variant="destructive" className="inline-flex items-center gap-1">
                  <Flag className="h-3 w-3" /> Flagged for review
                </Badge>
              )}
              {!trip.max_fare_check_passed && trip.status === "closed" && (
                <Badge variant="destructive">Fare check failed</Badge>
              )}
            </div>
          ) : undefined
        }
        facts={
          trip
            ? [
                {
                  label: "Driver",
                  value: (
                    <EntityLink
                      kind="driver"
                      id={trip.driver_id}
                      name={driverQuery.data?.name ?? trip.driver_id.slice(0, 8)}
                    />
                  ),
                },
                {
                  label: "Vehicle",
                  value: (
                    <EntityLink
                      kind="vehicle"
                      id={trip.vehicle_id}
                      name={vehicleQuery.data?.rego ?? trip.vehicle_id.slice(0, 8)}
                    />
                  ),
                },
                { label: "Started", value: formatDateTime(trip.start_at) },
                { label: "Ended", value: trip.end_at ? formatDateTime(trip.end_at) : "—" },
                { label: "Payment", value: PAYMENT_METHOD_LABELS[trip.payment_method] },
              ]
            : []
        }
        actions={
          trip && (
            <>
              {trip.status === "open" && (
                <Button variant="outline" size="sm" onClick={() => setCloseOpen(true)}>
                  Close trip…
                </Button>
              )}

              {trip.flagged_for_review ? (
                <Button variant="outline" size="sm" onClick={handleUnflag} disabled={flagMutation.isPending}>
                  {flagMutation.isPending ? "Clearing…" : "Clear flag"}
                </Button>
              ) : (
                <Tooltip
                  content={trip.status !== "closed" ? "Only closed trips can be flagged" : "Flag for dispute review"}
                >
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setFlagOpen(true)}
                    disabled={trip.status !== "closed"}
                  >
                    <Flag className="h-3.5 w-3.5" /> Flag
                  </Button>
                </Tooltip>
              )}

              <Tooltip content={disabledReason ?? "Edit this trip"}>
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)} disabled={!canManage}>
                  <Pencil className="h-3.5 w-3.5" /> Edit
                </Button>
              </Tooltip>

              <Tooltip
                content={
                  disabledReason ??
                  (trip.status === "open"
                    ? "Delete this trip"
                    : "Closed trips are financial records and cannot be deleted")
                }
              >
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => setDeleteOpen(true)}
                  disabled={!canManage || trip.status !== "open"}
                >
                  {trip.status !== "open" && !canManage ? (
                    <Lock className="h-3.5 w-3.5" />
                  ) : (
                    <Trash2 className="h-3.5 w-3.5" />
                  )}
                  Delete
                </Button>
              </Tooltip>
            </>
          )
        }
        tabs={
          trip
            ? [
                { value: "fare", label: "Fare", content: <FareTab trip={trip} /> },
                { value: "route", label: "Route", content: <RouteTab trip={trip} /> },
                { value: "payments", label: "Payments", content: <PaymentsTab tripId={trip.id} /> },
                { value: "receipt", label: "Receipt", content: <ReceiptTab trip={trip} /> },
                {
                  value: "rating",
                  label: "Rating",
                  content: <RatingTab tripId={trip.id} driverId={trip.driver_id} />,
                },
                { value: "audit", label: "Audit", content: <ActivityTab tripId={trip.id} /> },
              ]
            : []
        }
      />

      {trip && (
        <>
          <CloseTripSheet open={closeOpen} onClose={() => setCloseOpen(false)} trip={trip} />
          <FlagTripModal open={flagOpen} onClose={() => setFlagOpen(false)} trip={trip} />
          <TripFormModal
            open={editOpen}
            onClose={() => setEditOpen(false)}
            mode="edit"
            trip={trip}
            vehicles={vehiclesQuery.data ?? []}
            drivers={driversQuery.data ?? []}
            tariffs={tariffsQuery.data ?? []}
          />
          <Modal
            open={deleteOpen}
            onClose={() => {
              setDeleteOpen(false);
              setDeleteError(null);
            }}
            title="Delete trip?"
            description="This only works while the trip is still open — closed trips are financial records and cannot be deleted."
            footer={
              <>
                <Button
                  variant="outline"
                  onClick={() => {
                    setDeleteOpen(false);
                    setDeleteError(null);
                  }}
                >
                  Cancel
                </Button>
                <Button variant="destructive" disabled={deleteMutation.isPending} onClick={handleDelete}>
                  {deleteMutation.isPending ? "Deleting…" : "Delete"}
                </Button>
              </>
            }
          >
            <p className="text-sm text-muted-foreground">
              Trip {trip.id.slice(0, 8)} started {formatDateTime(trip.start_at)} will be permanently removed.
            </p>
            {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
          </Modal>
        </>
      )}
    </>
  );
}
