import { useEffect, useState } from "react";
import { Button, Input, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useDriverCompliance, useUpdateDriverCompliance } from "@/pages/fleet/api";

interface FormState {
  driver_license_expiry: string;
  driver_authority_expiry: string;
}

/**
 * Driver page Compliance tab (dashboard command-centre plan §4.6): the
 * licence/authority expiry dates, reusing the exact query/mutation
 * `DriversPanel`'s modal already uses (`GET`/`PATCH /v1/users/{id}` via
 * `useDriverCompliance`/`useUpdateDriverCompliance`) rather than a second
 * copy of the same read/write. Editable for owner/admin only, same gate as
 * that modal.
 *
 * Compliance DOCUMENTS are deliberately omitted here: `GET /v1/compliance/documents`
 * (`backend/app/api/v1/compliance.py::list_documents`) only filters by
 * `vehicle_id` -- there is no driver-scoped subject filter to call, and this
 * page must not invent one client-side (fetching every document and
 * filtering locally would silently understate the count once the list
 * exceeds a page). Per the workstream brief, omitted rather than shown
 * always-empty; add the section back once a driver-scoped filter lands.
 */
export function ComplianceTab({ driverId, canEdit }: { driverId: string; canEdit: boolean }) {
  const complianceQuery = useDriverCompliance(driverId);
  const updateCompliance = useUpdateDriverCompliance();
  const toast = useToast();

  const [form, setForm] = useState<FormState>({ driver_license_expiry: "", driver_authority_expiry: "" });
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (complianceQuery.data) {
      setForm({
        driver_license_expiry: complianceQuery.data.driver_license_expiry ?? "",
        driver_authority_expiry: complianceQuery.data.driver_authority_expiry ?? "",
      });
    }
  }, [complianceQuery.data]);

  async function handleSave() {
    setError(null);
    setSaved(false);
    try {
      await updateCompliance.mutateAsync({
        id: driverId,
        values: {
          driver_license_expiry: form.driver_license_expiry || null,
          driver_authority_expiry: form.driver_authority_expiry || null,
        },
      });
      setSaved(true);
      toast.success("Compliance dates saved");
    } catch (err) {
      setError(errorMessage(err));
      toast.error("Failed to save compliance dates", { description: errorMessage(err) });
    }
  }

  if (complianceQuery.isLoading) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  if (complianceQuery.isError) {
    return (
      <p className="text-sm text-destructive">
        Failed to load compliance dates: {errorMessage(complianceQuery.error)}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-xs text-muted-foreground">
        Required under NSW Point to Point Transport regulation to keep this driver compliant. Cab
        Dispatch reminds you when these are expiring but does not verify or enforce them.
      </p>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver licence expiry</label>
          <Input
            type="date"
            value={form.driver_license_expiry}
            onChange={(e) => setForm((f) => ({ ...f, driver_license_expiry: e.target.value }))}
            disabled={!canEdit}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver authority expiry</label>
          <Input
            type="date"
            value={form.driver_authority_expiry}
            onChange={(e) => setForm((f) => ({ ...f, driver_authority_expiry: e.target.value }))}
            disabled={!canEdit}
          />
          <p className="mt-1 text-xs text-muted-foreground">
            NSW Point to Point driver authority — separate from the driving licence above.
          </p>
        </div>
      </div>
      {canEdit && (
        <div className="flex items-center gap-3">
          <Button size="sm" onClick={handleSave} disabled={updateCompliance.isPending}>
            {updateCompliance.isPending ? "Saving…" : "Save compliance dates"}
          </Button>
          {saved && !updateCompliance.isPending && (
            <span className="text-xs text-emerald-600 dark:text-emerald-400">Saved.</span>
          )}
        </div>
      )}
      {error && <p className="text-xs text-destructive">{error}</p>}
      <p className="text-xs text-muted-foreground">
        Suitability status: {complianceQuery.data?.suitability_status ?? "—"}
      </p>
    </div>
  );
}
