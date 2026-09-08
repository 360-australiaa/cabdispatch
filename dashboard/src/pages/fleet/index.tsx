import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { AlertTriangle, Car, Smartphone, Trash2, Users } from "lucide-react";
import { Button, Checkbox, Input, Modal, PageHeader, Tabs, type TabItem } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { VehiclesPanel } from "./VehiclesPanel";
import { DriversPanel } from "./DriversPanel";
import { DevicesPanel } from "./DevicesPanel";
import { FatigueAlertsBanner } from "./FatigueAlertsBanner";
import { ComplianceExpiryBanner } from "./ComplianceExpiryBanner";
import {
  useForceWipeAllFleetData,
  useWipeAllFleetData,
  type FleetForceWipeFailure,
  type WipeAllFleetDataResult,
} from "./api";
import { errorMessage } from "./format";
import { canUseFleetTestTooling } from "./testTooling";

type FleetTab = "vehicles" | "drivers" | "devices";

const TABS: TabItem<FleetTab>[] = [
  { value: "vehicles", label: "Vehicles", icon: Car },
  { value: "drivers", label: "Drivers", icon: Users },
  { value: "devices", label: "Devices", icon: Smartphone },
];

const VALID_TABS = new Set<string>(TABS.map((t) => t.value));

const WIPE_CONFIRM_PHRASE = "DELETE";

/** Human labels for the evidence-category keys the force-wipe endpoint
 * reports in `evidence_rows_destroyed` -- see backend
 * app.services.fleet_wipe.ForceWipeResult. Kept as a display-only lookup so
 * an unrecognized future key still renders (falls back to the raw key)
 * rather than silently disappearing. */
const EVIDENCE_CATEGORY_LABELS: Record<string, string> = {
  psl_ledger_entries: "PSL levy ledger entries",
  psl_topups: "PSL levy top-up payments",
  wallet_transactions: "wallet transactions",
  trip_ratings: "trip ratings",
  compliance_documents: "compliance documents",
  tariff_change_log_entries: "tariff change-log entries",
};

/** Normalized shape both the default and force wipe results are mapped into,
 * so the result panel below has one rendering path regardless of which one
 * ran. `evidenceRowsDestroyed`/`auditLogPreserved` are only ever present on
 * a force-wipe result. */
interface WipeOutcome {
  usedForce: boolean;
  vehiclesDeleted: number;
  driversDeleted: number;
  devicesDeleted: number;
  failures: (WipeAllFleetDataResult["failures"][number] | FleetForceWipeFailure)[];
  evidenceRowsDestroyed?: Record<string, number>;
  auditLogPreserved?: boolean;
}

/**
 * Testing-only bulk wipe (2026-09-07, direct product instruction — see
 * useWipeAllFleetData's own doc). Deletes every vehicle, driver, and device on
 * this tenant in one action, so a fresh-onboarding test ("like a new user is
 * onboarding properly") starts from a genuinely clean fleet instead of the
 * real accumulated test data (multiple tablets/drivers/vehicles from past
 * sessions) that made that day's testing confusing.
 *
 * This is test tooling, and it is no longer part of the production UI: it
 * renders only when BOTH `VITE_ENABLE_TEST_TOOLING === "true"` at build time
 * AND the signed-in account is a platform owner (canUseFleetTestTooling, which
 * documents why both). The gate is applied by FleetPage below, which does not
 * render this component at all otherwise, and re-checked here so no future
 * caller can mount it ungated. The backend wipe routes are deliberately left
 * untouched — whether they survive is a separate decision.
 *
 * Even behind those gates it still demands the literal word DELETE typed out
 * rather than a click-through confirm: this has a bigger blast radius than any
 * other destructive action on this page (every vehicle/driver/device at once,
 * not one row).
 *
 * Two distinct actions live in this one modal:
 *   1. The default wipe (unchanged): deletes vehicles/devices/every driver
 *      EXCEPT ones with real PSL/wallet/rating/compliance/tariff evidence on
 *      file -- those correctly survive, reported back as failures.
 *   2. "Also permanently destroy financial/compliance evidence" (owner-only):
 *      a deliberate, separately-ticked, OFF-BY-DEFAULT opt-in that calls the
 *      real backend force-wipe endpoint instead -- see useForceWipeAllFleetData's
 *      own doc for exactly what that does and does not destroy. Never the
 *      default, never implied by typing DELETE alone -- the checkbox is its
 *      own explicit choice on top of that.
 */
