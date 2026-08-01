import { useMemo, useState, type FormEvent, type ReactNode } from "react";
import { isAxiosError } from "axios";
import { FileText, Plus, Receipt } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Modal,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import {
  formatAud,
  formatDate,
  useCancelSubscription,
  useCreateSubscription,
  useInvoices,
  useSubscriptions,
  useUpdateSubscription,
  useVehiclesForBilling,
  type BillingPlan,
  type InvoiceRead,
  type SubscriptionRead,
  type SubscriptionStatus,
} from "@/hooks/useBilling";

const PLAN_OPTIONS: { value: BillingPlan; label: string }[] = [
  { value: "basic", label: "Basic" },
  { value: "pro", label: "Pro" },
  { value: "enterprise", label: "Enterprise" },
];

const STATUS_OPTIONS: { value: SubscriptionStatus; label: string }[] = [
  { value: "trialing", label: "Trialing" },
  { value: "active", label: "Active" },
  { value: "past_due", label: "Past due" },
  { value: "canceled", label: "Canceled" },
  { value: "incomplete", label: "Incomplete" },
];

const STATUS_BADGE_VARIANT: Record<
  SubscriptionStatus,
  "success" | "accent" | "destructive" | "outline" | "default"
> = {
  trialing: "accent",
  active: "success",
  past_due: "destructive",
  canceled: "outline",
  incomplete: "outline",
};

const INVOICE_STATUS_VARIANT: Record<
  InvoiceRead["status"],
  "success" | "accent" | "destructive" | "outline" | "default"
> = {
  paid: "success",
  open: "accent",
  draft: "outline",
  void: "outline",
  uncollectible: "destructive",
};

const PAGE_SIZE = 10;

function apiErrorMessage(err: unknown, fallback: string): string {
  if (isAxiosError(err)) {
    const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail) && detail.length > 0 && typeof detail[0]?.msg === "string") {
      return detail[0].msg;
    }
  }
  return fallback;
}

type ViewTab = "subscriptions" | "invoices";

export default function BillingPage() {
  const { user } = useAuth();
  const canManage = user?.role === "owner" || user?.role === "admin" || user?.role === "dispatcher";

  const [tab, setTab] = useState<ViewTab>("subscriptions");

  return (
    <div>
      <PageHeader
        title="Billing"
        description="Per-vehicle subscriptions, plan changes, and invoice history."
        actions={canManage && tab === "subscriptions" ? <CreateSubscriptionButton /> : undefined}
      />

      <div className="mb-4 inline-flex rounded-md border border-border bg-muted p-1">
        <TabButton
          active={tab === "subscriptions"}
          onClick={() => setTab("subscriptions")}
          icon={<Receipt className="h-4 w-4" />}
        >
          Subscriptions
        </TabButton>
        <TabButton
          active={tab === "invoices"}
          onClick={() => setTab("invoices")}
          icon={<FileText className="h-4 w-4" />}
        >
          Invoices
        </TabButton>
      </div>

      {tab === "subscriptions" ? <SubscriptionsView canManage={canManage} /> : <InvoicesView />}
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
  icon,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-medium transition-colors",
        active ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
      )}
    >
      {icon}
      {children}
    </button>
  );
}

/** Local component so the "New subscription" button + modal can live in the header actions slot. */
function CreateSubscriptionButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <Plus className="h-4 w-4" />
        New subscription
      </Button>
      <CreateSubscriptionModal open={open} onClose={() => setOpen(false)} />
    </>
  );
}

