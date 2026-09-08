import { useEffect, useState, type ComponentType } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Copy,
  KeyRound,
  Link2Off,
  Lock,
  MapPin,
  RefreshCw,
  RotateCw,
  ShieldCheck,
  ShieldOff,
  Trash2,
  Unlock,
} from "lucide-react";
import { EntityLink } from "@/components/EntityLink";
import { EntityPage } from "@/components/layout/EntityPage";
import { Badge, Button, Modal, Tooltip, useToast } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { DEVICE_OFFLINE_AFTER_MS, isDeviceOffline } from "@/pages/overview/activityFeed";
import { useAuditLogQuery } from "@/pages/audit-log/api";
import { useLatestAppRelease } from "@/hooks/useAppReleases";
import { RESTART_APP_REASON } from "@/pages/fleet/DevicesPanel";
import {
  useDeleteDevice,
  useDeviceDetailQuery,
  useForceUpdate,
  useKioskLock,
  useLocateDevice,
  useRestartApp,
  useRotateDeviceSecret,
  useSetDeviceRevoked,
  useUpdateDevice,
  useVehicleOptions,
} from "@/pages/fleet/api";
import { batteryColor, networkBadgeVariant } from "@/pages/live-map/utils";
import { errorMessage, formatDateTime, relativeFromNow } from "@/pages/fleet/format";
import { StatusTab } from "./tabs/StatusTab";
import { HeartbeatsTab } from "./tabs/HeartbeatsTab";
import { CommandsTab } from "./tabs/CommandsTab";
import { VersionsTab } from "./tabs/VersionsTab";
import { ActivityTab } from "./tabs/ActivityTab";

const OFFLINE_MINUTES = Math.round(DEVICE_OFFLINE_AFTER_MS / 60_000);

type ConfirmAction = "unpair" | "revoke" | "reinstate" | "delete";

const DISABLED_REASON = "Requires an owner or admin account";

/**
 * One action-row button, optionally wrapped in an informational or
 * disabled-reason `Tooltip`. Declared at module scope rather than inside
 * `DevicePage` -- a component defined during another component's render
 * gets torn down and recreated every render, which resets whatever local
 * state it holds (eslint `react-hooks/static-components` catches exactly
 * this). This one is stateless, so the only real cost was the lint error,
 * but the fix is the same either way: hoist it.
 */
function ActionButton({
  icon: Icon,
  label,
  onClick,
  canManage,
  actionBusy,
  disabled = false,
  tooltip,
  variant = "outline",
}: {
  icon: ComponentType<{ className?: string }>;
  label: string;
  onClick: () => void;
  /** Whether the viewer's role can use ANY device mutation at all. */
  canManage: boolean;
  /** Whether some other mutation on this page is already in flight. */
  actionBusy: boolean;
  /** True when THIS specific command has its own reason to be blocked
   * (e.g. already pending) -- independent of the role gate above. */
  disabled?: boolean;
  /** Shown when enabled too, for an informational hint (e.g. restart's
   * "restarts the app, not the OS" note) -- overridden by the role-gate
   * reason when the viewer simply can't use this control at all. */
  tooltip?: string;
  variant?: "outline" | "destructive";
}) {
  const isDisabled = !canManage || actionBusy || disabled;
  const reason = !canManage ? DISABLED_REASON : tooltip;
  const button = (
    <Button variant={variant} size="sm" onClick={onClick} disabled={isDisabled}>
      <Icon className="h-4 w-4" /> {label}
    </Button>
  );
  return reason ? <Tooltip content={reason}>{button}</Tooltip> : button;
}

/** Re-renders the caller every `ms` so the offline badge/heartbeat age stay
 * honest between query refetches, without calling the impure `Date.now()`
 * directly inside render (same pattern as `pages/overview/index.tsx`'s own
 * `useNow`, `pages/duress/EscalationTimeline.tsx`, and
 * `pages/dispatch/JobDetailPanel.tsx` -- a state tick, not an animation). */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

