import { useState } from "react";
import { Button, Checkbox, Input, Sheet, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useResetOnChange } from "@/lib/useResetOnChange";
import { useUpdateDriverUser, type DriverUser } from "./api";

interface FormState {
  name: string;
  phone: string;
  driver_licence_no: string;
  wat_endorsed: boolean;
}

/**
 * Edit sheet for a driver's identity fields (dashboard command-centre plan
 * §4's Edit action) -- the subset of DriversPanel's "Add driver" modal that
 * still makes sense to edit AFTER creation: name, phone, licence number,
 * WAT endorsement. Deliberately excludes:
 *  - email/password: `UserUpdate` accepts a `password` field, but changing
 *    it for a driver silently changes their meter PIN under an "edit
 *    profile" label -- that has its own explicit action (Reset PIN) so an
 *    operator can't do it by accident while just fixing a phone number.
 *  - the two compliance-expiry dates: they have their own tab (Compliance)
 *    with its own audit framing; duplicating the fields here would let two
 *    open surfaces disagree about which one is "current".
 */
export function EditDriverSheet({
  driver,
  open,
  onClose,
}: {
  driver: DriverUser | null;
  open: boolean;
  onClose: () => void;
}) {
  const [form, setForm] = useState<FormState>({ name: "", phone: "", driver_licence_no: "", wat_endorsed: false });
  const [error, setError] = useState<string | null>(null);
  const mutation = useUpdateDriverUser();
  const toast = useToast();

  useResetOnChange(open ? driver?.id ?? null : null, () => {
    if (!driver) return;
    setForm({
      name: driver.name,
      phone: driver.phone ?? "",
      driver_licence_no: driver.driver_licence_no ?? "",
      wat_endorsed: driver.wat_endorsed,
    });
    setError(null);
    mutation.reset();
  });

  async function handleSubmit() {
    if (!driver) return;
    setError(null);
    try {
      await mutation.mutateAsync({
        id: driver.id,
        values: {
          name: form.name.trim(),
          phone: form.phone.trim() || null,
          driver_licence_no: form.driver_licence_no.trim() || null,
          wat_endorsed: form.wat_endorsed,
        },
      });
      toast.success("Driver updated", { description: form.name.trim() });
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      toast.error("Failed to update driver", { description: errorMessage(err) });
    }
  }

  return (
    <Sheet
      open={open}
      onClose={onClose}
      title="Edit driver"
      description="PATCH /v1/users/{id} — name, phone, licence number and WAT endorsement."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!form.name.trim() || mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Save changes"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Name *</label>
          <Input value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} required />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Phone</label>
          <Input value={form.phone} onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver licence no.</label>
          <Input
            value={form.driver_licence_no}
            onChange={(e) => setForm((f) => ({ ...f, driver_licence_no: e.target.value }))}
          />
        </div>
        <Checkbox
          label="WAT endorsed (wheelchair-accessible vehicle certified)"
          checked={form.wat_endorsed}
          onChange={(e) => setForm((f) => ({ ...f, wat_endorsed: e.target.checked }))}
        />
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    </Sheet>
  );
}
