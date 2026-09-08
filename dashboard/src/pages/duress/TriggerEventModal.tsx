import { useState, type FormEvent, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button, Modal, Select } from "@/components/ui";
import { triggerDuressEvent } from "./api";
import type { DuressEvent, DuressTrigger } from "./types";
import { useDuressLookups } from "./useDuressLookups";

const TRIGGER_OPTIONS: { value: DuressTrigger; label: string }[] = [
  { value: "button", label: "Panic button" },
  { value: "gesture", label: "Gesture" },
  { value: "voice", label: "Voice" },
  { value: "auto", label: "Automatic (sensor)" },
];

/**
 * Opens a new duress event via `POST /v1/duress/trigger` — the normal path
 * for logging an incident (e.g. dispatch takes a phone call reporting a
 * driver in distress and opens the record manually). Starts the 10-second
 * cancel window server-side.
 *
 * Vehicle and driver are `<Select>`s over the same lookups the events table
 * uses to resolve UUIDs (`useDuressLookups`), not free-text UUID fields —
 * dashboard audit `TriggerEventModal.tsx:66,74`: an operator opening an
 * incident by phone should never have to go find and paste a UUID first.
 */
export function TriggerEventModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (event: DuressEvent) => void;
}) {
  const lookups = useDuressLookups();
  const [vehicleId, setVehicleId] = useState("");
  const [driverId, setDriverId] = useState("");
  const [trigger, setTrigger] = useState<DuressTrigger>("button");

  const mutation = useMutation({
    mutationFn: () => triggerDuressEvent({ vehicle_id: vehicleId, driver_id: driverId, trigger }),
    onSuccess: (event) => {
      setVehicleId("");
      setDriverId("");
      setTrigger("button");
      onCreated(event);
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    mutation.mutate();
  }

  function handleClose() {
    mutation.reset();
    onClose();
  }

  const vehicleOptions = lookups.vehicles.map((v) => ({ value: v.id, label: v.rego }));
  const driverOptions = lookups.drivers.map((d) => ({ value: d.id, label: d.name }));

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="Trigger duress event"
      description="Opens a new event and starts its 10-second cancel window."
      className="max-w-md"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <FormField label="Vehicle">
          <Select
            options={vehicleOptions}
            value={vehicleId}
            onChange={(e) => setVehicleId(e.target.value)}
            placeholder={lookups.isLoading ? "Loading vehicles…" : "Select a vehicle"}
            disabled={lookups.isLoading || vehicleOptions.length === 0}
            required
          />
          {!lookups.isLoading && vehicleOptions.length === 0 && (
            <p className="text-xs text-muted-foreground">No vehicles found.</p>
          )}
        </FormField>
        <FormField label="Driver">
          <Select
            options={driverOptions}
            value={driverId}
            onChange={(e) => setDriverId(e.target.value)}
            placeholder={lookups.isLoading ? "Loading drivers…" : "Select a driver"}
            disabled={lookups.isLoading || driverOptions.length === 0}
            required
          />
          {!lookups.isLoading && driverOptions.length === 0 && (
            <p className="text-xs text-muted-foreground">No drivers found.</p>
          )}
        </FormField>
        <FormField label="Trigger">
          <Select
            options={TRIGGER_OPTIONS}
            value={trigger}
            onChange={(e) => setTrigger(e.target.value as DuressTrigger)}
          />
        </FormField>

        {mutation.isError && (
          <p className="text-sm text-destructive">Failed to trigger the event. Try again.</p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="destructive"
            disabled={mutation.isPending || !vehicleId || !driverId}
          >
            {mutation.isPending ? "Triggering…" : "Trigger event"}
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
