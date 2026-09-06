import { useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import { Camera, Plus } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Modal, Select, Table, type TableColumn } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import {
  useCreateDriver,
  useDriverCompliance,
  useDrivers,
  useUpdateDriverCompliance,
  useUploadDriverPhoto,
  useVehicleOptions,
  type DriverFilters,
  PAGE_LIMIT,
} from "./api";
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
 * optional fields are sent as null when blank (see useCreateDriver).
 * `driver_license_expiry`/`driver_authority_expiry` are `YYYY-MM-DD` from
 * `<input type="date">`, matching `UserCreate`'s `date | None` fields. */
interface CreateDriverFormValues {
  name: string;
  email: string;
  password: string;
  phone: string;
  driver_licence_no: string;
  driver_license_expiry: string;
  driver_authority_expiry: string;
}

const EMPTY_CREATE_DRIVER_FORM: CreateDriverFormValues = {
  name: "",
  email: "",
  password: "",
  phone: "",
  driver_licence_no: "",
  driver_license_expiry: "",
  driver_authority_expiry: "",
};

/** Controlled form state for editing an existing driver's compliance dates
 * (PATCH /v1/users/{id}) -- the only fields editable from this panel today.
 * Separate from CreateDriverFormValues since it's a different mutation
 * against a different, per-user endpoint (see useDriverCompliance's own doc
 * comment in api.ts for why). */
interface DriverComplianceFormValues {
  driver_license_expiry: string;
  driver_authority_expiry: string;
}

const EMPTY_COMPLIANCE_FORM: DriverComplianceFormValues = {
  driver_license_expiry: "",
  driver_authority_expiry: "",
};

