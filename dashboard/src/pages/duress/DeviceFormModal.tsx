import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  createDuressDevice,
  listVehicleOptionsForDeviceLink,
  updateDuressDevice,
} from "./api";
import { errorMessage } from "./format";
import type { DuressDevice } from "./types";

const NO_VEHICLE_VALUE = "";

/**
 * Create/register a new duress hardware device (`POST /v1/duress-devices`),
 * or edit an existing one's linked vehicle / phone number / active flag
 * (`PATCH /v1/duress-devices/{id}` — `DuressDeviceUpdate`). Two distinct
 * backend schemas, so two distinct field sets here:
 *   - create requires `device_code` + `plaintext_secret` (write-once — see
 *     `DuressDeviceCreateBody`'s doc comment in `./types`), neither of which
 *     `DuressDeviceUpdate` accepts at all (device_code is immutable after
 *     provisioning; the secret is rotated via its own dedicated endpoint —
 *     see `RotateSecretModal`).
 *   - edit only ever touches `vehicle_id` / `phone_number` / `active`.
 */
export function DeviceFormModal({
  device,
  open,
  onClose,
  onSaved,
}: {
  /** null = create mode. A device = edit mode for that row. */
  device: DuressDevice | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = device !== null;
  const queryClient = useQueryClient();

  const [deviceCode, setDeviceCode] = useState("");
  const [vehicleId, setVehicleId] = useState(NO_VEHICLE_VALUE);
  const [phoneNumber, setPhoneNumber] = useState("");
  const [plaintextSecret, setPlaintextSecret] = useState("");
  const [active, setActive] = useState(true);

  useEffect(() => {
    if (!open) return;
    setDeviceCode(device?.device_code ?? "");
    setVehicleId(device?.vehicle_id ?? NO_VEHICLE_VALUE);
    setPhoneNumber(device?.phone_number ?? "");
    setPlaintextSecret("");
    setActive(device?.active ?? true);
  }, [open, device]);

  const vehicleOptionsQuery = useQuery({
    queryKey: ["duress-devices", "vehicle-options"],
    queryFn: listVehicleOptionsForDeviceLink,
    enabled: open,
  });

  const vehicleSelectOptions = [
    { value: NO_VEHICLE_VALUE, label: "— No vehicle linked —" },
    ...(vehicleOptionsQuery.data ?? []).map((v) => ({ value: v.id, label: v.rego })),
  ];

  const mutation = useMutation({
    mutationFn: () =>
      isEdit
        ? updateDuressDevice(device.id, {
            vehicle_id: vehicleId || null,
            phone_number: phoneNumber.trim() || null,
            active,
          })
        : createDuressDevice({
            device_code: deviceCode.trim(),
            vehicle_id: vehicleId || null,
            phone_number: phoneNumber.trim() || null,
            plaintext_secret: plaintextSecret,
          }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["duress-devices"] });
      onSaved();
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

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={isEdit ? `Edit device ${device.device_code}` : "Register a duress device"}
      description={
        isEdit
          ? "Relink this unit to a different vehicle, update its callback number, or deactivate it."
          : "Provisions a physical CT-DPD-01 panic-button unit already burned with a shared secret."
      }
      className="max-w-md"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <FormField label="Device code">
          <Input
            value={deviceCode}
            onChange={(e) => setDeviceCode(e.target.value)}
            placeholder="e.g. CT-DPD-01-00042"
            disabled={isEdit}
            required
          />
          {isEdit && (
            <p className="mt-1 text-xs text-muted-foreground">
              The factory-etched device code can't be changed after registration.
            </p>
          )}
        </FormField>

        <FormField label="Linked vehicle">
          <Select
            options={vehicleSelectOptions}
            value={vehicleId}
            onChange={(e) => setVehicleId(e.target.value)}
            disabled={vehicleOptionsQuery.isLoading}
          />
        </FormField>

        <FormField label="Phone number (optional)">
          <Input
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            placeholder="+61…"
          />
          <p className="mt-1 text-xs text-muted-foreground">
            The device's own SIM number — used by the "Call the cab" operator action.
          </p>
        </FormField>

        {!isEdit && (
          <FormField label="Shared secret (K_dev)">
            <Input
              value={plaintextSecret}
              onChange={(e) => setPlaintextSecret(e.target.value)}
              placeholder="16–200 characters, as burned into firmware"
              minLength={16}
              maxLength={200}
              required
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Encrypted at rest immediately. No screen in this dashboard will ever show it back to
              you again — record it in your own device inventory now.
            </p>
          </FormField>
        )}

        {isEdit && (
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-border"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
            />
            Active (unchecking rejects this device at its next authentication attempt, without
            deleting its history)
          </label>
        )}

        {mutation.isError && (
          <p className="text-sm text-destructive">{errorMessage(mutation.error)}</p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : isEdit ? "Save changes" : "Register device"}
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
