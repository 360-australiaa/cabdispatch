import { useMemo, useRef, useState, type ChangeEvent } from "react";
import { Camera, Plus } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Modal, Select, Table, type TableColumn } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { useCreateDriver, useDrivers, useUploadDriverPhoto, useVehicleOptions, type DriverFilters, PAGE_LIMIT } from "./api";
import { DriverAvatar } from "./DriverAvatar";
import { PaginationBar } from "./PaginationBar";
import { errorMessage, formatDateTime, truncateId } from "./format";
import type { Driver } from "./types";

const PHOTO_UPLOAD_ROLES = new Set(["owner", "admin", "dispatcher"]);

const ON_SHIFT_OPTIONS = [
  { value: "true", label: "On shift" },
  { value: "false", label: "Off shift" },
];

/** Controlled form state for the "Add driver" modal (POST /v1/users, role
 * fixed to "driver"). Everything is a string for controlled inputs; the
 * optional fields are sent as null when blank (see useCreateDriver). */
interface CreateDriverFormValues {
  name: string;
  email: string;
  password: string;
  phone: string;
  driver_licence_no: string;
}

const EMPTY_CREATE_DRIVER_FORM: CreateDriverFormValues = {
  name: "",
  email: "",
  password: "",
  phone: "",
  driver_licence_no: "",
};

