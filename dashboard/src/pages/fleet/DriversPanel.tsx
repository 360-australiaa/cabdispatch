import { useMemo, useState } from "react";
import { Info } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Modal, Select, Table, type TableColumn } from "@/components/ui";
import { useDrivers, useVehicleOptions, type DriverFilters, PAGE_LIMIT } from "./api";
import { PaginationBar } from "./PaginationBar";
import { errorMessage, formatDateTime, truncateId } from "./format";
import type { Driver } from "./types";

const ON_SHIFT_OPTIONS = [
  { value: "true", label: "On shift" },
  { value: "false", label: "Off shift" },
];

export function DriversPanel() {
  const [skip, setSkip] = useState(0);
  const [statusSearch, setStatusSearch] = useState("");
  const [onShiftFilter, setOnShiftFilter] = useState("");
  const [selected, setSelected] = useState<Driver | null>(null);

  const filters: DriverFilters = useMemo(
    () => ({
      status: statusSearch.trim() || undefined,
      on_shift: onShiftFilter === "" ? undefined : onShiftFilter === "true",
    }),
    [statusSearch, onShiftFilter],
  );

  const driversQuery = useDrivers(skip, filters);
  const vehicleOptionsQuery = useVehicleOptions();

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicleOptionsQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehicleOptionsQuery.data]);

  const columns: TableColumn<Driver>[] = [
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
      <Card className="mb-4 border-brand-lavender bg-brand-lavender/40">
        <CardContent className="flex items-start gap-2 pt-4 text-sm text-brand-primary">
          <Info className="mt-0.5 h-4 w-4 shrink-0" />
          <p>
            Driver records are a read-only live-status rollup (<code>GET /v1/drivers</code>). The backend does not
            currently expose create/update/delete for the user domain, so this panel can only view drivers — to add,
            edit, or deactivate a driver, use the backend's user-management tooling directly.
          </p>
        </CardContent>
      </Card>

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
        <Button variant="outline" disabled title="Not supported by the backend API — /v1/drivers is read-only">
          Add driver
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
        onClose={() => setSelected(null)}
        title={selected?.name}
        description="Read-only driver detail"
        footer={
          <Button variant="outline" onClick={() => setSelected(null)}>
            Close
          </Button>
        }
      >
        {selected && (
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
        )}
      </Modal>
    </div>
  );
}
