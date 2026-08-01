import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import { Button, Input, Modal, Select } from "@/components/ui";
import type { PublishPositionRequest, VehicleLiveRead } from "./types";

const STATUS_OPTIONS = [
  { value: "available", label: "Available" },
  { value: "on_trip", label: "On trip" },
  { value: "break", label: "Break" },
  { value: "offline", label: "Offline" },
];

interface PublishPositionModalProps {
  open: boolean;
  onClose: () => void;
  vehicles: VehicleLiveRead[];
}

/**
 * Live Ops owns no CRUD table of its own (see `src/pages/live-map/types.ts`
 * header comment) — the one write endpoint this domain exposes is
 * `POST /v1/fleet/positions`, the same "position tick" a paired device sends
 * periodically. This modal lets a dispatcher publish one manually (e.g. to
 * correct a stale/offline device's map position), which is why there's no
 * matching edit/delete: a later publish for the same vehicle simply
 * supersedes this one, and there's nothing durable to delete.
 */
export function PublishPositionModal({ open, onClose, vehicles }: PublishPositionModalProps) {
  const queryClient = useQueryClient();
  const [vehicleId, setVehicleId] = useState("");
  const [lat, setLat] = useState("");
  const [lng, setLng] = useState("");
  const [status, setStatus] = useState("available");

  useEffect(() => {
    if (open) {
      setVehicleId(vehicles[0]?.id ?? "");
      setLat("");
      setLng("");
      setStatus("available");
    }
  }, [open, vehicles]);

  const mutation = useMutation({
    mutationFn: (payload: PublishPositionRequest) => apiClient.post("/v1/fleet/positions", payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["live-map", "vehicles"] });
      onClose();
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const latNum = Number(lat);
    const lngNum = Number(lng);
    if (!vehicleId || Number.isNaN(latNum) || Number.isNaN(lngNum)) return;
    mutation.mutate({ vehicle_id: vehicleId, lat: latNum, lng: lngNum, status });
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Publish vehicle position"
      description="Broadcasts a position update over WS /v1/fleet/live, the same event a paired device's periodic tick sends. Not persisted to the database."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="publish-position-form" disabled={mutation.isPending || vehicles.length === 0}>
            {mutation.isPending ? "Publishing…" : "Publish"}
          </Button>
        </>
      }
    >
      <form id="publish-position-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="pp-vehicle" className="text-sm font-medium">
            Vehicle
          </label>
          <Select
            id="pp-vehicle"
            options={vehicles.map((v) => ({ value: v.id, label: `${v.rego} — ${v.vehicle_class}` }))}
            placeholder={vehicles.length === 0 ? "No vehicles available" : "Select a vehicle"}
            value={vehicleId}
            onChange={(e) => setVehicleId(e.target.value)}
            required
            disabled={vehicles.length === 0}
          />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="pp-lat" className="text-sm font-medium">
              Latitude
            </label>
            <Input
              id="pp-lat"
              type="number"
              step="any"
              min={-90}
              max={90}
              required
              value={lat}
              onChange={(e) => setLat(e.target.value)}
              placeholder="-33.8688"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="pp-lng" className="text-sm font-medium">
              Longitude
            </label>
            <Input
              id="pp-lng"
              type="number"
              step="any"
              min={-180}
              max={180}
              required
              value={lng}
              onChange={(e) => setLng(e.target.value)}
              placeholder="151.2093"
            />
          </div>
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="pp-status" className="text-sm font-medium">
            Status
          </label>
          <Select
            id="pp-status"
            options={STATUS_OPTIONS}
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            required
          />
        </div>
        {mutation.isError && (
          <p className="text-sm text-destructive">
            Failed to publish position. Confirm the vehicle belongs to your tenant and try again.
          </p>
        )}
      </form>
    </Modal>
  );
}
