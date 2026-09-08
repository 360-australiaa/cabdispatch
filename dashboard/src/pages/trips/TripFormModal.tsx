import { useEffect, useState } from "react";
import axios from "axios";
import { Plus, Trash2 } from "lucide-react";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useCreateTripMutation,
  useUpdateTripMutation,
  type DriverLite,
  type PaymentMethod,
  type SplitPaymentItem,
  type TariffLite,
  type TimeClass,
  type Trip,
  type TripType,
  type VehicleLite,
} from "@/hooks/useTrips";
import { PAYMENT_METHOD_OPTIONS, TIME_CLASS_OPTIONS, TRIP_TYPE_OPTIONS } from "./format";

/** Payment methods that need a supporting field beyond the plain total. */
function needsVoucherCode(method: PaymentMethod) {
  return method === "voucher";
}
function needsAccountReference(method: PaymentMethod) {
  return method === "account";
}
function needsSplitPayments(method: PaymentMethod) {
  return method === "split_fare";
}

export interface TripFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  trip?: Trip;
  vehicles: VehicleLite[];
  drivers: DriverLite[];
  tariffs: TariffLite[];
}

interface FormState {
  vehicle_id: string;
  driver_id: string;
  tariff_id: string;
  type: TripType;
  payment_method: PaymentMethod;
  time_class: TimeClass;
  is_peak: boolean;
  // The real maxi-rate triggers (Order cl 2(d)) — replace the old raw "maxi"
  // checkbox, which the backend now ignores entirely for billing (it derives
  // is_maxi_vehicle itself from the vehicle's real fleet-domain
  // vehicle_class; see app/services/trips.py::resolve_is_maxi_vehicle). A
  // checkbox that visibly did nothing server-side was actively misleading.
  passenger_count: string;
  wheelchair_hiring: boolean;
  airport_rank_requested_maxi: boolean;
  voucher_code: string;
  account_reference: string;
  split_payments: SplitPaymentItem[];
  start_lat: string;
  start_lng: string;
  end_lat: string;
  end_lng: string;
  tolls: string;
  extras: string;
  gps_trace_ref: string;
  receipt_ref: string;
}

function emptyForm(): FormState {
  return {
    vehicle_id: "",
    driver_id: "",
    tariff_id: "",
    type: "rank_hail",
    payment_method: "cash",
    time_class: "day",
    is_peak: false,
    passenger_count: "1",
    wheelchair_hiring: false,
    airport_rank_requested_maxi: false,
    voucher_code: "",
    account_reference: "",
    split_payments: [],
    start_lat: "-33.8688",
    start_lng: "151.2093",
    end_lat: "",
    end_lng: "",
    tolls: "0",
    extras: "0",
    gps_trace_ref: "",
    receipt_ref: "",
  };
}

function formFromTrip(trip: Trip): FormState {
  return {
    vehicle_id: trip.vehicle_id,
    driver_id: trip.driver_id,
    tariff_id: trip.tariff_id,
    type: trip.type,
    payment_method: trip.payment_method,
    time_class: trip.time_class,
    is_peak: trip.is_peak,
    passenger_count: String(trip.passenger_count ?? 1),
    wheelchair_hiring: trip.wheelchair_hiring ?? false,
    airport_rank_requested_maxi: trip.airport_rank_requested_maxi ?? false,
    voucher_code: trip.voucher_code ?? "",
    account_reference: trip.account_reference ?? "",
    split_payments: trip.split_payments ?? [],
    start_lat: String(trip.start_lat),
    start_lng: String(trip.start_lng),
    end_lat: trip.end_lat != null ? String(trip.end_lat) : "",
    end_lng: trip.end_lng != null ? String(trip.end_lng) : "",
    tolls: trip.tolls,
    extras: trip.extras,
    gps_trace_ref: trip.gps_trace_ref ?? "",
    receipt_ref: trip.receipt_ref ?? "",
  };
}

function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      return detail.map((d) => d.msg ?? JSON.stringify(d)).join("; ");
    }
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

