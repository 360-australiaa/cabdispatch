import { useMemo, useState } from "react";
import {
  BatteryFull,
  BatteryLow,
  BatteryMedium,
  BatteryWarning,
  Lock,
  MapPin,
  Pencil,
  Plus,
  Power,
  RefreshCw,
  Trash2,
  Unlock,
} from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Modal,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import {
  useCreateDevice,
  useDeleteDevice,
  useDevices,
  useForceUpdate,
  useKioskLock,
  useLocateDevice,
  useRebootDevice,
  useUpdateDevice,
  useVehicleOptions,
  type DeviceFilters,
  PAGE_LIMIT,
} from "./api";
import { PaginationBar } from "./PaginationBar";
import { errorMessage, formatDateTime, relativeFromNow } from "./format";
import { EMPTY_DEVICE_FORM, type Device, type DeviceFormValues } from "./types";

const KIOSK_FILTER_OPTIONS = [
  { value: "true", label: "Kiosk-locked" },
  { value: "false", label: "Unlocked" },
];

function BatteryIcon({ battery }: { battery: number | null }) {
  if (battery === null) return <span className="text-muted-foreground">—</span>;
  const Icon = battery <= 15 ? BatteryWarning : battery <= 40 ? BatteryLow : battery <= 75 ? BatteryMedium : BatteryFull;
  const color = battery <= 15 ? "text-destructive" : battery <= 40 ? "text-gold-500" : "text-success";
  return (
    <span className={`inline-flex items-center gap-1 ${color}`}>
      <Icon className="h-4 w-4" /> {battery}%
    </span>
  );
}