function SubscriptionsView({ canManage }: { canManage: boolean }) {
  const [page, setPage] = useState(0);
  const [planFilter, setPlanFilter] = useState<BillingPlan | "">("");
  const [statusFilter, setStatusFilter] = useState<SubscriptionStatus | "">("");
  const [planChangeTarget, setPlanChangeTarget] = useState<SubscriptionRead | null>(null);
  const [cancelTarget, setCancelTarget] = useState<SubscriptionRead | null>(null);

  const { data: vehicles } = useVehiclesForBilling();
  const vehicleById = useMemo(() => {
    const map = new Map<string, string>();
    (vehicles ?? []).forEach((v) => map.set(v.id, v.rego));
    return map;
  }, [vehicles]);

  const { data, isLoading, isError, error } = useSubscriptions({
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
    plan: planFilter || undefined,
    status: statusFilter || undefined,
  });

  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const columns: TableColumn<SubscriptionRead>[] = [
    {
      key: "vehicle",
      header: "Vehicle",
      render: (row) => (
        <span className="font-medium text-foreground">{vehicleById.get(row.vehicle_id) ?? row.vehicle_id}</span>
      ),
    },
    {
      key: "plan",
      header: "Plan",
      render: (row) => <Badge variant="default">{planLabel(row.plan)}</Badge>,
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={STATUS_BADGE_VARIANT[row.status]}>{statusLabel(row.status)}</Badge>,
    },
    {
      key: "price_aud",
      header: "Price",
      render: (row) => <span>{formatAud(row.price_aud)}/mo</span>,
    },
    {
      key: "created_at",
      header: "Created",
      render: (row) => formatDate(row.created_at),
    },
    ...(canManage
      ? [
          {
            key: "actions",
            header: "",
            className: "text-right",
            render: (row: SubscriptionRead) => (
              <div className="flex justify-end gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={row.status === "canceled"}
                  onClick={() => setPlanChangeTarget(row)}
                >
                  Change plan
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  disabled={row.status === "canceled"}
                  onClick={() => setCancelTarget(row)}
                >
                  Cancel
                </Button>
              </div>
            ),
          } satisfies TableColumn<SubscriptionRead>,
        ]
      : []),
  ];

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-3 space-y-0">
        <CardTitle>Subscriptions</CardTitle>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            className="w-40"
            options={PLAN_OPTIONS}
            placeholder="All plans"
            value={planFilter}
            onChange={(e) => {
              setPlanFilter(e.target.value as BillingPlan | "");
              setPage(0);
            }}
          />
          <Select
            className="w-40"
            options={STATUS_OPTIONS}
            placeholder="All statuses"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value as SubscriptionStatus | "");
              setPage(0);
            }}
          />
        </div>
      </CardHeader>
      <CardContent>
        {isError ? (
          <ErrorBanner message={apiErrorMessage(error, "Failed to load subscriptions.")} />
        ) : (
          <>
            <Table
              columns={columns}
              data={data?.items ?? []}
              rowKey={(row) => row.id}
              isLoading={isLoading}
              emptyState="No subscriptions match these filters."
            />
            {total > 0 && (
              <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
                <span>
                  {total} subscription{total === 1 ? "" : "s"} — page {page + 1} of {pageCount}
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
          </>
        )}
      </CardContent>

      <ChangePlanModal subscription={planChangeTarget} onClose={() => setPlanChangeTarget(null)} />
      <CancelSubscriptionModal
        subscription={cancelTarget}
        vehicleLabel={cancelTarget ? vehicleById.get(cancelTarget.vehicle_id) ?? cancelTarget.vehicle_id : ""}
        onClose={() => setCancelTarget(null)}
      />
    </Card>
  );
}

function InvoicesView() {
  const { data: vehicles } = useVehiclesForBilling();
  const { data: subsData } = useSubscriptions({ skip: 0, limit: 200 });
  const [subscriptionFilter, setSubscriptionFilter] = useState("");

  const vehicleById = useMemo(() => {
    const map = new Map<string, string>();
    (vehicles ?? []).forEach((v) => map.set(v.id, v.rego));
    return map;
  }, [vehicles]);

  const subscriptionById = useMemo(() => {
    const map = new Map<string, SubscriptionRead>();
    (subsData?.items ?? []).forEach((s) => map.set(s.id, s));
    return map;
  }, [subsData]);

  const subscriptionOptions = (subsData?.items ?? []).map((s) => ({
    value: s.id,
    label: `${vehicleById.get(s.vehicle_id) ?? s.vehicle_id} — ${planLabel(s.plan)}`,
  }));

  const { data, isLoading, isError, error } = useInvoices(subscriptionFilter || undefined);

  function subscriptionLabel(subscriptionId: string): string {
    const sub = subscriptionById.get(subscriptionId);
    if (!sub) return subscriptionId;
    return vehicleById.get(sub.vehicle_id) ?? sub.vehicle_id;
  }

  const columns: TableColumn<InvoiceRead>[] = [
    {
      key: "id",
      header: "Invoice",
      render: (row) => <span className="font-mono text-xs">{row.id}</span>,
    },
    {
      key: "subscription_id",
      header: "Vehicle",
      render: (row) => subscriptionLabel(row.subscription_id),
    },
    {
      key: "amount_aud",
      header: "Amount",
      sortable: true,
      sortAccessor: (row) => Number(row.amount_aud),
      render: (row) => formatAud(row.amount_aud),
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={INVOICE_STATUS_VARIANT[row.status]}>{invoiceStatusLabel(row.status)}</Badge>,
    },
    {
      key: "period",
      header: "Period",
      sortable: true,
      sortAccessor: (row) => row.period_start,
      render: (row) => (
        <span className="whitespace-nowrap">
          {formatDate(row.period_start)} – {formatDate(row.period_end)}
        </span>
      ),
    },
    {
      key: "mock",
      header: "",
      render: (row) => (row.mock ? <Badge variant="outline">Simulated</Badge> : null),
    },
  ];

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-3 space-y-0">
        <CardTitle>Invoices</CardTitle>
        <Select
          className="w-64"
          options={subscriptionOptions}
          placeholder="All vehicles"
          value={subscriptionFilter}
          onChange={(e) => setSubscriptionFilter(e.target.value)}
        />
      </CardHeader>
      <CardContent>
        {isError ? (
          <ErrorBanner message={apiErrorMessage(error, "Failed to load invoices.")} />
        ) : (
          <Table
            columns={columns}
            data={data?.items ?? []}
            rowKey={(row) => row.id}
            isLoading={isLoading}
            pageSize={10}
            emptyState="No invoices yet for these vehicles."
          />
        )}
      </CardContent>
    </Card>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">
      {message}
    </div>
  );
}