/** Create/edit modal for a trip. Create posts the full TripCreate shape;
 * edit only sends the fields TripUpdate accepts (no type/start position —
 * those are immutable once a trip exists). */
export function TripFormModal({
  open,
  onClose,
  mode,
  trip,
  vehicles,
  drivers,
  tariffs,
}: TripFormModalProps) {
  const [form, setForm] = useState<FormState>(trip ? formFromTrip(trip) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateTripMutation();
  const updateMutation = useUpdateTripMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(trip ? formFromTrip(trip) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, trip?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  /** Shared cross-field check for whichever payment method is selected —
   * mirrors the backend's own `_validate_voucher_and_account`/
   * `_validate_split_payments_required` (schemas/trips.py) so a bad
   * combination is caught before the request round-trip. */
  function validatePaymentFields(): string | null {
    if (needsVoucherCode(form.payment_method) && !form.voucher_code.trim()) {
      return "Voucher code is required for the voucher payment method.";
    }
    if (needsAccountReference(form.payment_method) && !form.account_reference.trim()) {
      return "Account reference is required for the account payment method.";
    }
    if (needsSplitPayments(form.payment_method)) {
      if (form.split_payments.length < 2) {
        return "Split fare needs at least two payment legs.";
      }
      for (const leg of form.split_payments) {
        if (!leg.method.trim() || !leg.amount.trim() || Number.isNaN(Number(leg.amount))) {
          return "Every split-fare leg needs a method and a valid amount.";
        }
      }
    }
    return null;
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    const paymentError = validatePaymentFields();
    if (paymentError) {
      setError(paymentError);
      return;
    }

    if (mode === "create") {
      const lat = Number(form.start_lat);
      const lng = Number(form.start_lng);
      const passengerCount = Number(form.passenger_count);
      if (!form.vehicle_id || !form.driver_id || !form.tariff_id) {
        setError("Vehicle, driver and tariff are required.");
        return;
      }
      if (Number.isNaN(lat) || Number.isNaN(lng)) {
        setError("Start latitude/longitude must be numbers.");
        return;
      }
      if (!Number.isInteger(passengerCount) || passengerCount < 1 || passengerCount > 11) {
        setError("Passenger count must be a whole number from 1 to 11.");
        return;
      }
      try {
        await createMutation.mutateAsync({
          client_uuid: crypto.randomUUID(),
          vehicle_id: form.vehicle_id,
          driver_id: form.driver_id,
          tariff_id: form.tariff_id,
          type: form.type,
          payment_method: form.payment_method,
          time_class: form.time_class,
          is_peak: form.is_peak,
          passenger_count: passengerCount,
          wheelchair_hiring: form.wheelchair_hiring,
          airport_rank_requested_maxi: form.airport_rank_requested_maxi,
          voucher_code: needsVoucherCode(form.payment_method) ? form.voucher_code.trim() : null,
          account_reference: needsAccountReference(form.payment_method)
            ? form.account_reference.trim()
            : null,
          start_lat: lat,
          start_lng: lng,
          tolls: form.tolls || "0",
          extras: form.extras || "0",
          gps_trace_ref: form.gps_trace_ref || null,
        });
        onClose();
      } catch (err) {
        setError(extractErrorMessage(err));
      }
    } else {
      if (!trip) return;
      const end_lat = form.end_lat.trim() === "" ? null : Number(form.end_lat);
      const end_lng = form.end_lng.trim() === "" ? null : Number(form.end_lng);
      if ((end_lat != null && Number.isNaN(end_lat)) || (end_lng != null && Number.isNaN(end_lng))) {
        setError("End latitude/longitude must be numbers.");
        return;
      }
      try {
        await updateMutation.mutateAsync({
          id: trip.id,
          input: {
            vehicle_id: form.vehicle_id || null,
            driver_id: form.driver_id || null,
            tariff_id: form.tariff_id || null,
            payment_method: form.payment_method,
            voucher_code: needsVoucherCode(form.payment_method) ? form.voucher_code.trim() : null,
            account_reference: needsAccountReference(form.payment_method)
              ? form.account_reference.trim()
              : null,
            split_payments: needsSplitPayments(form.payment_method) ? form.split_payments : null,
            tolls: form.tolls || "0",
            extras: form.extras || "0",
            gps_trace_ref: form.gps_trace_ref || null,
            receipt_ref: form.receipt_ref || null,
            end_lat,
            end_lng,
          },
        });
        onClose();
      } catch (err) {
        setError(extractErrorMessage(err));
      }
    }
  }

  function addSplitLeg() {
    setForm((f) => ({ ...f, split_payments: [...f.split_payments, { method: "cash", amount: "" }] }));
  }
  function removeSplitLeg(index: number) {
    setForm((f) => ({ ...f, split_payments: f.split_payments.filter((_, i) => i !== index) }));
  }
  function updateSplitLeg(index: number, patch: Partial<SplitPaymentItem>) {
    setForm((f) => ({
      ...f,
      split_payments: f.split_payments.map((leg, i) => (i === index ? { ...leg, ...patch } : leg)),
    }));
  }

  const vehicleOptions = vehicles.map((v) => ({ value: v.id, label: v.rego }));
  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));
  const tariffOptions = tariffs.map((t) => ({
    value: t.id,
    label: `${t.name} (${t.region}${t.booked ? ", booked" : ""})`,
  }));

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "New trip" : `Edit trip ${trip?.id.slice(0, 8)}`}
      description={
        mode === "create"
          ? "Starts an open meter run. Fare fields populate once the trip is closed."
          : "Only fields the backend allows post-creation are editable here."
      }
      className="max-w-2xl"
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="trip-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create trip" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="trip-form" onSubmit={handleSubmit} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Vehicle</label>
          <Select
            options={vehicleOptions}
            placeholder="Select vehicle"
            value={form.vehicle_id}
            onChange={(e) => update("vehicle_id", e.target.value)}
            required
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Driver</label>
          <Select
            options={driverOptions}
            placeholder="Select driver"
            value={form.driver_id}
            onChange={(e) => update("driver_id", e.target.value)}
            required
          />
        </div>
        <div className="flex flex-col gap-1 sm:col-span-2">
          <label className="text-xs font-medium text-muted-foreground">Tariff</label>
          <Select
            options={tariffOptions}
            placeholder="Select tariff"
            value={form.tariff_id}
            onChange={(e) => update("tariff_id", e.target.value)}
            required
          />
        </div>

        {mode === "create" && (
          <>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Trip type</label>
              <Select
                options={TRIP_TYPE_OPTIONS}
                value={form.type}
                onChange={(e) => update("type", e.target.value as TripType)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Time class</label>
              <Select
                options={TIME_CLASS_OPTIONS}
                value={form.time_class}
                onChange={(e) => update("time_class", e.target.value as TimeClass)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Start latitude</label>
              <Input
                value={form.start_lat}
                onChange={(e) => update("start_lat", e.target.value)}
                inputMode="decimal"
                required
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Start longitude</label>
              <Input
                value={form.start_lng}
                onChange={(e) => update("start_lng", e.target.value)}
                inputMode="decimal"
                required
              />
            </div>
            <div className="flex items-center gap-2 pt-5">
              <input
                id="is_peak"
                type="checkbox"
                checked={form.is_peak}
                onChange={(e) => update("is_peak", e.target.checked)}
                className="h-4 w-4 rounded border-input"
              />
              <label htmlFor="is_peak" className="text-sm text-foreground">
                Peak surcharge applies
              </label>
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Passenger count</label>
              <Input
                type="number"
                min={1}
                max={11}
                value={form.passenger_count}
                onChange={(e) => update("passenger_count", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                5+ triggers the maxi rate, but only on a vehicle whose real fleet vehicle_class is
                "maxi" — this never applies by request alone.
              </p>
            </div>
            <div className="flex items-center gap-2 pt-5">
              <input
                id="wheelchair_hiring"
                type="checkbox"
                checked={form.wheelchair_hiring}
                onChange={(e) => update("wheelchair_hiring", e.target.checked)}
                className="h-4 w-4 rounded border-input"
              />
              <label htmlFor="wheelchair_hiring" className="text-sm text-foreground">
                Wheelchair hiring (always overrides the maxi rate off)
              </label>
            </div>
            <div className="flex items-center gap-2 pt-5">
              <input
                id="airport_rank_requested_maxi"
                type="checkbox"
                checked={form.airport_rank_requested_maxi}
                onChange={(e) => update("airport_rank_requested_maxi", e.target.checked)}
                className="h-4 w-4 rounded border-input"
              />
              <label htmlFor="airport_rank_requested_maxi" className="text-sm text-foreground">
                Maxi specifically requested at a Sydney Airport rank
              </label>
            </div>
          </>
        )}

        {mode === "edit" && (
          <>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">End latitude</label>
              <Input
                value={form.end_lat}
                onChange={(e) => update("end_lat", e.target.value)}
                inputMode="decimal"
                placeholder="Optional"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">End longitude</label>
              <Input
                value={form.end_lng}
                onChange={(e) => update("end_lng", e.target.value)}
                inputMode="decimal"
                placeholder="Optional"
              />
            </div>
            <div className="flex flex-col gap-1 sm:col-span-2">
              <label className="text-xs font-medium text-muted-foreground">Receipt reference</label>
              <Input
                value={form.receipt_ref}
                onChange={(e) => update("receipt_ref", e.target.value)}
                placeholder="Optional"
              />
            </div>
          </>
        )}

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Payment method</label>
          <Select
            options={PAYMENT_METHOD_OPTIONS}
            value={form.payment_method}
            onChange={(e) => update("payment_method", e.target.value as PaymentMethod)}
          />
        </div>
        {needsVoucherCode(form.payment_method) ? (
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Voucher code</label>
            <Input value={form.voucher_code} onChange={(e) => update("voucher_code", e.target.value)} required />
          </div>
        ) : needsAccountReference(form.payment_method) ? (
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Account reference</label>
            <Input
              value={form.account_reference}
              onChange={(e) => update("account_reference", e.target.value)}
              required
            />
          </div>
        ) : (
          <div />
        )}
        {needsSplitPayments(form.payment_method) && (
          <div className="flex flex-col gap-2 sm:col-span-2">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium text-muted-foreground">
                Split-fare legs (must sum to the trip total)
              </label>
              <Button type="button" variant="outline" size="sm" onClick={addSplitLeg}>
                <Plus className="h-3.5 w-3.5" /> Add leg
              </Button>
            </div>
            {form.split_payments.length === 0 && (
              <p className="text-xs text-muted-foreground">No legs yet — add at least two.</p>
            )}
            {form.split_payments.map((leg, index) => (
              <div key={index} className="flex items-center gap-2">
                <Select
                  className="w-32"
                  options={[
                    { value: "cash", label: "Cash" },
                    { value: "card", label: "Card" },
                  ]}
                  value={leg.method}
                  onChange={(e) => updateSplitLeg(index, { method: e.target.value })}
                />
                <Input
                  className="w-32"
                  inputMode="decimal"
                  placeholder="Amount"
                  value={leg.amount}
                  onChange={(e) => updateSplitLeg(index, { amount: e.target.value })}
                />
                <Button type="button" variant="ghost" size="icon" onClick={() => removeSplitLeg(index)}>
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </div>
            ))}
          </div>
        )}
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Tolls ($)</label>
          <Input
            value={form.tolls}
            onChange={(e) => update("tolls", e.target.value)}
            inputMode="decimal"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Extras ($)</label>
          <Input
            value={form.extras}
            onChange={(e) => update("extras", e.target.value)}
            inputMode="decimal"
          />
        </div>
        <div className="flex flex-col gap-1 sm:col-span-2">
          <label className="text-xs font-medium text-muted-foreground">GPS trace reference</label>
          <Input
            value={form.gps_trace_ref}
            onChange={(e) => update("gps_trace_ref", e.target.value)}
            placeholder="Optional"
          />
        </div>

        {error && (
          <p className="sm:col-span-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {error}
          </p>
        )}
      </form>
    </Modal>
  );
}
