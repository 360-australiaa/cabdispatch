import { useState } from "react";
import { AlertTriangle, Building2, Car, Plus, Route, Siren, Users } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  Modal,
  PageHeader,
  Table,
  type TableColumn,
} from "@/components/ui";
import {
  useCreateTenant,
  usePlatformHealth,
  usePlatformTenants,
  useTenantSummary,
  PLATFORM_PAGE_LIMIT,
  type CreateTenantValues,
  type PlatformTenant,
} from "@/hooks/usePlatformConsole";
import { errorMessage, formatDateTime } from "./format";

const EMPTY_FORM: CreateTenantValues = {
  name: "",
  abn: "",
  tsp_number: "",
  bsp_number: "",
  plan: "standard",
};

function HealthSummary() {
  const healthQuery = usePlatformHealth();
  const health = healthQuery.data;

  const tiles = [
    { label: "Total tenants", value: health?.total_tenants, icon: Building2 },
    { label: "Total vehicles", value: health?.total_vehicles, icon: Car },
    { label: "Trips today", value: health?.total_trips_today, icon: Route },
  ];

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Platform health</CardTitle>
      </CardHeader>
      <CardContent>
        {healthQuery.isError ? (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            Failed to load platform health. Check the backend connection and try again.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            {tiles.map(({ label, value, icon: Icon }) => (
              <div
                key={label}
                className="flex items-center gap-3 rounded-md border border-border p-4"
              >
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand-lavender text-brand-primary">
                  <Icon className="h-4 w-4" />
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">{label}</p>
                  <p className="text-lg font-semibold text-foreground">
                    {healthQuery.isLoading ? "..." : (value ?? "-")}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** Read-only health rollup for one tenant, opened from a tenants-table row click. */
function TenantDetailModal({
  tenantId,
  tenantName,
  onClose,
}: {
  tenantId: string | null;
  tenantName: string | undefined;
  onClose: () => void;
}) {
  const summaryQuery = useTenantSummary(tenantId);
  const summary = summaryQuery.data;

  const tiles = [
    { label: "Vehicles", value: summary?.vehicle_count, icon: Car },
    { label: "Drivers", value: summary?.driver_count, icon: Users },
    { label: "Trips (last 30 days)", value: summary?.trip_count_last_30_days, icon: Route },
    { label: "Active duress events", value: summary?.active_duress_count, icon: Siren },
  ];

  return (
    <Modal
      open={tenantId != null}
      onClose={onClose}
      title={tenantName ?? "Tenant"}
      description="Health rollup for this tenant."
    >
      {summaryQuery.isError && (
        <p className="flex items-center gap-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          Failed to load this tenant's summary. Check the backend connection and try again.
        </p>
      )}
      {!summaryQuery.isError && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {tiles.map(({ label, value, icon: Icon }) => (
            <div
              key={label}
              className="flex items-center gap-3 rounded-md border border-border p-4"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand-lavender text-brand-primary">
                <Icon className="h-4 w-4" />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">{label}</p>
                <p className="text-lg font-semibold text-foreground">
                  {summaryQuery.isLoading ? "..." : (value ?? "-")}
                </p>
              </div>
            </div>
          ))}
        </div>
      )}
    </Modal>
  );
}

/** Platform admin console at /platform. Owner-only (see PlatformOwnerRoute).
 * Cross-tenant tenant list + onboarding + platform-wide health, backed by
 * app/api/v1/platform.py (prefix /v1/platform). */
export default function PlatformConsolePage() {
  const [skip, setSkip] = useState(0);
  const tenantsQuery = usePlatformTenants(skip);
  const createTenant = useCreateTenant();

  const [formOpen, setFormOpen] = useState(false);
  const [formValues, setFormValues] = useState<CreateTenantValues>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);

  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);

  function openCreate() {
    setFormValues(EMPTY_FORM);
    setFormError(null);
    setFormOpen(true);
  }

  async function submitForm() {
    setFormError(null);
    if (!formValues.name.trim()) {
      setFormError("name: field required");
      return;
    }
    try {
      await createTenant.mutateAsync(formValues);
      setFormOpen(false);
    } catch (err) {
      setFormError(errorMessage(err));
    }
  }

  const columns: TableColumn<PlatformTenant>[] = [
    {
      key: "name",
      header: "Tenant",
      sortable: true,
      render: (t) => <span className="font-medium">{t.name}</span>,
    },
    {
      key: "plan",
      header: "Plan",
      sortable: true,
      render: (t) => <Badge variant="outline">{t.plan}</Badge>,
    },
    {
      key: "created_at",
      header: "Created",
      sortable: true,
      sortAccessor: (t) => new Date(t.created_at),
      render: (t) => formatDateTime(t.created_at),
    },
  ];

  const total = tenantsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PLATFORM_PAGE_LIMIT));
  const page = Math.floor(skip / PLATFORM_PAGE_LIMIT);

  return (
    <div>
      <PageHeader
        title="Platform Admin"
        description="Cross-tenant onboarding and platform-wide health. Visible to the platform owner only."
        actions={
          <Button onClick={openCreate}>
            <Plus className="h-4 w-4" />
            Create tenant
          </Button>
        }
      />

      <HealthSummary />

      <Card>
        <CardHeader>
          <CardTitle>Tenants</CardTitle>
        </CardHeader>
        <CardContent>
          {tenantsQuery.isError && (
            <p className="mb-3 flex items-center gap-2 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4 shrink-0" />
              Failed to load tenants. Check the backend connection and try again.
            </p>
          )}
          <Table
            columns={columns}
            data={tenantsQuery.data?.items ?? []}
            rowKey={(t) => t.id}
            isLoading={tenantsQuery.isLoading}
            emptyState={
              tenantsQuery.isError ? "Couldn't load tenants." : "No tenants yet."
            }
            onRowClick={(t) => setSelectedTenantId(t.id)}
          />
          {pageCount > 1 && (
            <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
              <span>
                Page {page + 1} of {pageCount}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={skip === 0}
                  onClick={() => setSkip((s) => Math.max(0, s - PLATFORM_PAGE_LIMIT))}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= pageCount - 1}
                  onClick={() => setSkip((s) => s + PLATFORM_PAGE_LIMIT)}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Modal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title="Create tenant"
        description="Onboard a new tenant onto the platform."
        footer={
          <>
            <Button variant="outline" onClick={() => setFormOpen(false)}>
              Cancel
            </Button>
            <Button onClick={submitForm} disabled={createTenant.isPending}>
              {createTenant.isPending ? "Creating..." : "Create tenant"}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          {formError && (
            <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {formError}
            </p>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">Name</span>
            <Input
              value={formValues.name}
              onChange={(e) => setFormValues((v) => ({ ...v, name: e.target.value }))}
              placeholder="Acme Taxis"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">Plan</span>
            <Input
              value={formValues.plan ?? ""}
              onChange={(e) => setFormValues((v) => ({ ...v, plan: e.target.value }))}
              placeholder="standard"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">ABN</span>
            <Input
              value={formValues.abn ?? ""}
              onChange={(e) => setFormValues((v) => ({ ...v, abn: e.target.value }))}
              placeholder="Optional"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">TSP number</span>
            <Input
              value={formValues.tsp_number ?? ""}
              onChange={(e) => setFormValues((v) => ({ ...v, tsp_number: e.target.value }))}
              placeholder="Optional"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-foreground">BSP number</span>
            <Input
              value={formValues.bsp_number ?? ""}
              onChange={(e) => setFormValues((v) => ({ ...v, bsp_number: e.target.value }))}
              placeholder="Optional"
            />
          </label>
        </div>
      </Modal>

      <TenantDetailModal
        tenantId={selectedTenantId}
        tenantName={tenantsQuery.data?.items.find((t) => t.id === selectedTenantId)?.name}
        onClose={() => setSelectedTenantId(null)}
      />
    </div>
  );
}