function planLabel(plan: BillingPlan): string {
  return PLAN_OPTIONS.find((p) => p.value === plan)?.label ?? plan;
}

function statusLabel(status: SubscriptionStatus): string {
  return STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

function invoiceStatusLabel(status: InvoiceRead["status"]): string {
  return status.charAt(0).toUpperCase() + status.slice(1);
}

/** ---- Create subscription modal ---- */

function CreateSubscriptionModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data: vehicles, isLoading: vehiclesLoading } = useVehiclesForBilling();
  const createMutation = useCreateSubscription();
  const [vehicleId, setVehicleId] = useState("");
  const [plan, setPlan] = useState<BillingPlan>("basic");

  function handleClose() {
    createMutation.reset();
    setVehicleId("");
    setPlan("basic");
    onClose();
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!vehicleId) return;
    createMutation.mutate({ vehicle_id: vehicleId, plan }, { onSuccess: () => handleClose() });
  }

  const vehicleOptions = (vehicles ?? []).map((v) => ({ value: v.id, label: v.rego }));

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="New subscription"
      description="Start a billing subscription for a vehicle."
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!vehicleId || createMutation.isPending}>
            {createMutation.isPending ? "Creating…" : "Create subscription"}
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-foreground" htmlFor="new-sub-vehicle">
            Vehicle
          </label>
          <Select
            id="new-sub-vehicle"
            options={vehicleOptions}
            placeholder={vehiclesLoading ? "Loading vehicles…" : "Select a vehicle"}
            value={vehicleId}
            onChange={(e) => setVehicleId(e.target.value)}
            disabled={vehiclesLoading}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-foreground" htmlFor="new-sub-plan">
            Plan
          </label>
          <Select
            id="new-sub-plan"
            options={PLAN_OPTIONS}
            value={plan}
            onChange={(e) => setPlan(e.target.value as BillingPlan)}
          />
        </div>
        {createMutation.isError && (
          <ErrorBanner message={apiErrorMessage(createMutation.error, "Failed to create subscription.")} />
        )}
      </form>
    </Modal>
  );
}

/** ---- Change plan modal ---- */

function ChangePlanModal({
  subscription,
  onClose,
}: {
  subscription: SubscriptionRead | null;
  onClose: () => void;
}) {
  const updateMutation = useUpdateSubscription();
  const [plan, setPlan] = useState<BillingPlan>(subscription?.plan ?? "basic");

  // Reset local plan selection whenever a new subscription is targeted.
  const subId = subscription?.id;
  useMemo(() => {
    if (subscription) setPlan(subscription.plan);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subId]);

  function handleClose() {
    updateMutation.reset();
    onClose();
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!subscription) return;
    updateMutation.mutate({ id: subscription.id, body: { plan } }, { onSuccess: () => handleClose() });
  }

  return (
    <Modal
      open={!!subscription}
      onClose={handleClose}
      title="Change plan"
      description={subscription ? "Update the plan for this subscription." : undefined}
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={updateMutation.isPending}>
            {updateMutation.isPending ? "Saving…" : "Save plan"}
          </Button>
        </>
      }
    >
      {subscription && (
        <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-foreground" htmlFor="change-plan">
              Plan
            </label>
            <Select
              id="change-plan"
              options={PLAN_OPTIONS}
              value={plan}
              onChange={(e) => setPlan(e.target.value as BillingPlan)}
            />
            <p className="text-xs text-muted-foreground">
              Current price {formatAud(subscription.price_aud)}/mo. Price is recalculated from the new plan.
            </p>
          </div>
          {updateMutation.isError && (
            <ErrorBanner message={apiErrorMessage(updateMutation.error, "Failed to update plan.")} />
          )}
        </form>
      )}
    </Modal>
  );
}

/** ---- Cancel subscription modal ---- */

function CancelSubscriptionModal({
  subscription,
  vehicleLabel,
  onClose,
}: {
  subscription: SubscriptionRead | null;
  vehicleLabel: string;
  onClose: () => void;
}) {
  const cancelMutation = useCancelSubscription();

  function handleClose() {
    cancelMutation.reset();
    onClose();
  }

  function handleConfirm() {
    if (!subscription) return;
    cancelMutation.mutate(subscription.id, { onSuccess: () => handleClose() });
  }

  return (
    <Modal
      open={!!subscription}
      onClose={handleClose}
      title="Cancel subscription"
      description={
        subscription
          ? `This will cancel the ${planLabel(subscription.plan)} subscription for ${vehicleLabel}. The record is kept as billing history.`
          : undefined
      }
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Keep subscription
          </Button>
          <Button variant="destructive" onClick={handleConfirm} disabled={cancelMutation.isPending}>
            {cancelMutation.isPending ? "Canceling…" : "Cancel subscription"}
          </Button>
        </>
      }
    >
      {cancelMutation.isError && (
        <ErrorBanner message={apiErrorMessage(cancelMutation.error, "Failed to cancel subscription.")} />
      )}
    </Modal>
  );
}