export function DevicesPanel() {
  const [skip, setSkip] = useState(0);
  const [androidIdSearch, setAndroidIdSearch] = useState("");
  const [kioskFilter, setKioskFilter] = useState("");

  const filters: DeviceFilters = useMemo(
    () => ({
      android_id: androidIdSearch.trim() || undefined,
      kiosk_locked: kioskFilter === "" ? undefined : kioskFilter === "true",
    }),
    [androidIdSearch, kioskFilter],
  );

  const devicesQuery = useDevices(skip, filters);
  const vehicleOptionsQuery = useVehicleOptions();

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicleOptionsQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehicleOptionsQuery.data]);

  const vehicleSelectOptions = useMemo(
    () => [
      { value: "", label: "Unassigned" },
      ...(vehicleOptionsQuery.data ?? []).map((v) => ({ value: v.id, label: v.rego })),
    ],
    [vehicleOptionsQuery.data],
  );

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Device | null>(null);
  const [formValues, setFormValues] = useState<DeviceFormValues>(EMPTY_DEVICE_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Device | null>(null);
  const [pendingActionId, setPendingActionId] = useState<string | null>(null);

  const createDevice = useCreateDevice();
  const updateDevice = useUpdateDevice();
  const deleteDevice = useDeleteDevice();
  const kioskLock = useKioskLock();
  const forceUpdate = useForceUpdate();
  const locateDevice = useLocateDevice();
  const rebootDevice = useRebootDevice();

  function openCreate() {
    setEditing(null);
    setFormValues(EMPTY_DEVICE_FORM);
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(d: Device) {
    setEditing(d);
    setFormValues({
      android_id: d.android_id,
      model: d.model ?? "",
      app_version: d.app_version ?? "",
      vehicle_id: d.vehicle_id ?? "",
      kiosk_locked: d.kiosk_locked,
    });
    setFormError(null);
    setFormOpen(true);
  }

  async function submitForm() {
    setFormError(null);
    try {
      if (editing) {
        await updateDevice.mutateAsync({ id: editing.id, values: formValues });
      } else {
        await createDevice.mutateAsync(formValues);
      }
      setFormOpen(false);
    } catch (err) {
      setFormError(errorMessage(err));
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    try {
      await deleteDevice.mutateAsync(deleteTarget.id);
      setDeleteTarget(null);
    } catch (err) {
      setFormError(errorMessage(err));
      setDeleteTarget(null);
    }
  }

  async function toggleKioskLock(d: Device) {
    setPendingActionId(d.id);
    try {
      await kioskLock.mutateAsync({ id: d.id, enabled: !d.kiosk_locked });
    } finally {
      setPendingActionId(null);
    }
  }

  async function triggerForceUpdate(d: Device) {
    setPendingActionId(d.id);
    try {
      await forceUpdate.mutateAsync(d.id);
    } finally {
      setPendingActionId(null);
    }
  }

  async function triggerLocate(d: Device) {
    setPendingActionId(d.id);
    try {
      await locateDevice.mutateAsync(d.id);
    } finally {
      setPendingActionId(null);
    }
  }

  async function triggerReboot(d: Device) {
    setPendingActionId(d.id);
    try {
      await rebootDevice.mutateAsync(d.id);
    } finally {
      setPendingActionId(null);
    }
  }

  const columns: TableColumn<Device>[] = [
    { key: "android_id", header: "Android ID", render: (d) => <span className="font-medium">{d.android_id}</span> },
    { key: "model", header: "Model", render: (d) => d.model || "—" },
    { key: "app_version", header: "App version", render: (d) => d.app_version || "—" },
    { key: "battery", header: "Battery", render: (d) => <BatteryIcon battery={d.battery} /> },
    { key: "network", header: "Network", render: (d) => (d.network ? <Badge variant="outline">{d.network}</Badge> : "—") },
    {
      key: "last_seen_at",
      header: "Last seen",
      sortable: true,
      sortAccessor: (d) => d.last_seen_at ?? "",
      render: (d) => (
        <span title={formatDateTime(d.last_seen_at)}>{relativeFromNow(d.last_seen_at)}</span>
      ),
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (d) => (d.vehicle_id ? vehicleRegoById.get(d.vehicle_id) ?? d.vehicle_id : "—"),
    },
    {
      key: "kiosk_locked",
      header: "Kiosk",
      render: (d) => <Badge variant={d.kiosk_locked ? "destructive" : "success"}>{d.kiosk_locked ? "Locked" : "Unlocked"}</Badge>,
    },
    {
      key: "force_update_pending",
      header: "Update",
      render: (d) => (d.force_update_pending ? <Badge variant="accent">Pending</Badge> : <span className="text-muted-foreground">Up to date</span>),
    },
    {
      key: "locate_requested",
      header: "Locate",
      render: (d) => (d.locate_requested ? <Badge variant="accent">Pending</Badge> : <span className="text-muted-foreground">—</span>),
    },
    {
      key: "reboot_requested",
      header: "Reboot",
      render: (d) => (d.reboot_requested ? <Badge variant="accent">Pending</Badge> : <span className="text-muted-foreground">—</span>),
    },
    {
      key: "actions",
      header: "",
      render: (d) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label={d.kiosk_locked ? "Unlock kiosk" : "Lock kiosk"}
            title={d.kiosk_locked ? "Remotely unlock kiosk mode" : "Remotely lock into kiosk mode"}
            onClick={(e) => {
              e.stopPropagation();
              toggleKioskLock(d);
            }}
            disabled={pendingActionId === d.id}
          >
            {d.kiosk_locked ? <Unlock className="h-4 w-4" /> : <Lock className="h-4 w-4" />}
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Force update"
            title={d.force_update_pending ? "Force-update already pending" : "Push a forced app update"}
            onClick={(e) => {
              e.stopPropagation();
              triggerForceUpdate(d);
            }}
            disabled={pendingActionId === d.id || d.force_update_pending}
          >
            <RefreshCw className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Locate device"
            title={d.locate_requested ? "Locate request already pending" : "Request a fresh location fix"}
            onClick={(e) => {
              e.stopPropagation();
              triggerLocate(d);
            }}
            disabled={pendingActionId === d.id || d.locate_requested}
          >
            <MapPin className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Reboot device"
            title={d.reboot_requested ? "Reboot already pending" : "Queue a remote reboot"}
            onClick={(e) => {
              e.stopPropagation();
              triggerReboot(d);
            }}
            disabled={pendingActionId === d.id || d.reboot_requested}
          >
            <Power className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Edit ${d.android_id}`}
            onClick={(e) => {
              e.stopPropagation();
              openEdit(d);
            }}
          >
            <Pencil className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Delete ${d.android_id}`}
            onClick={(e) => {
              e.stopPropagation();
              setDeleteTarget(d);
            }}
          >
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
        </div>
      ),
      className: "text-right",
    },
  ];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-52">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Search Android ID</label>
            <Input
              placeholder="e.g. AB12CD34"
              value={androidIdSearch}
              onChange={(e) => {
                setSkip(0);
                setAndroidIdSearch(e.target.value);
              }}
            />
          </div>
          <div className="w-44">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Kiosk state</label>
            <Select
              placeholder="All devices"
              options={KIOSK_FILTER_OPTIONS}
              value={kioskFilter}
              onChange={(e) => {
                setSkip(0);
                setKioskFilter(e.target.value);
              }}
            />
          </div>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> Register device
        </Button>
      </div>

      {devicesQuery.isError ? (
        <Card>
          <CardContent className="pt-4 text-sm text-destructive">
            Failed to load devices: {errorMessage(devicesQuery.error)}
          </CardContent>
        </Card>
      ) : (
        <>
          <Table
            columns={columns}
            data={devicesQuery.data?.items ?? []}
            rowKey={(d) => d.id}
            isLoading={devicesQuery.isLoading}
            onRowClick={openEdit}
            emptyState="No devices match these filters."
          />
          <PaginationBar
            skip={skip}
            limit={PAGE_LIMIT}
            total={devicesQuery.data?.total ?? 0}
            onSkipChange={setSkip}
          />
        </>
      )}

      <Modal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title={editing ? `Edit ${editing.android_id}` : "Register device"}
        footer={
          <>
            <Button variant="outline" onClick={() => setFormOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={submitForm}
              disabled={!formValues.android_id.trim() || createDevice.isPending || updateDevice.isPending}
            >
              {editing ? "Save changes" : "Register device"}
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Android ID *</label>
            <Input
              value={formValues.android_id}
              onChange={(e) => setFormValues((f) => ({ ...f, android_id: e.target.value }))}
              disabled={!!editing}
              maxLength={100}
              required
            />
            {editing && (
              <p className="mt-1 text-xs text-muted-foreground">
                Android ID identifies the physical unit and can't be changed — re-pair the device instead.
              </p>
            )}
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Model</label>
            <Input
              value={formValues.model}
              onChange={(e) => setFormValues((f) => ({ ...f, model: e.target.value }))}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">App version</label>
            <Input
              value={formValues.app_version}
              onChange={(e) => setFormValues((f) => ({ ...f, app_version: e.target.value }))}
            />
          </div>
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Linked vehicle</label>
            <Select
              options={vehicleSelectOptions}
              value={formValues.vehicle_id}
              onChange={(e) => setFormValues((f) => ({ ...f, vehicle_id: e.target.value }))}
            />
          </div>
          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-input"
              checked={formValues.kiosk_locked}
              onChange={(e) => setFormValues((f) => ({ ...f, kiosk_locked: e.target.checked }))}
            />
            Start kiosk-locked
          </label>
        </div>
        {formError && <p className="mt-3 text-sm text-destructive">{formError}</p>}
      </Modal>

      <Modal
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        title="Delete device"
        description={deleteTarget ? `This unregisters ${deleteTarget.android_id}. This can't be undone.` : ""}
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteDevice.isPending}>
              Delete
            </Button>
          </>
        }
      />
    </div>
  );
}