/**
 * `/devices/:deviceId` -- the tablet's full record (dashboard command-centre
 * plan §6). Header + actions reuse `DevicesPanel`'s exact mutations so this
 * page and the Fleet & Drivers row buttons can never disagree on what a
 * command does; the Status/Versions/Activity tabs are real data, Heartbeats
 * and Commands are labelled placeholders (see their own files) because the
 * backend history tables/endpoints they need don't exist yet.
 */
export default function DevicePage() {
  const { deviceId } = useParams<{ deviceId: string }>();
  const navigate = useNavigate();
  const toast = useToast();
  const { user } = useAuth();

  // Every device mutation the backend exposes is owner|admin gated
  // (`_require_admin` in `app/api/v1/fleet.py`) -- a dispatcher or driver
  // token gets a 403 from every one of them. Disabling honestly here rather
  // than hiding (per the plan's F5 permissions note: "disabled + tooltip,
  // not hidden") saves that round trip and says why up front.
  const canManage = user?.role === "owner" || user?.role === "admin";

  const deviceQuery = useDeviceDetailQuery(deviceId ?? null);
  const device = deviceQuery.data;

  const vehicleOptionsQuery = useVehicleOptions();
  const vehicle = device?.vehicle_id
    ? vehicleOptionsQuery.data?.find((v) => v.id === device.vehicle_id)
    : undefined;

  // Any authenticated tenant user, not platform-owner-gated -- see
  // `useLatestAppRelease`'s own doc for why this is the right endpoint
  // rather than `GET /v1/platform/app-releases`. Still handled
  // defensively: a 404 (no release ever published) or any other failure
  // just omits the "update available" comparison, never blocks the header.
  const latestReleaseQuery = useLatestAppRelease();

  // Decided up front, not inside the tab component: whether the Activity tab
  // exists at all depends on whether this call succeeds for the current
  // viewer. A parallel workstream may still be adding `subject_id` support
  // (or this could fail for a role this endpoint doesn't expect) -- either
  // way, a failure omits the tab rather than shows it broken.
  const auditLogQuery = useAuditLogQuery(0, { subject_id: deviceId });

  const kioskLock = useKioskLock();
  const forceUpdate = useForceUpdate();
  const locateDevice = useLocateDevice();
  const restartApp = useRestartApp();
  const rotateSecret = useRotateDeviceSecret();
  const setRevoked = useSetDeviceRevoked();
  const updateDevice = useUpdateDevice();
  const deleteDevice = useDeleteDevice();

  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const [rotateSecretOpen, setRotateSecretOpen] = useState(false);
  const [revealedSecret, setRevealedSecret] = useState<string | null>(null);

  async function toggleKioskLock() {
    if (!device) return;
    try {
      await kioskLock.mutateAsync({ id: device.id, enabled: !device.kiosk_locked });
      toast.success(device.kiosk_locked ? "Kiosk unlocked" : "Kiosk locked", {
        description: device.android_id,
      });
    } catch (err) {
      toast.error(device.kiosk_locked ? "Failed to unlock kiosk" : "Failed to lock kiosk", {
        description: errorMessage(err),
      });
    }
  }

  async function triggerRestart() {
    if (!device) return;
    try {
      await restartApp.mutateAsync(device.id);
      toast.success("App restart queued", { description: device.android_id });
    } catch (err) {
      toast.error("Failed to queue app restart", { description: errorMessage(err) });
    }
  }

  async function triggerForceUpdate() {
    if (!device) return;
    try {
      await forceUpdate.mutateAsync(device.id);
      toast.success("Update queued", { description: device.android_id });
    } catch (err) {
      toast.error("Failed to queue update", { description: errorMessage(err) });
    }
  }

  async function triggerLocate() {
    if (!device) return;
    try {
      await locateDevice.mutateAsync(device.id);
      toast.success("Locate requested", { description: device.android_id });
    } catch (err) {
      toast.error("Failed to request location", { description: errorMessage(err) });
    }
  }

  async function confirmRotateSecret() {
    if (!device) return;
    try {
      const result = await rotateSecret.mutateAsync(device.id);
      // The one and only place this value is ever held -- never passed to
      // `toast` (which could end up surfaced elsewhere) and never logged.
      setRevealedSecret(result.device_secret);
    } catch (err) {
      toast.error("Failed to rotate device secret", { description: errorMessage(err) });
      setRotateSecretOpen(false);
    }
  }

  function closeRotateSecret() {
    setRotateSecretOpen(false);
    setRevealedSecret(null);
  }

  async function copySecret() {
    if (!revealedSecret) return;
    await navigator.clipboard.writeText(revealedSecret);
    toast.success("Secret copied to clipboard");
  }

  async function runConfirmedAction() {
    if (!device || !confirmAction) return;
    try {
      if (confirmAction === "unpair") {
        await updateDevice.mutateAsync({
          id: device.id,
          values: {
            model: device.model ?? "",
            app_version: device.app_version ?? "",
            vehicle_id: "",
            kiosk_locked: device.kiosk_locked,
          },
        });
        toast.success("Vehicle link cleared", { description: device.android_id });
      } else if (confirmAction === "revoke") {
        await setRevoked.mutateAsync({ id: device.id, revoked: true });
        toast.success("Device revoked", { description: device.android_id });
      } else if (confirmAction === "reinstate") {
        await setRevoked.mutateAsync({ id: device.id, revoked: false });
        toast.success("Device reinstated", { description: device.android_id });
      } else {
        await deleteDevice.mutateAsync(device.id);
        toast.success("Device deleted", { description: device.android_id });
        navigate("/fleet?tab=devices");
        return;
      }
      setConfirmAction(null);
    } catch (err) {
      toast.error("That action failed", { description: errorMessage(err) });
      setConfirmAction(null);
    }
  }

  const now = useNow(30_000);
  const offline = device ? isDeviceOffline(device, now) : false;
  const latestRelease = latestReleaseQuery.data;
  const updateAvailable =
    !!latestRelease && !!device?.app_version && device.app_version !== latestRelease.version_name;

  const actionBusy =
    kioskLock.isPending ||
    forceUpdate.isPending ||
    locateDevice.isPending ||
    restartApp.isPending ||
    updateDevice.isPending ||
    setRevoked.isPending ||
    deleteDevice.isPending;

  const tabs = device
    ? [
        { value: "status", label: "Status", content: <StatusTab device={device} /> },
        { value: "heartbeats", label: "Heartbeats", content: <HeartbeatsTab /> },
        { value: "commands", label: "Commands", content: <CommandsTab /> },
        {
          value: "versions",
          label: "Versions",
          content: (
            <VersionsTab
              device={device}
              canManage={canManage}
              onPushUpdate={triggerForceUpdate}
              isPushing={forceUpdate.isPending}
            />
          ),
        },
        ...(!auditLogQuery.isError
          ? [
              {
                value: "activity",
                label: "Activity",
                content: (
                  <ActivityTab
                    entries={auditLogQuery.data?.items ?? []}
                    isLoading={auditLogQuery.isLoading}
                  />
                ),
              },
            ]
          : []),
      ]
    : [];

  return (
    <>
      <EntityPage
        kind="device"
        title={device ? device.model || device.android_id : deviceId ? `Device ${deviceId}` : "Device"}
        subtitle="Tablet record"
        statusBadge={
          device ? (
            <Tooltip
              content={
                offline
                  ? `No heartbeat for more than ${OFFLINE_MINUTES} minutes.`
                  : `Heartbeat within the last ${OFFLINE_MINUTES} minutes.`
              }
            >
              <Badge variant={offline ? "destructive" : "success"}>{offline ? "Offline" : "Online"}</Badge>
            </Tooltip>
          ) : undefined
        }
        facts={
          device
            ? [
                { label: "Model", value: device.model ?? "—" },
                {
                  label: "Android ID",
                  value: (
                    <span className="inline-flex items-center gap-1">
                      <span className="font-mono text-xs">{device.android_id}</span>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        aria-label="Copy android ID"
                        onClick={async () => {
                          await navigator.clipboard.writeText(device.android_id);
                          toast.success("Android ID copied");
                        }}
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </Button>
                    </span>
                  ),
                },
                {
                  label: "App version",
                  value: (
                    <span className="inline-flex items-center gap-1.5">
                      {device.app_version ?? "—"}
                      {updateAvailable && (
                        <Tooltip
                          content={`Running ${device.app_version}; latest published build is ${latestRelease!.version_name}.`}
                        >
                          <Badge variant="accent">Update available</Badge>
                        </Tooltip>
                      )}
                    </span>
                  ),
                },
                {
                  // No "charging" state anywhere on this fact: the backend
                  // Device model has no such column (only `battery: int |
                  // None` exists), so showing one would be a fabricated
                  // figure the repo's own honesty rule forbids.
                  label: "Battery",
                  value:
                    device.battery == null ? (
                      "—"
                    ) : (
                      <span style={{ color: batteryColor(device.battery) }}>{device.battery}%</span>
                    ),
                },
                {
                  label: "Network",
                  value: device.network ? (
                    <Badge variant={networkBadgeVariant(device.network)}>{device.network}</Badge>
                  ) : (
                    "—"
                  ),
                },
                {
                  label: "Last heartbeat",
                  value: (
                    <span
                      title={formatDateTime(device.last_seen_at)}
                      className={offline ? "text-destructive" : "text-success"}
                    >
                      {relativeFromNow(device.last_seen_at)}
                    </span>
                  ),
                },
                {
                  label: "Paired vehicle",
                  value: device.vehicle_id ? (
                    <EntityLink kind="vehicle" id={device.vehicle_id} name={vehicle?.rego ?? device.vehicle_id} />
                  ) : (
                    "—"
                  ),
                },
                {
                  label: "Kiosk",
                  value: (
                    <Badge variant={device.kiosk_locked ? "success" : "outline"}>
                      {device.kiosk_locked ? "Locked" : "Unlocked"}
                    </Badge>
                  ),
                },
              ]
            : undefined
        }
        actions={
          device ? (
            <div className="flex flex-wrap items-center gap-2">
              <ActionButton
                icon={device.kiosk_locked ? Unlock : Lock}
                label={device.kiosk_locked ? "Unlock kiosk" : "Lock kiosk"}
                onClick={toggleKioskLock}
                canManage={canManage}
                actionBusy={actionBusy}
              />
              {/* "Restart app", not "Reboot" -- see DevicesPanel's own
                  RESTART_APP_REASON doc, reused verbatim here. There is no
                  separate device-owner-aware OS reboot in this codebase, so
                  this page does not offer a second, fake "Reboot" control
                  distinct from this one. */}
              <ActionButton
                icon={RotateCw}
                label="Restart app"
                onClick={triggerRestart}
                canManage={canManage}
                actionBusy={actionBusy}
                disabled={device.reboot_requested}
                tooltip={device.reboot_requested ? "Restart already queued" : RESTART_APP_REASON}
              />
              <ActionButton
                icon={RefreshCw}
                label="Force update"
                onClick={triggerForceUpdate}
                canManage={canManage}
                actionBusy={actionBusy}
                disabled={device.force_update_pending}
                tooltip={device.force_update_pending ? "Force-update already pending" : undefined}
              />
              <ActionButton
                icon={MapPin}
                label="Locate"
                onClick={triggerLocate}
                canManage={canManage}
                actionBusy={actionBusy}
                disabled={device.locate_requested}
                tooltip={device.locate_requested ? "Locate request already pending" : undefined}
              />
              <ActionButton
                icon={KeyRound}
                label="Rotate secret"
                onClick={() => setRotateSecretOpen(true)}
                canManage={canManage}
                actionBusy={actionBusy}
                tooltip="Invalidates the tablet's current secret and shows a new one once"
              />
              <ActionButton
                icon={Link2Off}
                label="Unpair"
                onClick={() => setConfirmAction("unpair")}
                canManage={canManage}
                actionBusy={actionBusy}
                tooltip="Clears the vehicle link; the tablet stays registered"
              />
              <ActionButton
                icon={device.revoked_at ? ShieldCheck : ShieldOff}
                label={device.revoked_at ? "Reinstate" : "Revoke"}
                onClick={() => setConfirmAction(device.revoked_at ? "reinstate" : "revoke")}
                canManage={canManage}
                actionBusy={actionBusy}
                tooltip={
                  device.revoked_at
                    ? "Lets this tablet heartbeat and answer commands again"
                    : "Reversibly stops this tablet from heartbeating or answering commands"
                }
              />
              <ActionButton
                icon={Trash2}
                label="Delete"
                variant="destructive"
                onClick={() => setConfirmAction("delete")}
                canManage={canManage}
                actionBusy={actionBusy}
                tooltip="Permanently unregisters this device -- cannot be undone"
              />
            </div>
          ) : undefined
        }
        tabs={tabs}
        backTo={{ label: "Fleet & Drivers › Devices", to: "/fleet?tab=devices" }}
        isLoading={deviceQuery.isLoading}
        error={deviceQuery.isError ? errorMessage(deviceQuery.error) : undefined}
      />

      <Modal
        open={rotateSecretOpen}
        onClose={closeRotateSecret}
        title={revealedSecret ? "New device secret" : "Rotate device secret?"}
        description={
          revealedSecret ? undefined : (
            <>
              Mints a fresh secret for {device?.android_id} and invalidates the one currently on the
              tablet. The tablet will need this new secret entered again (or to re-pair) before it can
              heartbeat, locate, or answer commands. This does not change which vehicle it's paired to.
            </>
          )
        }
        footer={
          revealedSecret ? (
            <Button onClick={closeRotateSecret}>Done</Button>
          ) : (
            <>
              <Button variant="outline" onClick={closeRotateSecret}>
                Cancel
              </Button>
              <Button onClick={confirmRotateSecret} disabled={rotateSecret.isPending}>
                {rotateSecret.isPending ? "Rotating…" : "Rotate secret"}
              </Button>
            </>
          )
        }
      >
        {revealedSecret && (
          <div className="flex items-center gap-2 rounded-md border border-border bg-muted p-3">
            <code className="flex-1 break-all font-mono text-xs">{revealedSecret}</code>
            <Button variant="ghost" size="icon" aria-label="Copy new secret" onClick={copySecret}>
              <Copy className="h-4 w-4" />
            </Button>
          </div>
        )}
        {revealedSecret && (
          <p className="mt-3 text-xs text-muted-foreground">
            Shown once. It will not be shown again after you close this dialog -- copy it now.
          </p>
        )}
      </Modal>

      <Modal
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        title={
          confirmAction === "unpair"
            ? "Clear vehicle link?"
            : confirmAction === "revoke"
              ? "Revoke device?"
              : confirmAction === "reinstate"
                ? "Reinstate device?"
                : "Delete device"
        }
        description={
          confirmAction === "unpair"
            ? `${device?.android_id} stays registered but is no longer linked to a vehicle.`
            : confirmAction === "revoke"
              ? `${device?.android_id}'s heartbeat, locate and command endpoints stop answering it. This is reversible -- re-pair it with a fresh code, or Reinstate here, to bring it back.`
              : confirmAction === "reinstate"
                ? `${device?.android_id} can heartbeat and answer commands again.`
                : `This unregisters ${device?.android_id}. This can't be undone.`
        }
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmAction(null)}>
              Cancel
            </Button>
            <Button
              variant={confirmAction === "delete" || confirmAction === "revoke" ? "destructive" : "primary"}
              onClick={runConfirmedAction}
              disabled={actionBusy}
            >
              {confirmAction === "unpair"
                ? "Clear link"
                : confirmAction === "revoke"
                  ? "Revoke"
                  : confirmAction === "reinstate"
                    ? "Reinstate"
                    : "Delete"}
            </Button>
          </>
        }
      />
    </>
  );
}