export function DriversPanel() {
  const { user } = useAuth();
  const canUploadPhoto = Boolean(user && PHOTO_UPLOAD_ROLES.has(user.role));
  const [skip, setSkip] = useState(0);
  const [statusSearch, setStatusSearch] = useState("");
  const [onShiftFilter, setOnShiftFilter] = useState("");
  const [selected, setSelected] = useState<Driver | null>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const uploadPhoto = useUploadDriverPhoto();

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateDriverFormValues>(EMPTY_CREATE_DRIVER_FORM);
  const [createError, setCreateError] = useState<string | null>(null);
  const createDriver = useCreateDriver();

  const filters: DriverFilters = useMemo(
    () => ({
      status: statusSearch.trim() || undefined,
      on_shift: onShiftFilter === "" ? undefined : onShiftFilter === "true",
    }),
    [statusSearch, onShiftFilter],
  );

  const driversQuery = useDrivers(skip, filters);
  const vehicleOptionsQuery = useVehicleOptions();

  async function handlePhotoSelected(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file || !selected) return;
    setPhotoError(null);
    try {
      await uploadPhoto.mutateAsync({ userId: selected.id, file });
    } catch (err) {
      setPhotoError(errorMessage(err));
    }
  }

  function openCreate() {
    setCreateForm(EMPTY_CREATE_DRIVER_FORM);
    setCreateError(null);
    setCreateOpen(true);
  }

  async function submitCreate() {
    setCreateError(null);
    try {
      await createDriver.mutateAsync({
        name: createForm.name,
        email: createForm.email,
        password: createForm.password,
        phone: createForm.phone || undefined,
        driver_licence_no: createForm.driver_licence_no || undefined,
      });
      setCreateOpen(false);
      setCreateForm(EMPTY_CREATE_DRIVER_FORM);
    } catch (err) {
      setCreateError(errorMessage(err));
    }
  }

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicleOptionsQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehicleOptionsQuery.data]);

  const columns: TableColumn<Driver>[] = [
    {
      key: "photo",
      header: "",
      className: "w-12",
      render: (d) => <DriverAvatar userId={d.id} name={d.name} size="h-8 w-8" />,
    },
    { key: "name", header: "Name", sortable: true, render: (d) => <span className="font-medium">{d.name}</span> },
    { key: "phone", header: "Phone", render: (d) => d.phone || "—" },
    { key: "user_status", header: "Status", render: (d) => <Badge variant="outline">{d.user_status}</Badge> },
    {
      key: "on_shift",
      header: "Shift",
      render: (d) => <Badge variant={d.on_shift ? "success" : "default"}>{d.on_shift ? "On shift" : "Off shift"}</Badge>,
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (d) => (d.vehicle_id ? vehicleRegoById.get(d.vehicle_id) ?? d.vehicle_id : "—"),
    },
    { key: "shift_start_at", header: "Shift started", render: (d) => formatDateTime(d.shift_start_at) },
    { key: "current_trip_id", header: "Current trip", render: (d) => truncateId(d.current_trip_id) },
  ];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-48">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Status</label>
            <Input
              placeholder="e.g. active"
              value={statusSearch}
              onChange={(e) => {
                setSkip(0);
                setStatusSearch(e.target.value);
              }}
            />
          </div>
          <div className="w-44">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Shift</label>
            <Select
              placeholder="All drivers"
              options={ON_SHIFT_OPTIONS}
              value={onShiftFilter}
              onChange={(e) => {
                setSkip(0);
                setOnShiftFilter(e.target.value);
              }}
            />
          </div>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> Add driver
        </Button>
      </div>

      {driversQuery.isError ? (
        <Card>
          <CardContent className="pt-4 text-sm text-destructive">
            Failed to load drivers: {errorMessage(driversQuery.error)}
          </CardContent>
        </Card>
      ) : (
        <>
          <Table
            columns={columns}
            data={driversQuery.data?.items ?? []}
            rowKey={(d) => d.id}
            isLoading={driversQuery.isLoading}
            onRowClick={setSelected}
            emptyState="No drivers match these filters."
          />
          <PaginationBar
            skip={skip}
            limit={PAGE_LIMIT}
            total={driversQuery.data?.total ?? 0}
            onSkipChange={setSkip}
          />
        </>
      )}

      <Modal
        open={selected !== null}
        onClose={() => {
          setSelected(null);
          setPhotoError(null);
        }}
        title={selected?.name}
        description="Read-only driver detail"
        footer={
          <Button
            variant="outline"
            onClick={() => {
              setSelected(null);
              setPhotoError(null);
            }}
          >
            Close
          </Button>
        }
      >
        {selected && (
          <>
            <div className="mb-4 flex items-center gap-4">
              <DriverAvatar userId={selected.id} name={selected.name} size="h-16 w-16" />
              {canUploadPhoto && (
                <div>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={handlePhotoSelected}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={uploadPhoto.isPending}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Camera className="h-3.5 w-3.5" />
                    {uploadPhoto.isPending ? "Uploading..." : "Upload photo"}
                  </Button>
                  {photoError && <p className="mt-1 text-xs text-destructive">{photoError}</p>}
                </div>
              )}
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <div>
              <dt className="text-xs text-muted-foreground">Phone</dt>
              <dd>{selected.phone || "—"}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Status</dt>
              <dd>{selected.user_status}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">On shift</dt>
              <dd>{selected.on_shift ? "Yes" : "No"}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Vehicle</dt>
              <dd>{selected.vehicle_id ? vehicleRegoById.get(selected.vehicle_id) ?? selected.vehicle_id : "—"}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Shift started</dt>
              <dd>{formatDateTime(selected.shift_start_at)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Current trip</dt>
              <dd>{truncateId(selected.current_trip_id, 12)}</dd>
            </div>
            </dl>
          </>
        )}
      </Modal>

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Add driver"
        description="Creates a driver-role user (POST /v1/users)."
        footer={
          <>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={submitCreate}
              disabled={
                !createForm.name.trim() ||
                !createForm.email.trim() ||
                createForm.password.length < 6 ||
                createDriver.isPending
              }
            >
              {createDriver.isPending ? "Creating..." : "Create driver"}
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Name *</label>
            <Input
              value={createForm.name}
              onChange={(e) => setCreateForm((f) => ({ ...f, name: e.target.value }))}
              maxLength={255}
              required
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Email *</label>
            <Input
              type="email"
              value={createForm.email}
              onChange={(e) => setCreateForm((f) => ({ ...f, email: e.target.value }))}
              required
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Password / PIN *</label>
            <Input
              type="password"
              value={createForm.password}
              onChange={(e) => setCreateForm((f) => ({ ...f, password: e.target.value }))}
              minLength={6}
              required
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Becomes the meter app login PIN for this driver — the tablet keypad is numeric only, so use digits.
              Minimum 6 characters.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Phone</label>
            <Input
              value={createForm.phone}
              onChange={(e) => setCreateForm((f) => ({ ...f, phone: e.target.value }))}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver licence no.</label>
            <Input
              value={createForm.driver_licence_no}
              onChange={(e) => setCreateForm((f) => ({ ...f, driver_licence_no: e.target.value }))}
            />
          </div>
        </div>
        {createError && <p className="mt-3 text-sm text-destructive">{createError}</p>}
      </Modal>
    </div>
  );
}
