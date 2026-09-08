import { useState, type FormEvent, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button, Input, Modal } from "@/components/ui";
import { createJob } from "./api";
import { AddressGeocoder, type GeocoderResult } from "./AddressGeocoder";
import { DispatchMapPicker, type PickTarget } from "./DispatchMapPicker";
import { FareEstimatePanel } from "./FareEstimatePanel";
import type { Job } from "./types";

const EMPTY_FORM = {
  originAddress: "",
  originLat: "",
  originLng: "",
  destAddress: "",
  destLat: "",
  destLng: "",
  fareLow: "",
  fareHigh: "",
};

/**
 * Opens a new job via `POST /v1/jobs` — this is what actually broadcasts a
 * `JobOffer` to every currently-available driver (toggled available + open
 * shift + not mid-trip, see `app/services/jobs.py`) over `WS /v1/jobs/live`,
 * which the Android app's Available Trips wheel slot is already listening
 * on. This modal is the dashboard-side half of that loop that didn't exist
 * before — dispatch could never actually create a job to test against.
 *
 * Pickup/drop-off are set via `AddressGeocoder` (Mapbox forward geocoding)
 * or `DispatchMapPicker` (click-to-pick, mirroring the tariffs toll-zone
 * picker) — both degrade to plain text/number entry without a Mapbox token,
 * see each component's header. `FareEstimatePanel` shows which tariff would
 * apply at the pickup point via `/v1/tariffs/suggest`; it is informational
 * only — see that component's header for why the fare fields below stay a
 * manually-typed, required estimate rather than anything computed here.
 */
export function CreateJobModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (job: Job) => void;
}) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [activePin, setActivePin] = useState<PickTarget>("pickup");

  const mutation = useMutation({
    mutationFn: () =>
      createJob({
        origin_address: form.originAddress,
        origin_lat: Number.parseFloat(form.originLat),
        origin_lng: Number.parseFloat(form.originLng),
        dest_address: form.destAddress,
        dest_lat: Number.parseFloat(form.destLat),
        dest_lng: Number.parseFloat(form.destLng),
        fare_estimate_low: Number.parseFloat(form.fareLow),
        fare_estimate_high: Number.parseFloat(form.fareHigh),
      }),
    onSuccess: (job) => {
      setForm(EMPTY_FORM);
      setActivePin("pickup");
      onCreated(job);
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    mutation.mutate();
  }

  function handleClose() {
    mutation.reset();
    setForm(EMPTY_FORM);
    setActivePin("pickup");
    onClose();
  }

  function set<K extends keyof typeof EMPTY_FORM>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  function handleGeocodeSelect(target: PickTarget, result: GeocoderResult) {
    if (target === "pickup") {
      setForm((f) => ({
        ...f,
        originAddress: result.address,
        originLat: result.lat.toString(),
        originLng: result.lng.toString(),
      }));
    } else {
      setForm((f) => ({
        ...f,
        destAddress: result.address,
        destLat: result.lat.toString(),
        destLng: result.lng.toString(),
      }));
    }
  }

  function handleMapPick(target: PickTarget, lat: number, lng: number) {
    if (target === "pickup") {
      setForm((f) => ({ ...f, originLat: lat.toString(), originLng: lng.toString() }));
    } else {
      setForm((f) => ({ ...f, destLat: lat.toString(), destLng: lng.toString() }));
    }
  }

  const pickupPoint =
    form.originLat && form.originLng
      ? { lat: Number.parseFloat(form.originLat), lng: Number.parseFloat(form.originLng) }
      : null;
  const dropoffPoint =
    form.destLat && form.destLng
      ? { lat: Number.parseFloat(form.destLat), lng: Number.parseFloat(form.destLng) }
      : null;

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="New job"
      description="Broadcasts to every currently-available driver on shift. First to accept wins; sibling offers expire automatically."
      className="max-w-lg"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <AddressGeocoder
          label="Pickup address"
          placeholder="123 George St, Sydney"
          value={form.originAddress}
          onSelect={(result) => {
            setActivePin("pickup");
            handleGeocodeSelect("pickup", result);
            set("originAddress", result.address);
          }}
        />
        <AddressGeocoder
          label="Drop-off address"
          placeholder="Sydney Airport, T1"
          value={form.destAddress}
          onSelect={(result) => {
            setActivePin("dropoff");
            handleGeocodeSelect("dropoff", result);
            set("destAddress", result.address);
          }}
        />

        <DispatchMapPicker
          pickup={pickupPoint}
          dropoff={dropoffPoint}
          active={activePin}
          onActiveChange={setActivePin}
          onPick={handleMapPick}
        />

        <FareEstimatePanel lat={pickupPoint?.lat ?? null} lng={pickupPoint?.lng ?? null} />

        <div className="grid grid-cols-2 gap-3">
          <FormField label="Fare estimate — low ($)">
            <Input
              type="number"
              step="0.01"
              min="0"
              value={form.fareLow}
              onChange={(e) => set("fareLow", e.target.value)}
              placeholder="18.00"
              required
            />
          </FormField>
          <FormField label="Fare estimate — high ($)">
            <Input
              type="number"
              step="0.01"
              min="0"
              value={form.fareHigh}
              onChange={(e) => set("fareHigh", e.target.value)}
              placeholder="22.00"
              required
            />
          </FormField>
        </div>
        <p className="text-xs text-muted-foreground">
          This is the number quoted to the passenger before the trip — not the fare charged. The meter
          computes the actual fare at trip end and it will differ; this estimate excludes tolls, waiting
          time, and the exact route driven.
        </p>

        {mutation.isError && (
          <p className="text-sm text-destructive">
            Failed to create the job. Check the fields and try again.
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" disabled={mutation.isPending}>
            {mutation.isPending ? "Broadcasting…" : "Create & broadcast"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function FormField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-sm font-medium">{label}</label>
      {children}
    </div>
  );
}