export function DriversPanel() {
  const { user } = useAuth();
  const canUploadPhoto = Boolean(user && PHOTO_UPLOAD_ROLES.has(user.role));
  // PATCH /v1/users/{id} is owner/admin-gated server-side (see _require_admin
  // in backend/app/api/v1/users.py) -- narrower than the photo-upload roles
  // above, which also allow dispatcher.
  const canEditCompliance = Boolean(user && (user.role === "owner" || user.role === "admin"));
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

  const [complianceForm, setComplianceForm] = useState<DriverComplianceFormValues>(EMPTY_COMPLIANCE_FORM);
  const [complianceError, setComplianceError] = useState<string | null>(null);
  const [complianceSaved, setComplianceSaved] = useState(false);
  const driverComplianceQuery = useDriverCompliance(selected?.id ?? null);
  const updateDriverCompliance = useUpdateDriverCompliance();

  const filters: DriverFilters = useMemo(
    () => ({
      status: statusSearch.trim() || undefined,
      on_shift: onShiftFilter === "" ? undefined : onShiftFilter === "true",
    }),
    [statusSearch, onShiftFilter],
  );

  const driversQuery = useDrivers(skip, filters);
  const vehicleOptionsQuery = useVehicleOptions();

  // Reset to the freshly-fetched compliance dates whenever a different driver
  // is opened (or their record refetches) -- mirrors openEdit's pattern in
  // VehiclesPanel.tsx of seeding form state from the loaded record.
  useEffect(() => {
    if (driverComplianceQuery.data) {
      setComplianceForm({
        driver_license_expiry: driverComplianceQuery.data.driver_license_expiry ?? "",
        driver_authority_expiry: driverComplianceQuery.data.driver_authority_expiry ?? "",
      });
    }
  }, [driverComplianceQuery.data]);

  async function submitCompliance() {
    if (!selected) return;
    setComplianceError(null);
    setComplianceSaved(false);
    try {
      await updateDriverCompliance.mutateAsync({
        id: selected.id,
        values: {
          driver_license_expiry: complianceForm.driver_license_expiry || null,
          driver_authority_expiry: complianceForm.driver_authority_expiry || null,
        },
      });
      setComplianceSaved(true);
    } catch (err) {
      setComplianceError(errorMessage(err));
    }
  }

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
        driver_license_expiry: createForm.driver_license_expiry || undefined,
        driver_authority_expiry: createForm.driver_authority_expiry || undefined,
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
          setComplianceError(null);
          setComplianceSaved(false);
        }}
        title={selected?.name}
        description="Driver detail — most fields are read-only; compliance dates below are editable."
        footer={
          <Button
            variant="outline"
            onClick={() => {
              setSelected(null);
              setPhotoError(null);
              setComplianceError(null);
              setComplianceSaved(false);
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
            {/* Driver code first: it is the one thing an operator opens this
              * modal needing, since it's half of the driver's meter sign-in
              * (code + PIN) and appears nowhere else in the dashboard. Shown
              * monospace because it gets read off this screen and typed into a
              * tablet keypad. Falls back to an honest "—" rather than a
              * placeholder while the per-user fetch is in flight or if this
              * user genuinely has no code (non-driver roles). */}
            <div>
              <dt className="text-xs text-muted-foreground">Driver code (meter login)</dt>
              <dd className="font-mono">{driverComplianceQuery.data?.driver_code || "—"}</dd>
            </div>
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

            <div className="mt-4 border-t border-border pt-4">
              <h4 className="text-sm font-semibold text-foreground">Compliance dates</h4>
              <p className="mt-1 text-xs text-muted-foreground">
                Required under NSW Point to Point Transport regulation to keep this driver
                compliant. Cab Dispatch reminds you when these are expiring but does not verify or
                enforce them.
              </p>
              {driverComplianceQuery.isLoading ? (
                <p className="mt-3 text-xs text-muted-foreground">Loading…</p>
              ) : driverComplianceQuery.isError ? (
                <p className="mt-3 text-xs text-destructive">
                  Failed to load compliance dates: {errorMessage(driverComplianceQuery.error)}
                </p>
              ) : (
                <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div>
                    <label className="mb-1 block text-xs font-medium text-muted-foreground">
                      Driver licence expiry
                    </label>
                    <Input
                      type="date"
                      value={complianceForm.driver_license_expiry}
                      onChange={(e) =>
                        setComplianceForm((f) => ({ ...f, driver_license_expiry: e.target.value }))
                      }
                      disabled={!canEditCompliance}
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-muted-foreground">
                      Driver authority expiry
                    </label>
                    <Input
                      type="date"
                      value={complianceForm.driver_authority_expiry}
                      onChange={(e) =>
                        setComplianceForm((f) => ({ ...f, driver_authority_expiry: e.target.value }))
                      }
                      disabled={!canEditCompliance}
                    />
                    <p className="mt-1 text-xs text-muted-foreground">
                      NSW Point to Point driver authority — separate from the driving licence above.
                    </p>
                  </div>
                </div>
              )}
              {canEditCompliance && !driverComplianceQuery.isLoading && !driverComplianceQuery.isError && (
                <div className="mt-3 flex items-center gap-3">
                  <Button size="sm" onClick={submitCompliance} disabled={updateDriverCompliance.isPending}>
                    {updateDriverCompliance.isPending ? "Saving..." : "Save compliance dates"}
                  </Button>
                  {complianceSaved && !updateDriverCompliance.isPending && (
                    <span className="text-xs text-emerald-600 dark:text-emerald-400">Saved.</span>
                  )}
                </div>
              )}
              {complianceError && <p className="mt-2 text-xs text-destructive">{complianceError}</p>}
            </div>
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
          <div className="sm:col-span-2">
            <p className="text-xs text-muted-foreground">
              Required under NSW Point to Point Transport regulation to keep this driver compliant.
              Cab Dispatch reminds you when these are expiring but does not verify or enforce them.
              Both are optional — leave blank if unknown for now.
            </p>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver licence expiry</label>
            <Input
              type="date"
              value={createForm.driver_license_expiry}
              onChange={(e) => setCreateForm((f) => ({ ...f, driver_license_expiry: e.target.value }))}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">Driver authority expiry</label>
            <Input
              type="date"
              value={createForm.driver_authority_expiry}
              onChange={(e) => setCreateForm((f) => ({ ...f, driver_authority_expiry: e.target.value }))}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              NSW Point to Point driver authority — separate from the driving licence above.
            </p>
          </div>
        </div>
        {createError && <p className="mt-3 text-sm text-destructive">{createError}</p>}
      </Modal>
    </div>
  );
}