function WipeAllFleetDataButton() {
  const { user } = useAuth();
  // Second, independent check of the same gate FleetPage applies before
  // rendering this at all — see this component's own doc.
  const allowed = canUseFleetTestTooling(user);
  const canForceWipe = user?.role === "owner";
  const wipeAll = useWipeAllFleetData();
  const forceWipe = useForceWipeAllFleetData();
  const wiping = wipeAll.isPending || forceWipe.isPending;
  const [open, setOpen] = useState(false);
  const [confirmText, setConfirmText] = useState("");
  const [destroyEvidence, setDestroyEvidence] = useState(false);
  const [result, setResult] = useState<WipeOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);

  function openModal() {
    setConfirmText("");
    setDestroyEvidence(false);
    setResult(null);
    setError(null);
    setOpen(true);
  }

  async function confirmWipe() {
    setError(null);
    try {
      if (destroyEvidence && canForceWipe) {
        const res = await forceWipe.mutateAsync();
        setResult({
          usedForce: true,
          vehiclesDeleted: res.vehiclesDeleted,
          driversDeleted: res.driversDeleted,
          devicesDeleted: res.devicesDeleted,
          failures: res.failures,
          evidenceRowsDestroyed: res.evidenceRowsDestroyed,
          auditLogPreserved: res.auditLogPreserved,
        });
      } else {
        const res = await wipeAll.mutateAsync();
        setResult({ usedForce: false, ...res });
      }
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const evidenceEntries = result?.evidenceRowsDestroyed
    ? Object.entries(result.evidenceRowsDestroyed).filter(([, count]) => count > 0)
    : [];

  // Nothing at all — no button, no disabled affordance, no hint that the
  // capability exists. Placed after every hook so the hook order is stable.
  if (!allowed) return null;

  return (
    <>
      <Button variant="destructive" onClick={openModal}>
        <Trash2 className="h-4 w-4" />
        Wipe all fleet data
      </Button>

      <Modal
        open={open}
        onClose={() => {
          if (!wiping) setOpen(false);
        }}
        title={
          result
            ? result.failures.length > 0
              ? "Fleet data partially wiped"
              : "Fleet data wiped"
            : "Wipe every vehicle, driver, and device?"
        }
        description={
          result
            ? result.failures.length > 0
              ? `Deleted ${result.vehiclesDeleted} vehicle${result.vehiclesDeleted === 1 ? "" : "s"}, ${result.driversDeleted} driver${result.driversDeleted === 1 ? "" : "s"}, and ${result.devicesDeleted} device${result.devicesDeleted === 1 ? "" : "s"}, but ${result.failures.length} row${result.failures.length === 1 ? "" : "s"} failed to delete (see below). Run it again to retry just the leftovers.`
              : `Deleted ${result.vehiclesDeleted} vehicle${result.vehiclesDeleted === 1 ? "" : "s"}, ${result.driversDeleted} driver${result.driversDeleted === 1 ? "" : "s"}, and ${result.devicesDeleted} device${result.devicesDeleted === 1 ? "" : "s"}. The fleet is now empty — ready for a fresh onboarding test.`
            : "This is temporary, testing-only tooling — it permanently deletes EVERY vehicle, driver, and device on this tenant in one action, including any linked to real trip history. This cannot be undone. Type DELETE to confirm."
        }
        footer={
          result ? (
            <Button onClick={() => setOpen(false)}>Done</Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => setOpen(false)} disabled={wiping}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                disabled={confirmText !== WIPE_CONFIRM_PHRASE || wiping}
                onClick={confirmWipe}
              >
                {wiping ? "Wiping…" : destroyEvidence ? "Wipe everything, evidence included" : "Wipe everything"}
              </Button>
            </>
          )
        }
      >
        {!result && (
          <div className="flex flex-col gap-3">
            {error && (
              <p className="flex items-center gap-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                {error}
              </p>
            )}

            {canForceWipe && (
              <Checkbox
                wrapperClassName="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2"
                checked={destroyEvidence}
                onChange={(e) => setDestroyEvidence(e.target.checked)}
                disabled={wiping}
                label={
                  <>
                    <span className="font-medium text-destructive">
                      Also permanently destroy financial/compliance evidence
                    </span>
                    <span className="mt-1 block font-normal text-muted-foreground">
                      Off by default. A normal wipe correctly leaves behind any driver with real
                      evidence on file. Ticking this box additionally and irreversibly destroys, for
                      every driver being deleted: PSL levy ledger entries and top-up payments,
                      wallet transactions, trip ratings, compliance documents, and tariff
                      change-log entries. The tamper-evident audit trail is never touched, even
                      with this box ticked — a driver who has ever been recorded there still cannot
                      be deleted, reported as a failure below. There is no undo.
                    </span>
                  </>
                }
              />
            )}

            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-foreground">
                Type <span className="font-mono">{WIPE_CONFIRM_PHRASE}</span> to confirm
              </span>
              <Input
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                disabled={wiping}
                placeholder={WIPE_CONFIRM_PHRASE}
              />
            </label>
          </div>
        )}

        {result && evidenceEntries.length > 0 && (
          <div className="mb-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <p className="font-medium">Evidence permanently destroyed:</p>
            <ul className="mt-1 list-inside list-disc">
              {evidenceEntries.map(([category, count]) => (
                <li key={category}>
                  {count} {EVIDENCE_CATEGORY_LABELS[category] ?? category}
                </li>
              ))}
            </ul>
            {result.auditLogPreserved && (
              <p className="mt-1 text-muted-foreground">
                The tamper-evident audit trail was left untouched.
              </p>
            )}
          </div>
        )}

        {result && result.failures.length > 0 && (
          <ul className="flex max-h-40 flex-col gap-1 overflow-y-auto rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {result.failures.map((f) => (
              <li key={`${f.kind}-${f.id}`}>
                {f.kind} {f.id.slice(0, 8)}…: {f.reason}
              </li>
            ))}
          </ul>
        )}
      </Modal>
    </>
  );
}

