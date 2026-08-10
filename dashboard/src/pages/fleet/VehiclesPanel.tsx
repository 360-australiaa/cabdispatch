import { useMemo, useState } from "react";
import { Archive, BarChart3, Copy, KeyRound, Pencil, Plus, Trash2 } from "lucide-react";
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
  downloadVehicleEvidencePack,
  useCreateVehicle,
  useDeleteVehicle,
  useDeviceOptions,
  useGeneratePairingCode,
  useUpdateVehicle,
  useVehicles,
  type VehicleFilters,
  PAGE_LIMIT,
} from "./api";
import { PaginationBar } from "./PaginationBar";
import { VehicleReportsModal } from "./VehicleReportsModal";
import { errorMessage, formatDateTime } from "./format";
import {
  EMPTY_VEHICLE_FORM,
  VEHICLE_CLASS_OPTIONS,
  VEHICLE_STATUS_OPTIONS,
  type Vehicle,
  type VehicleFormValues,
  type VehicleStatus,
} from "./types";

function statusBadgeVariant(status: VehicleStatus) {
  switch (status) {
    case "active":
      return "success" as const;
    case "maintenance":
      return "accent" as const;
    case "suspended":
      return "destructive" as const;
    default:
      return "outline" as const;
  }
}

export function VehiclesPanel() {
  const [skip, setSkip] = useState(0);
  const [regoSearch, setRegoSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [classFilter, setClassFilter] = useState("");

  const filters: VehicleFilters = useMemo(
    () => ({
      rego: regoSearch.trim() || undefined,
      status: statusFilter || undefined,
      vehicle_class: classFilter || undefined,
    }),
    [regoSearch, statusFilter, classFilter],
  );

  const vehiclesQuery = useVehicles(skip, filters);
  const deviceOptionsQuery = useDeviceOptions();

  const deviceByVehicleId = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of deviceOptionsQuery.data ?? []) {
      if (d.vehicle_id) map.set(d.vehicle_id, d.android_id);
    }
    return map;
  }, [deviceOptionsQuery.data]);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Vehicle | null>(null);
  const [formValues, setFormValues] = useState<VehicleFormValues>(EMPTY_VEHICLE_FORM);
  const [formError, setFormError] = useState<string | null>(null);

  const [deleteTarget, setDeleteTarget] = useState<Vehicle | null>(null);
  const [pairingTarget, setPairingTarget] = useState<Vehicle | null>(null);
  const [reportsTarget, setReportsTarget] = useState<Vehicle | null>(null);

  const createVehicle = useCreateVehicle();
  const updateVehicle = useUpdateVehicle();
  const deleteVehicle = useDeleteVehicle();
  const generatePairingCode = useGeneratePairingCode();

  const [exportingId, setExportingId] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  async function handleExportEvidencePack(v: Vehicle) {
    setExportError(null);
    setExportingId(v.id);
    try {
      await downloadVehicleEvidencePack(v);
    } catch (err) {
      setExportError(errorMessage(err));
    } finally {
      setExportingId(null);
    }
  }

  function openCreate() {
    setEditing(null);
    setFormValues(EMPTY_VEHICLE_FORM);
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(v: Vehicle) {
    setEditing(v);
    setFormValues({
      rego: v.rego,
      vin: v.vin ?? "",
      vehicle_class: v.vehicle_class,
      camera_serial: v.camera_serial ?? "",
      tracking_device_id: v.tracking_device_id ?? "",
      meter_device_id: v.meter_device_id ?? "",
      status: v.status,
    });
    setFormError(null);
    setFormOpen(true);
  }

  async function submitForm() {
    setFormError(null);
    try {
      if (editing) {
        await updateVehicle.mutateAsync({ id: editing.id, values: formValues });
      } else {
        await createVehicle.mutateAsync(formValues);
      }
      setFormOpen(false);
    } catch (err) {
      setFormError(errorMessage(err));
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    try {
      await deleteVehicle.mutateAsync(deleteTarget.id);
      setDeleteTarget(null);
    } catch (err) {
      setFormError(errorMessage(err));
      setDeleteTarget(null);
    }
  }

  const columns: TableColumn<Vehicle>[] = [
    { key: "rego", header: "Rego", sortable: true, render: (v) => <span className="font-medium">{v.rego}</span> },
    { key: "vin", header: "VIN", render: (v) => v.vin || "—" },
    {
      key: "vehicle_class",
      header: "Class",
      sortable: true,
      render: (v) => VEHICLE_CLASS_OPTIONS.find((o) => o.value === v.vehicle_class)?.label ?? v.vehicle_class,
    },
    {
      key: "status",
      header: "Status",
      sortable: true,
      render: (v) => <Badge variant={statusBadgeVariant(v.status)}>{v.status}</Badge>,
    },
    {
      key: "device",
      header: "Linked device",
      render: (v) => {
        const androidId = deviceByVehicleId.get(v.id);
        return androidId ? (
          <Badge variant="default">{androidId}</Badge>
        ) : (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-xs"
            onClick={(e) => {
              e.stopPropagation();
              setPairingTarget(v);
            }}
          >
            <KeyRound className="h-3.5 w-3.5" /> Pair
          </Button>
        );
      },
    },
    { key: "updated_at", header: "Updated", sortable: true, render: (v) => formatDateTime(v.updated_at) },
    {
      key: "actions",
      header: "",
      render: (v) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Operations reports for ${v.rego}`}
            onClick={(e) => {
              e.stopPropagation();
              setReportsTarget(v);
            }}
          >
            <BarChart3 className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Export evidence pack for ${v.rego}`}
            title="Export evidence pack"
            disabled={exportingId === v.id}
            onClick={(e) => {
              e.stopPropagation();
              handleExportEvidencePack(v);
            }}
          >
            <Archive className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Edit ${v.rego}`}
            onClick={(e) => {
              e.stopPropagation();
              openEdit(v);
            }}
          >
            <Pencil className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={`Delete ${v.rego}`}
            onClick={(e) => {
              e.stopPropagation();
              setDeleteTarget(v);
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
          <div className="w-48">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Search rego</label>
            <Input
              placeholder="e.g. ABC123"
              value={regoSearch}
              onChange={(e) => {
                setSkip(0);
                setRegoSearch(e.target.value);
              }}
            />
          </div>
          <div className="w-44">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Status</label>
            <Select
              placeholder="All statuses"
              options={VEHICLE_STATUS_OPTIONS}
              value={statusFilter}
              onChange={(e) => {
                setSkip(0);
                setStatusFilter(e.target.value);
              }}
            />
          </div>
          <div className="w-44">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Class</label>
            <Select
              placeholder="All classes"
              options={VEHICLE_CLASS_OPTIONS}
              value={classFilter}
              onChange={(e) => {
                setSkip(0);
                setClassFilter(e.target.value);
              }}
            />
          </div>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> Add vehicle
        </Button>
      </div>

      {exportError && (
        <Card className="mb-4 border-destructive/40">
          <CardContent className="pt-4 text-sm text-destructive">
            Failed to export evidence pack: {exportError}
          </CardContent>
        </Card>
      )}

      {vehiclesQuery.isError ? (
        <Card>
          <CardContent className="pt-4 text-sm text-destructive">
            Failed to load vehicles: {errorMessage(vehiclesQuery.error)}
          </CardContent>
        </Card>
      ) : (
        <>
          <Table
            columns={columns}
            data={vehiclesQuery.data?.items ?? []}
            rowKey={(v) => v.id}
            isLoading={vehiclesQuery.isLoading}
            onRowClick={openEdit}
            emptyState="No vehicles match these filters."
          />
          <PaginationBar
            skip={skip}
            limit={PAGE_LIMIT}
            total={vehiclesQuery.data?.total ?? 0}
            onSkipChange={setSkip}
          />
        </>
      )}

      <Modal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title={editing ? `Edit ${editing.rego}` : "Add vehicle"}
        footer={
          <>
            <Button variant="outline" onClick={() => setFormOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={submitForm}
              disabled={
                !formValues.rego.trim() || createVehicle.isPending || updateVehicle.isPending
              }
            >
              {editing ? "Save changes" : "Create vehicle"}
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Rego *</label>
            <Input
              value={formValues.rego}
              onChange={(e) => setFormValues((f) => ({ ...f, rego: e.target.value }))}
              maxLength={20}
              required
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">VIN</label>
            <Input
              value={formValues.vin}
              onChange={(e) => setFormValues((f) => ({ ...f, vin: e.target.value }))}
              maxLength={32}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Class</label>
            <Select
              options={VEHICLE_CLASS_OPTIONS}
              value={formValues.vehicle_class}
              onChange={(e) =>
                setFormValues((f) => ({ ...f, vehicle_class: e.target.value as VehicleFormValues["vehicle_class"] }))
              }
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Status</label>
            <Select
              options={VEHICLE_STATUS_OPTIONS}
              value={formValues.status}
              onChange={(e) =>
                setFormValues((f) => ({ ...f, status: e.target.value as VehicleFormValues["status"] }))
              }
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Camera serial</label>
            <Input
              value={formValues.camera_serial}
              onChange={(e) => setFormValues((f) => ({ ...f, camera_serial: e.target.value }))}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Tracking device ID</label>
            <Input
              value={formValues.tracking_device_id}
              onChange={(e) => setFormValues((f) => ({ ...f, tracking_device_id: e.target.value }))}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Meter device ID</label>
            <Input
              value={formValues.meter_device_id}
              onChange={(e) => setFormValues((f) => ({ ...f, meter_device_id: e.target.value }))}
            />
          </div>
        </div>
        {formError && <p className="mt-3 text-sm text-destructive">{formError}</p>}
      </Modal>

      <Modal
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        title="Delete vehicle"
        description={deleteTarget ? `This permanently removes ${deleteTarget.rego}. This can't be undone.` : ""}
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteVehicle.isPending}>
              Delete
            </Button>
          </>
        }
      />

      <Modal
        open={pairingTarget !== null}
        onClose={() => {
          setPairingTarget(null);
          generatePairingCode.reset();
        }}
        title={pairingTarget ? `Pair a device to ${pairingTarget.rego}` : "Pair a device"}
        description="Generate a one-time code for the driver's tablet to enter during device registration."
        footer={
          generatePairingCode.data ? (
            <Button variant="outline" onClick={() => setPairingTarget(null)}>
              Done
            </Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => setPairingTarget(null)}>
                Cancel
              </Button>
              <Button
                onClick={() => pairingTarget && generatePairingCode.mutate(pairingTarget.id)}
                disabled={generatePairingCode.isPending}
              >
                Generate code
              </Button>
            </>
          )
        }
      >
        {generatePairingCode.data ? (
          <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-muted p-4">
            <div>
              <p className="font-mono text-2xl tracking-widest">{generatePairingCode.data.code}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                Expires {formatDateTime(generatePairingCode.data.expires_at)}
              </p>
            </div>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Copy code"
              onClick={() => navigator.clipboard.writeText(generatePairingCode.data!.code)}
            >
              <Copy className="h-4 w-4" />
            </Button>
          </div>
        ) : generatePairingCode.isError ? (
          <p className="text-sm text-destructive">{errorMessage(generatePairingCode.error)}</p>
        ) : (
          <p className="text-sm text-muted-foreground">
            The device enters this code in the kiosk app to bind itself to the vehicle.
          </p>
        )}
      </Modal>
      <VehicleReportsModal vehicle={reportsTarget} onClose={() => setReportsTarget(null)} />
    </div>
  );
}
