import { useState } from "react";
import axios from "axios";
import { AlertTriangle, CheckCircle2, Flag } from "lucide-react";
import { Badge, Button, Input, Modal, Select } from "@/components/ui";
import { useCloseTripMutation, useFlagTripMutation, type Trip } from "@/hooks/useTrips";
import {
  formatDateTime,
  formatDistance,
  formatDurationSeconds,
  formatMoney,
  formatPercent,
  PAYMENT_METHOD_LABELS,
  PAYMENT_METHOD_OPTIONS,
  statusBadgeVariant,
  TIME_CLASS_LABELS,
  TRIP_TYPE_LABELS,
} from "./format";

export interface TripDetailModalProps {
  open: boolean;
  onClose: () => void;
  trip: Trip | null;
  vehicleLabel: string;
  driverLabel: string;
  onEdit: () => void;
  onDelete: () => void;
}

function FareRow({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className="flex items-center justify-between py-1 text-sm">
      <span className={muted ? "text-muted-foreground" : "text-foreground"}>{label}</span>
      <span className={muted ? "text-muted-foreground" : "font-medium text-foreground"}>{value}</span>
    </div>
  );
}

function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

/** Row-detail view: full fare breakdown plus the max-fare-check flag surfaced
 * prominently when it fails. Open trips get an inline "close trip" action
 * since fare fields are all zero until close() runs the fare engine. */
