import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { AlertTriangle, Car, Smartphone, Trash2, Users } from "lucide-react";
import { Button, Input, Modal, PageHeader } from "@/components/ui";
import { cn } from "@/lib/utils";
import { VehiclesPanel } from "./VehiclesPanel";
import { DriversPanel } from "./DriversPanel";
import { DevicesPanel } from "./DevicesPanel";
import { FatigueAlertsBanner } from "./FatigueAlertsBanner";
import { ComplianceExpiryBanner } from "./ComplianceExpiryBanner";
import { useWipeAllFleetData, type WipeAllFleetDataResult } from "./api";
import { errorMessage } from "./format";

type FleetTab = "vehicles" | "drivers" | "devices";

const TABS: { key: FleetTab; label: string; icon: typeof Car }[] = [
  { key: "vehicles", label: "Vehicles", icon: Car },
  { key: "drivers", label: "Drivers", icon: Users },
  { key: "devices", label: "Devices", icon: Smartphone },
];

const VALID_TABS = new Set<string>(TABS.map((t) => t.key));

const WIPE_CONFIRM_PHRASE = "DELETE";

/**
 * TEMPORARY testing-only bulk wipe (2026-09-07, direct product instruction —
 * see useWipeAllFleetData's own doc). Deletes every vehicle, driver, and
 * device on this tenant in one action, so a fresh-onboarding test ("like a
 * new user is onboarding properly") starts from a genuinely clean fleet
 * instead of the real accumulated test data (multiple tablets/drivers/
 * vehicles from past sessions) that made today's testing confusing. Gated
 * behind typing the literal word DELETE, not just a click-through confirm —
 * this is a bigger blast radius than any other destructive action on this
 * page (every vehicle/driver/device at once, not one row), and it should be
 * removed from the dashboard entirely once onboarding testing is done.
 */
function WipeAllFleetDataButton() {
  const wipeAll = useWipeAllFleetData();
  const [open, setOpen] = useState(false);
  const [confirmText, setConfirmText] = useState("");
  const [result, setResult] = useState<WipeAllFleetDataResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  function openModal() {
    setConfirmText("");
    setResult(null);
    setError(null);
    setOpen(true);
  }

  async function confirmWipe() {
    setError(null);
    try {
      const res = await wipeAll.mutateAsync();
      setResult(res);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <>
      <Button variant="destructive" onClick={openModal}>
        <Trash2 className="h-4 w-4" />
        Wipe all fleet data
      </Button>

      <Modal
        open={open}
        onClose={() => {
          if (!wipeAll.isPending) setOpen(false);
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
              <Button variant="outline" onClick={() => setOpen(false)} disabled={wipeAll.isPending}>
                Cancel
              </Button>
              <Button
                variant="destructive"
                disabled={confirmText !== WIPE_CONFIRM_PHRASE || wipeAll.isPending}
                onClick={confirmWipe}
              >
                {wipeAll.isPending ? "Wiping…" : "Wipe everything"}
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
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-foreground">
                Type <span className="font-mono">{WIPE_CONFIRM_PHRASE}</span> to confirm
              </span>
              <Input
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                disabled={wipeAll.isPending}
                placeholder={WIPE_CONFIRM_PHRASE}
              />
            </label>
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
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<FleetTab>(() => {
    const requested = searchParams.get("tab");
    return requested && VALID_TABS.has(requested) ? (requested as FleetTab) : "vehicles";
  });

  return (
    <div>
      <PageHeader
        title="Fleet & Drivers"
        description="Manage vehicles, their linked kiosk devices, and view driver live-status."
        actions={<WipeAllFleetDataButton />}
      />

      <FatigueAlertsBanner />
      <ComplianceExpiryBanner />

      <div className="mb-6 flex gap-1 border-b border-border">
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              "flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors",
              tab === key
                ? "border-brand-primary text-brand-primary"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === "vehicles" && <VehiclesPanel />}
      {tab === "drivers" && <DriversPanel />}
      {tab === "devices" && <DevicesPanel />}
    </div>
  );
}
