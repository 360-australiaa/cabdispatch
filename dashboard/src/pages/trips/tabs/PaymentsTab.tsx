import { Badge, EmptyState, ErrorBanner, Skeleton, Table, type TableColumn } from "@/components/ui";
import { errorMessage, formatDateTime, formatMoney } from "@/lib/format";
import { useTripPaymentsQuery, type TripPayment } from "@/hooks/useTrips";

export interface PaymentsTabProps {
  tripId: string;
}

const METHOD_LABELS: Record<TripPayment["method"], string> = {
  tap_to_pay: "Tap to pay",
  link: "Payment link",
  cash: "Cash",
  cabcharge: "Cabcharge",
  ttss: "TTSS",
};

function statusVariant(status: TripPayment["status"]): "success" | "destructive" | "outline" | "accent" {
  switch (status) {
    case "succeeded":
      return "success";
    case "failed":
    case "canceled":
      return "destructive";
    case "refunded":
      return "outline";
    default:
      return "accent";
  }
}

/**
 * Trip page Payments tab (dashboard command-centre plan §7): `GET
 * /v1/payments?trip_id=` -- confirmed real and trip-filterable server-side
 * (`backend/app/api/v1/payments.py::list_payments` takes `trip_id`
 * independently of `method`, unlike `pages/payment-recon`'s hook which
 * always pins `method` to cabcharge/ttss for that page's own narrower
 * view). Refund/adjust, payment link and manual/cash capture (plan §9,
 * Payments module) are a separate workstream -- this tab is the read-only
 * list the plan asks for here.
 */
export function PaymentsTab({ tripId }: PaymentsTabProps) {
  const paymentsQuery = useTripPaymentsQuery(tripId);

  if (paymentsQuery.isLoading) {
    return <Skeleton className="h-32 w-full" />;
  }

  if (paymentsQuery.isError) {
    return (
      <ErrorBanner
        message={`Failed to load payments for this trip (GET /v1/payments?trip_id=): ${errorMessage(
          paymentsQuery.error,
        )}`}
      />
    );
  }

  const payments = paymentsQuery.data?.items ?? [];

  const columns: TableColumn<TripPayment>[] = [
    { key: "created_at", header: "When", render: (p) => formatDateTime(p.created_at) },
    { key: "method", header: "Method", render: (p) => METHOD_LABELS[p.method] ?? p.method },
    { key: "amount", header: "Amount", className: "text-right", render: (p) => formatMoney(p.amount) },
    { key: "surcharge", header: "Surcharge", className: "text-right", render: (p) => formatMoney(p.surcharge) },
    {
      key: "status",
      header: "Status",
      render: (p) => <Badge variant={statusVariant(p.status)}>{p.status}</Badge>,
    },
    { key: "docket_number", header: "Docket", render: (p) => p.docket_number ?? "—" },
  ];

  return (
    <Table
      columns={columns}
      data={payments}
      rowKey={(p) => p.id}
      label="Payments"
      emptyState={
        <EmptyState title="No payments" description="No payment records reference this trip yet." />
      }
    />
  );
}
