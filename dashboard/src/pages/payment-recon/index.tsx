import { useState } from "react";
import { isAxiosError } from "axios";
import { CreditCard, Ticket } from "lucide-react";
import {
  Badge,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { usePaymentsList } from "./api";
import type { PaymentRead, PaymentStatus, ReconciliationMethod } from "./types";

const PAGE_SIZE = 20;

const STATUS_OPTIONS: { value: PaymentStatus; label: string }[] = [
  { value: "pending", label: "Pending" },
  { value: "requires_action", label: "Requires action" },
  { value: "succeeded", label: "Succeeded" },
  { value: "failed", label: "Failed" },
  { value: "refunded", label: "Refunded" },
  { value: "canceled", label: "Canceled" },
];

const STATUS_BADGE_VARIANT: Record<PaymentStatus, "success" | "accent" | "destructive" | "outline" | "default"> = {
  pending: "outline",
  requires_action: "accent",
  succeeded: "success",
  failed: "destructive",
  refunded: "accent",
  canceled: "outline",
};

const METHOD_TABS: { value: ReconciliationMethod; label: string; icon: typeof CreditCard }[] = [
  { value: "cabcharge", label: "CabCharge", icon: CreditCard },
  { value: "ttss", label: "TTSS", icon: Ticket },
];

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

/** Money fields come back as decimal strings; format explicitly for display only. */
function formatAud(amount: string | null | undefined): string {
  if (amount == null || amount === "") return "\u2014";
  const n = Number(amount);
  if (Number.isNaN(n)) return amount;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "\u2014";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("en-AU", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function statusLabel(status: PaymentStatus): string {
  return STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

export default function PaymentReconciliationPage() {
  const [method, setMethod] = useState<ReconciliationMethod>("cabcharge");

  return (
    <div>
      <PageHeader
        title="CabCharge / TTSS Reconciliation"
        description="Read-only audit view of CabCharge and TTSS docket payments for reconciliation against settlement/claim reports."
      />

      <div className="mb-4 inline-flex rounded-md border border-border bg-muted p-1">
        {METHOD_TABS.map(({ value, label, icon: Icon }) => (
          <button
            key={value}
            type="button"
            onClick={() => setMethod(value)}
            className={cn(
              "inline-flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-medium transition-colors",
              method === value ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      <DocketTable method={method} />
    </div>
  );
}

function DocketTable({ method }: { method: ReconciliationMethod }) {
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<PaymentStatus | "">("");
  const [tripIdFilter, setTripIdFilter] = useState("");

  const { data, isLoading, isError, error } = usePaymentsList(method, {
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
    status: statusFilter || undefined,
    trip_id: tripIdFilter || undefined,
  });

  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const baseColumns: TableColumn<PaymentRead>[] = [
    {
      key: "docket_number",
      header: "Docket",
      render: (row) => (
        <span className="font-mono text-xs font-medium text-foreground">{row.docket_number ?? "\u2014"}</span>
      ),
    },
    {
      key: "trip_id",
      header: "Trip",
      render: (row) => <span className="font-mono text-xs">{row.trip_id.slice(0, 8)}</span>,
    },
    {
      key: "amount",
      header: "Amount",
      render: (row) => formatAud(row.amount),
    },
  ];

  const ttssColumns: TableColumn<PaymentRead>[] =
    method === "ttss"
      ? [
          {
            key: "subsidy_amount",
            header: "Subsidy",
            render: (row) => formatAud(row.subsidy_amount),
          },
          {
            key: "passenger_paid_amount",
            header: "Passenger paid",
            render: (row) => formatAud(row.passenger_paid_amount),
          },
        ]
      : [];

  const tailColumns: TableColumn<PaymentRead>[] = [
    {
      key: "status",
      header: "Status",
      render: (row) => <Badge variant={STATUS_BADGE_VARIANT[row.status]}>{statusLabel(row.status)}</Badge>,
    },
    {
      key: "captured_at",
      header: "Captured",
      render: (row) => formatDateTime(row.captured_at),
    },
    {
      key: "notes",
      header: "Notes",
      className: "max-w-xs truncate text-muted-foreground",
      render: (row) => row.notes ?? "\u2014",
    },
  ];

  const columns = [...baseColumns, ...ttssColumns, ...tailColumns];

  const methodLabel = method === "cabcharge" ? "CabCharge" : "TTSS";

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-center justify-between gap-3 space-y-0">
        <CardTitle>{methodLabel} dockets</CardTitle>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            className="w-48"
            placeholder="Filter by trip ID"
            value={tripIdFilter}
            onChange={(e) => {
              setTripIdFilter(e.target.value);
              setPage(0);
            }}
          />
          <Select
            className="w-44"
            options={STATUS_OPTIONS}
            placeholder="All statuses"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value as PaymentStatus | "");
              setPage(0);
            }}
          />
        </div>
      </CardHeader>
      <CardContent>
        {isError ? (
          <ErrorBanner message={apiErrorMessage(error, "Failed to load " + methodLabel + " dockets.")} />
        ) : (
          <>
            <Table
              key={method}
              columns={columns}
              data={data?.items ?? []}
              rowKey={(row) => row.id}
              isLoading={isLoading}
              emptyState={"No " + methodLabel + " dockets match these filters."}
            />
            {total > 0 && (
              <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
                <span>
                  {total} docket{total === 1 ? "" : "s"} {"—"} page {page + 1} of {pageCount}
                </span>
                <div className="flex gap-2">
                  <PageButton disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                    Previous
                  </PageButton>
                  <PageButton
                    disabled={page >= pageCount - 1}
                    onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
                  >
                    Next
                  </PageButton>
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function PageButton({
  children,
  disabled,
  onClick,
}: {
  children: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "inline-flex h-8 items-center rounded-md border border-input bg-background px-3 text-sm font-medium transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50",
      )}
    >
      {children}
    </button>
  );
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">
      {message}
    </div>
  );
}