export function TripDetailModal({
  open,
  onClose,
  trip,
  vehicleLabel,
  driverLabel,
  onEdit,
  onDelete,
}: TripDetailModalProps) {
  const [closing, setClosing] = useState(false);
  const [closeError, setCloseError] = useState<string | null>(null);
  const [surchargePct, setSurchargePct] = useState("");
  const [cleaningFee, setCleaningFee] = useState("0");
  const [includePsl, setIncludePsl] = useState(false);
  const [closePaymentMethod, setClosePaymentMethod] = useState<string>("");

  const [flagging, setFlagging] = useState(false);
  const [flagReason, setFlagReason] = useState("");
  const [flagError, setFlagError] = useState<string | null>(null);

  const closeMutation = useCloseTripMutation();
  const flagMutation = useFlagTripMutation();

  if (!trip) return null;

  const variancePctNum = trip.variance_pct != null ? Number(trip.variance_pct) : null;
  const showVarianceWarning = !trip.max_fare_check_passed;

  async function handleClose() {
    setCloseError(null);
    try {
      await closeMutation.mutateAsync({
        id: trip!.id,
        input: {
          surcharge_pct: surchargePct || undefined,
          cleaning_fee: cleaningFee || "0",
          include_psl: includePsl,
          payment_method: closePaymentMethod ? (closePaymentMethod as "cash" | "card") : undefined,
        },
      });
      setClosing(false);
    } catch (err) {
      setCloseError(extractErrorMessage(err));
    }
  }

  async function handleFlag() {
    setFlagError(null);
    try {
      await flagMutation.mutateAsync({
        id: trip!.id,
        input: { flagged: true, reason: flagReason.trim() },
      });
      setFlagging(false);
      setFlagReason("");
    } catch (err) {
      setFlagError(extractErrorMessage(err));
    }
  }

  async function handleUnflag() {
    setFlagError(null);
    try {
      await flagMutation.mutateAsync({
        id: trip!.id,
        input: { flagged: false },
      });
    } catch (err) {
      setFlagError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={
        <span className="flex items-center gap-2">
          Trip {trip.id.slice(0, 8)}
          <Badge variant={statusBadgeVariant(trip.status)}>{trip.status}</Badge>
          {trip.flagged_for_review && (
            <Badge variant="destructive" className="inline-flex items-center gap-1">
              <Flag className="h-3 w-3" /> Flagged for review
            </Badge>
          )}
        </span>
      }
      description={formatDateTime(trip.start_at)}
      className="max-w-2xl"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
          {trip.status === "open" && (
            <Button variant="destructive" onClick={onDelete}>
              Delete
            </Button>
          )}
          <Button variant="secondary" onClick={onEdit}>
            Edit
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {showVarianceWarning && (
          <div className="flex items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-semibold">Max fare check failed</p>
              <p>
                Device-reported total differs from the recomputed fare by{" "}
                {formatPercent(trip.variance_pct)}
                {variancePctNum != null ? " (threshold 1.00%)" : ""}. Review before issuing a
                receipt.
              </p>
            </div>
          </div>
        )}
        {!showVarianceWarning && trip.variance_pct != null && (
          <div className="flex items-center gap-2 rounded-md border border-success bg-success/10 px-3 py-2 text-sm text-success">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            <span>Max fare check passed — variance {formatPercent(trip.variance_pct)}.</span>
          </div>
        )}

        {trip.flagged_for_review && (
          <div className="flex items-start gap-2 rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <Flag className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-semibold">Flagged for dispute review</p>
              <p>{trip.review_notes || "No reason was recorded."}</p>
            </div>
          </div>
        )}

        <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
          <div>
            <p className="text-xs text-muted-foreground">Vehicle</p>
            <p className="font-medium text-foreground">{vehicleLabel}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Driver</p>
            <p className="font-medium text-foreground">{driverLabel}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Type</p>
            <p className="font-medium text-foreground">{TRIP_TYPE_LABELS[trip.type]}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Time class</p>
            <p className="font-medium text-foreground">
              {TIME_CLASS_LABELS[trip.time_class]}
              {trip.is_peak ? " · peak" : ""}
              {trip.maxi ? " · maxi" : ""}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Payment</p>
            <p className="font-medium text-foreground">{PAYMENT_METHOD_LABELS[trip.payment_method]}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Distance / duration</p>
            <p className="font-medium text-foreground">
              {formatDistance(trip.distance_m)} · {formatDurationSeconds(trip.moving_s + trip.waiting_s)}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Started</p>
            <p className="font-medium text-foreground">{formatDateTime(trip.start_at)}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Ended</p>
            <p className="font-medium text-foreground">{formatDateTime(trip.end_at)}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Receipt</p>
            <p className="font-medium text-foreground">{trip.receipt_ref ?? "—"}</p>
          </div>
        </div>

        <div className="rounded-lg border border-border p-3">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Fare breakdown
          </p>
          <FareRow label="Flag fall" value={formatMoney(trip.flag_fall)} />
          <FareRow label="Distance" value={formatMoney(trip.dist_amount)} />
          <FareRow label="Waiting" value={formatMoney(trip.wait_amount)} />
          <FareRow label="Peak" value={formatMoney(trip.peak_amount)} />
          <FareRow label="Tolls" value={formatMoney(trip.tolls)} />
          <FareRow label="PSL" value={formatMoney(trip.psl)} />
          <FareRow label="Extras" value={formatMoney(trip.extras)} />
          <FareRow label="Subtotal" value={formatMoney(trip.subtotal)} muted />
          <FareRow label="Surcharge" value={formatMoney(trip.surcharge)} />
          <div className="my-1 border-t border-border" />
          <FareRow label="Total" value={formatMoney(trip.total)} />
          <FareRow label="GST component" value={formatMoney(trip.gst_component)} muted />
          <FareRow
            label="Variance vs device total"
            value={trip.variance_pct != null ? formatPercent(trip.variance_pct) : "n/a"}
            muted
          />
        </div>

        {trip.status === "open" && (
          <div className="rounded-lg border border-dashed border-border p-3">
            {!closing ? (
              <Button size="sm" variant="outline" onClick={() => setClosing(true)}>
                Close trip…
              </Button>
            ) : (
              <div className="space-y-2">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Close trip &amp; run fare engine
                </p>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Input
                    placeholder="Surcharge %"
                    value={surchargePct}
                    onChange={(e) => setSurchargePct(e.target.value)}
                    inputMode="decimal"
                  />
                  <Input
                    placeholder="Cleaning fee"
                    value={cleaningFee}
                    onChange={(e) => setCleaningFee(e.target.value)}
                    inputMode="decimal"
                  />
                  <Select
                    options={PAYMENT_METHOD_OPTIONS}
                    placeholder="Payment"
                    value={closePaymentMethod}
                    onChange={(e) => setClosePaymentMethod(e.target.value)}
                  />
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={includePsl}
                      onChange={(e) => setIncludePsl(e.target.checked)}
                      className="h-4 w-4 rounded border-input"
                    />
                    Include PSL
                  </label>
                </div>
                {closeError && <p className="text-sm text-destructive">{closeError}</p>}
                <div className="flex gap-2">
                  <Button size="sm" onClick={handleClose} disabled={closeMutation.isPending}>
                    {closeMutation.isPending ? "Closing…" : "Confirm close"}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setClosing(false)}>
                    Cancel
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}

        <div className="rounded-lg border border-dashed border-border p-3">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Dispute / review flag
          </p>
          {trip.flagged_for_review ? (
            <div className="space-y-2">
              <p className="text-sm text-muted-foreground">
                {trip.review_notes || "No reason was recorded."}
              </p>
              <Button
                size="sm"
                variant="outline"
                onClick={handleUnflag}
                disabled={flagMutation.isPending}
              >
                {flagMutation.isPending ? "Clearing…" : "Clear flag"}
              </Button>
            </div>
          ) : !flagging ? (
            <div className="space-y-1">
              <Button
                size="sm"
                variant="outline"
                onClick={() => setFlagging(true)}
                disabled={trip.status !== "closed"}
              >
                <Flag className="h-3.5 w-3.5" /> Flag for review…
              </Button>
              {trip.status !== "closed" && (
                <p className="text-xs text-muted-foreground">
                  Only closed trips can be flagged for dispute review.
                </p>
              )}
            </div>
          ) : (
            <div className="space-y-2">
              <Input
                placeholder="Reason for dispute (required)"
                value={flagReason}
                onChange={(e) => setFlagReason(e.target.value)}
              />
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={handleFlag}
                  disabled={flagMutation.isPending || flagReason.trim().length === 0}
                >
                  {flagMutation.isPending ? "Flagging…" : "Confirm flag"}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setFlagging(false);
                    setFlagReason("");
                    setFlagError(null);
                  }}
                >
                  Cancel
                </Button>
              </div>
            </div>
          )}
          {flagError && <p className="mt-2 text-sm text-destructive">{flagError}</p>}
        </div>
      </div>
    </Modal>
  );
}
