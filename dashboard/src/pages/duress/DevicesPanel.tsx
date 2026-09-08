import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Plus, Trash2 } from "lucide-react";
import { Badge, Button, Card, CardContent, Modal, Table } from "@/components/ui";
import type { TableColumn } from "@/components/ui/Table";
import { useAuth } from "@/lib/auth";
import { deleteDuressDevice, listDuressDevices, listVehicleOptionsForDeviceLink } from "./api";
import { DeviceFormModal } from "./DeviceFormModal";
import { RotateSecretModal } from "./RotateSecretModal";
import { errorMessage, formatDateTime } from "./format";
import type { DuressDevice } from "./types";

const PAGE_SIZE = 20;

/** Mirrors `backend/app/api/v1/duress_device.py`'s `_DISPATCH_ROLES` gate on
 * create/update/rotate-secret/delete — list/get is any authenticated user
 * (read-only visibility), so this only guards the WRITE affordances. Without
 * it, a driver would see fully-enabled register/edit/rotate/delete buttons
 * that always 403. */
const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);

/**
 * Hardware provisioning surface for the physical CT-DPD-01 duress
 * panic-button devices — full CRUD against `/v1/duress-devices`
 * (`backend/app/api/v1/duress_device.py`), which had zero dashboard call
 * sites before this. This is the surface that makes `EventDetailPanel`'s
 * "Call the cab" action ever reachable in practice: that action requires
 * `event.device_id` to resolve to a device row with a `phone_number` on
 * file, which can only exist if a device has been registered and linked to
 * a vehicle here first.
 */
export function DevicesPanel() {
  const { user } = useAuth();
  const canManage = !!user && MANAGE_ROLES.has(user.role);
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [formTarget, setFormTarget] = useState<DuressDevice | "create" | null>(null);
  const [rotateTarget, setRotateTarget] = useState<DuressDevice | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DuressDevice | null>(null);

  const devicesQuery = useQuery({
    queryKey: ["duress-devices", page],
    queryFn: () => listDuressDevices({ limit: PAGE_SIZE, offset: page * PAGE_SIZE }),
    placeholderData: (prev) => prev,
  });

  const vehicleOptionsQuery = useQuery({
    queryKey: ["duress-devices", "vehicle-options"],
    queryFn: listVehicleOptionsForDeviceLink,
  });

  const vehicleRegoById = useMemo(() => {
    const map = new Map<string, string>();
    for (const v of vehicleOptionsQuery.data ?? []) map.set(v.id, v.rego);
    return map;
  }, [vehicleOptionsQuery.data]);

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDuressDevice(id),
    onSuccess: () => {
      setDeleteTarget(null);
      queryClient.invalidateQueries({ queryKey: ["duress-devices"] });
    },
  });

  const total = devicesQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<DuressDevice>[] = [
    {
      key: "device_code",
      header: "Device code",
      render: (row) => <span className="font-mono text-xs">{row.device_code}</span>,
    },
    {
      key: "vehicle_id",
      header: "Vehicle",
      render: (row) =>
        row.vehicle_id ? (
          vehicleRegoById.get(row.vehicle_id) ?? (
            <span className="font-mono text-xs text-muted-foreground">{row.vehicle_id}</span>
          )
        ) : (
          <span className="text-muted-foreground">— unlinked —</span>
        ),
    },
    {
      key: "phone_number",
      header: "Phone",
      render: (row) => row.phone_number ?? <span className="text-muted-foreground">—</span>,
    },
    {
      key: "battery_pct",
      header: "Battery",
      render: (row) =>
        row.battery_pct == null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          `${row.battery_pct}%${row.on_battery ? " (on backup)" : ""}`
        ),
    },
    {
      key: "last_seen_at",
      header: "Last seen",
      render: (row) => formatDateTime(row.last_seen_at),
      sortable: true,
      sortAccessor: (row) => row.last_seen_at ?? "",
    },
    {
      key: "active",
      header: "Status",
      render: (row) => (
        <Badge variant={row.active ? "success" : "outline"}>
          {row.active ? "Active" : "Inactive"}
        </Badge>
      ),
    },
  ];

  if (canManage) {
    columns.push({
      key: "actions",
      header: "",
      className: "text-right",
      render: (row: DuressDevice) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            aria-label={`Edit ${row.device_code}`}
            onClick={(e) => {
              e.stopPropagation();
              setFormTarget(row);
            }}
          >
            <Pencil className="h-3.5 w-3.5" />
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            aria-label={`Rotate secret for ${row.device_code}`}
            onClick={(e) => {
              e.stopPropagation();
              setRotateTarget(row);
            }}
          >
            <KeyRound className="h-3.5 w-3.5" />
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            aria-label={`Delete ${row.device_code}`}
            onClick={(e) => {
              e.stopPropagation();
              setDeleteTarget(row);
            }}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      ),
    });
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex items-center justify-between gap-3 pt-4">
          <p className="text-sm text-muted-foreground">
            Physical CT-DPD-01 panic-button units. Linking one to a vehicle here is what makes
            that vehicle's duress events resolvable to a callable phone (the "Call the cab"
            action).
          </p>
          {canManage && (
            <Button onClick={() => setFormTarget("create")}>
              <Plus className="h-4 w-4" /> Register device
            </Button>
          )}
        </CardContent>
      </Card>

      {!canManage && (
        <p className="text-xs text-muted-foreground">
          Registering, editing, rotating secrets, or deleting a device is restricted to
          owner/admin/dispatcher roles. You can still view the list below.
        </p>
      )}

      {devicesQuery.isError && (
        <p className="text-sm text-destructive">
          Failed to load duress devices. Check the backend connection and try again.
        </p>
      )}

      <Table
        columns={columns}
        data={devicesQuery.data?.items ?? []}
        rowKey={(row) => row.id}
        isLoading={devicesQuery.isLoading}
        emptyState="No duress devices registered yet."
      />

      {pageCount > 1 && (
        <div className="flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page >= pageCount - 1}
              onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            >
              Next
            </Button>
          </div>
        </div>
      )}

      <DeviceFormModal
        device={formTarget === "create" ? null : formTarget}
        open={formTarget !== null}
        onClose={() => setFormTarget(null)}
        onSaved={() => setFormTarget(null)}
      />

      <RotateSecretModal
        device={rotateTarget}
        open={rotateTarget !== null}
        onClose={() => setRotateTarget(null)}
        onRotated={() => setRotateTarget(null)}
      />

      <Modal
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        title="Delete duress device?"
        description="This permanently removes the device record, including its provisioning secret. This cannot be undone. To temporarily reject the device without losing its history, use Edit → Active instead."
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deleteMutation.isError && (
          <p className="text-sm text-destructive">{errorMessage(deleteMutation.error)}</p>
        )}
      </Modal>
    </div>
  );
}