/** Fleet & Drivers — /fleet. Vehicle + device CRUD against the real backend,
 * plus a read-only driver rollup (see DriversPanel for why). Honors an
 * initial `?tab=` query param (e.g. `/fleet?tab=drivers`, used by the Getting
 * Started checklist to deep-link straight into a specific tab) — same
 * "read once, plain useState afterwards" convention as ShiftsPage's
 * `vehicle_id` param; the tab itself is not kept in sync with the URL after
 * that, matching how this page's other filters already behave. */
export default function FleetPage() {
  const { user } = useAuth();
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<FleetTab>(() => {
    const requested = searchParams.get("tab");
    return requested && VALID_TABS.has(requested) ? (requested as FleetTab) : "vehicles";
  });

  return (
    <div>
      {/* The wipe is test tooling, not a product feature: this header carries
          no actions at all unless the build and the account both allow it (see
          canUseFleetTestTooling). Everyone else gets a plain page header. */}
      <PageHeader
        title="Fleet & Drivers"
        description="Manage vehicles, their linked kiosk devices, and view driver live-status."
        actions={canUseFleetTestTooling(user) ? <WipeAllFleetDataButton /> : undefined}
      />

      <FatigueAlertsBanner />
      <ComplianceExpiryBanner />

      <Tabs
        items={TABS}
        value={tab}
        onChange={setTab}
        variant="underline"
        label="Fleet sections"
        className="mb-6"
      />

      {tab === "vehicles" && <VehiclesPanel />}
      {tab === "drivers" && <DriversPanel />}
      {tab === "devices" && <DevicesPanel />}
    </div>
  );
}
