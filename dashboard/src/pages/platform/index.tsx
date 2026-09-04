import { useState } from "react";
import {
  AlertTriangle,
  Ban,
  Building2,
  Car,
  CheckCircle2,
  CreditCard,
  Plus,
  Route,
  Siren,
  Users,
} from "lucide-react";
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
  usePlatformBillingSummary,
  usePlatformHealth,
  usePlatformTenants,
  useTenantBilling,
  useTenantSummary,
  useUpdateTenantStatus,
  PLATFORM_PAGE_LIMIT,
  type CreateTenantValues,
  type PlatformTenant,
  type TenantStatus,
} from "@/hooks/usePlatformConsole";
import { errorMessage, formatAud, formatDateTime, tenantStatusBadgeVariant } from "./format";

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

/** MRR headline + per-plan subscription counts, server-computed via
 * GET /v1/platform/billing/summary — never trusts anything client-supplied. */
function BillingSummary() {
  const billingQuery = usePlatformBillingSummary();
  const billing = billingQuery.data;
  const planEntries = Object.entries(billing?.plan_counts ?? {});

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Billing</CardTitle>
      </CardHeader>
      <CardContent>
        {billingQuery.isError ? (
          <p className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            Failed to load platform billing. Check the backend connection and try again.
          </p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div className="flex items-center gap-3 rounded-md border border-border p-4">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brand-lavender text-brand-primary">
                <CreditCard className="h-4 w-4" />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">MRR</p>
                <p className="text-lg font-semibold text-foreground">
                  {billingQuery.isLoading ? "..." : formatAud(billing?.mrr_aud)}
                </p>
              </div>
            </div>
            <div className="rounded-md border border-border p-4 sm:col-span-2">
              <p className="mb-2 text-xs text-muted-foreground">Active subscriptions by plan</p>
              {billingQuery.isLoading ? (
                <p className="text-sm text-muted-foreground">...</p>
              ) : planEntries.length === 0 ? (
                <p className="text-sm text-muted-foreground">No active subscriptions yet.</p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {planEntries.map(([plan, count]) => (
                    <Badge key={plan} variant="outline">
                      {plan}: {count}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** This tenant's subscriptions — the platform-owner's support-triage view,
 * so staff can review a network's billing without impersonating them via
 * the ?tenant_id= override every other endpoint supports. */
function TenantBillingSection({ tenantId }: { tenantId: string | null }) {
  const billingQuery = useTenantBilling(tenantId);
  const subscriptions = billingQuery.data ?? [];

  return (
    <div className="mt-4">
      <p className="mb-2 text-xs font-medium uppercase text-muted-foreground">Billing</p>
      {billingQuery.isError && (
        <p className="flex items-center gap-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          Failed to load this tenant's billing. Check the backend connection and try again.
        </p>
      )}
      {!billingQuery.isError && billingQuery.isLoading && (
        <p className="text-sm text-muted-foreground">Loading...</p>
      )}
      {!billingQuery.isError && !billingQuery.isLoading && subscriptions.length === 0 && (
        <p className="text-sm text-muted-foreground">No subscriptions for this tenant.</p>
      )}
      {subscriptions.length > 0 && (
        <div className="flex flex-col gap-2">
          {subscriptions.map((sub) => (
            <div
              key={sub.id}
              className="flex items-center justify-between rounded-md border border-border p-3 text-sm"
            >
              <span className="font-medium text-foreground">Vehicle {sub.vehicle_id}</span>
              <div className="flex items-center gap-2">
                <Badge variant="outline">{sub.plan}</Badge>
                <Badge variant={tenantStatusBadgeVariant(sub.status)}>{sub.status}</Badge>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
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

      <TenantBillingSection tenantId={tenantId} />
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
  const updateTenantStatus = useUpdateTenantStatus();

  const [formOpen, setFormOpen] = useState(false);
  const [formValues, setFormValues] = useState<CreateTenantValues>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);

  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);
  // Suspend/reactivate used to fire straight from the row button with no
  // confirmation step at all -- every other destructive-ish action in this
  // app (voucher delete, corp-account delete, MFA disable, branding reset)
  // goes through a confirm Modal first, and this one arguably has a bigger
  // blast radius than any of those (it locks every user at a real paying
  // tenant out of the whole platform).
  const [confirmingTenant, setConfirmingTenant] = useState<PlatformTenant | null>(null);

  function confirmToggleTenantStatus() {
    if (!confirmingTenant) return;
    const nextStatus: TenantStatus = confirmingTenant.status === "suspended" ? "active" : "suspended";
    updateTenantStatus.mutate(
      { tenantId: confirmingTenant.id, status: nextStatus },
      { onSuccess: () => setConfirmingTenant(null) },
    );
  }

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
      key: "status",
      header: "Status",
      sortable: true,
      render: (t) => <Badge variant={tenantStatusBadgeVariant(t.status)}>{t.status}</Badge>,
    },
    {
      key: "created_at",
      header: "Created",
      sortable: true,
      sortAccessor: (t) => new Date(t.created_at),
      render: (t) => formatDateTime(t.created_at),
    },
    {
      key: "actions",
      header: "",
      render: (t) => (
        <Button
          variant="outline"
          size="sm"
          disabled={updateTenantStatus.isPending}
          onClick={(e) => {
            e.stopPropagation();
            setConfirmingTenant(t);
          }}
        >
          {t.status === "suspended" ? (
            <>
              <CheckCircle2 className="h-4 w-4" />
              Reactivate
            </>
          ) : (
            <>
              <Ban className="h-4 w-4" />
              Suspend
            </>
          )}
        </Button>
      ),
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
      <BillingSummary />

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

      <Modal
        open={confirmingTenant != null}
        onClose={() => setConfirmingTenant(null)}
        title={confirmingTenant?.status === "suspended" ? "Reactivate tenant?" : "Suspend tenant?"}
        description={
          confirmingTenant?.status === "suspended"
            ? `${confirmingTenant?.name} regains access immediately.`
            : `Every user at ${confirmingTenant?.name} loses access immediately — this is not reversible from their side, only from here.`
        }
        footer={
          <>
            <Button variant="outline" onClick={() => setConfirmingTenant(null)}>
              Cancel
            </Button>
            <Button
              variant={confirmingTenant?.status === "suspended" ? "primary" : "destructive"}
              disabled={updateTenantStatus.isPending}
              onClick={confirmToggleTenantStatus}
            >
              {updateTenantStatus.isPending
                ? "Working…"
                : confirmingTenant?.status === "suspended"
                  ? "Reactivate"
                  : "Suspend"}
            </Button>
          </>
        }
      >
        {updateTenantStatus.isError && (
          <p className="text-sm text-destructive">This action failed. Refresh and try again.</p>
        )}
      </Modal>
    </div>
  );
}
