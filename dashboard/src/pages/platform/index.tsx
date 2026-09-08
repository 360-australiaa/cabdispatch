import { useState } from "react";
import { AlertTriangle, Ban, CheckCircle2, Plus } from "lucide-react";
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
  Pagination,
  Table,
  useToast,
  type TableColumn,
} from "@/components/ui";
import {
  useCreateTenant,
  usePlatformTenants,
  useUpdateTenantStatus,
  PLATFORM_PAGE_LIMIT,
  type CreateTenantValues,
  type PlatformTenant,
  type TenantStatus,
} from "@/hooks/usePlatformConsole";
import { errorMessage, formatDateTime, tenantStatusBadgeVariant } from "./format";
import { HealthSummary } from "./HealthSummary";
import { BillingSummary } from "./BillingSummary";
import { AppReleasesSection } from "./AppReleasesSection";
import { TenantDetailModal } from "./TenantDetailModal";

const EMPTY_FORM: CreateTenantValues = {
  name: "",
  abn: "",
  tsp_number: "",
  bsp_number: "",
  plan: "standard",
};

/** Platform admin console at /platform. Owner-only (see PlatformOwnerRoute).
 * Cross-tenant tenant list + onboarding + platform-wide health, backed by
 * app/api/v1/platform.py (prefix /v1/platform). */
export default function PlatformConsolePage() {
  const toast = useToast();
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
    const tenantName = confirmingTenant.name;
    updateTenantStatus.mutate(
      { tenantId: confirmingTenant.id, status: nextStatus },
      {
        onSuccess: () => {
          setConfirmingTenant(null);
          toast.success(nextStatus === "suspended" ? "Tenant suspended" : "Tenant reactivated", {
            description: tenantName,
          });
        },
        onError: (err) =>
          toast.error(
            nextStatus === "suspended" ? "Failed to suspend tenant" : "Failed to reactivate tenant",
            { description: errorMessage(err) },
          ),
      },
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
      toast.success("Tenant created", { description: formValues.name.trim() || undefined });
    } catch (err) {
      setFormError(errorMessage(err));
      toast.error("Failed to create tenant", { description: errorMessage(err) });
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
      <AppReleasesSection />

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
            <Pagination
              page={page}
              pageCount={pageCount}
              onPageChange={(p) => setSkip(p * PLATFORM_PAGE_LIMIT)}
            />
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
