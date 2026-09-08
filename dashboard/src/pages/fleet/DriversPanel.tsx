import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Plus } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Modal,
  Pagination,
  Select,
  Table,
  useToast,
  type TableColumn,
} from "@/components/ui";
import { useCreateDriver, useDrivers, useVehicleOptions, type DriverFilters, PAGE_LIMIT } from "./api";
import { DriverAvatar } from "./DriverAvatar";
import { errorMessage, formatDateTime, truncateId, vehicleLabel } from "./format";
import type { Driver } from "./types";

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

export function DriversPanel() {
  const navigate = useNavigate();
  const toast = useToast();
  const [skip, setSkip] = useState(0);
  const [statusSearch, setStatusSearch] = useState("");
  const [onShiftFilter, setOnShiftFilter] = useState("");

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

  // Server-side pagination is offset-based (`skip`), so the shared zero-based
  // `Pagination` needs the offset translated to a page index and back.
  const total = driversQuery.data?.total ?? 0;
  const page = Math.floor(skip / PAGE_LIMIT);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_LIMIT));
  const rangeStart = total === 0 ? 0 : skip + 1;
  const rangeEnd = Math.min(total, skip + PAGE_LIMIT);
  const vehicleOptionsQuery = useVehicleOptions();

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
      toast.success("Driver created", { description: createForm.name.trim() || undefined });
    } catch (err) {
      setCreateError(errorMessage(err));
      toast.error("Failed to create driver", { description: errorMessage(err) });
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
      render: (d) => vehicleLabel(d.vehicle_id, vehicleRegoById),
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
            onRowClick={(d) => navigate(`/drivers/${d.id}`)}
            emptyState="No drivers match these filters."
          />
          {/* Hidden while the whole list fits on one page. */}
          {total > PAGE_LIMIT && (
            <Pagination
              page={page}
              pageCount={pageCount}
              onPageChange={(p) => setSkip(p * PAGE_LIMIT)}
              summary={
                <>
                  {rangeStart}–{rangeEnd} of {total} (page {page + 1} of {pageCount})
                </>
              }
            />
          )}
        </>
      )}

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
