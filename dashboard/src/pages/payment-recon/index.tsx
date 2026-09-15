import { useState, type ReactNode } from "react";
import { CreditCard, Download, FileCheck2, ShieldCheck, Ticket, X } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Checkbox,
  ErrorBanner,
  Input,
  Modal,
  PageHeader,
  Pagination,
  Select,
  Table,
  Tabs,
  useToast,
  type TabItem,
  type TableColumn,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import {
  listPaymentsForExport,
  useAuthorizeCabchargeMutation,
  usePaymentQuery,
  usePaymentsList,
  useTtssClaimMutation,
  useUpdatePaymentMutation,
  EXPORT_FETCH_LIMIT,
} from "./api";
import {
  STATUS_BADGE_VARIANT,
  buildDocketCsv,
  downloadTextFile,
  errorMessage,
  formatAud,
  formatDateTime,
  joinSettlementRef,
  splitSettlementRef,
  statusLabel,
  statusOptionsFor,
} from "./format";
import type { PaymentRead, PaymentStatus, ReconciliationMethod } from "./types";

const PAGE_SIZE = 20;

/** The payments router has no role gate of its own (any authenticated
 * tenant user), but reconciling money is an office job: the write controls
 * here follow the same owner/admin/dispatcher line every other desk on this
 * dashboard draws, so a driver account gets the read-only view. */
const RECONCILE_ROLES = new Set(["owner", "admin", "dispatcher"]);

const METHOD_TABS: TabItem<ReconciliationMethod>[] = [
  { value: "cabcharge", label: "CabCharge", icon: CreditCard },
  { value: "ttss", label: "TTSS", icon: Ticket },
];

function methodLabel(method: ReconciliationMethod): string {
  return method === "cabcharge" ? "CabCharge" : "TTSS";
}

/**
 * Payment Reconciliation -- the CabCharge / TTSS docket workflow (admin
 * plan §3: "the page is read-only and shows 0 rows; build a real
 * reconciliation workflow"). Per docket: open it, move its status
 * (`PATCH /v1/payments/{id}`), attach the settlement/claim reference (kept
 * in `notes`, see `splitSettlementRef`), and -- while it is still pending --
 * run the real-or-mock rail flow (`POST /v1/payments/cabcharge/authorize`,
 * `POST /v1/payments/ttss/claim`). Per list: export the filtered dockets
 * as the claim CSV.
 *
 * Note the backend's own warning (`app/api/v1/payments.py` module
 * docstring): no CabCharge/TTSS credentials are configured anywhere, so the
 * authorise/claim flows always take the mock branch today. The result
 * toast says so every time rather than letting an operator believe a claim
 * went to the scheme.
 */
export default function PaymentReconciliationPage() {
  const [method, setMethod] = useState<ReconciliationMethod>("cabcharge");

  return (
    <div>
      <PageHeader
        title="CabCharge / TTSS Reconciliation"
        description="Match CabCharge and TTSS dockets against settlement and claim reports: mark each docket settled or paid, attach the settlement reference, and export the claim file."
      />

      <Tabs
        items={METHOD_TABS}
        value={method}
        onChange={setMethod}
        variant="pill"
        label="Reconciliation methods"
        className="mb-4"
      />

      <DocketTable key={method} method={method} />
    </div>
  );
}

function DocketTable({ method }: { method: ReconciliationMethod }) {
  const toast = useToast();
  const { user } = useAuth();
  const canReconcile = !!user && RECONCILE_ROLES.has(user.role);

  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<PaymentStatus | "">("");
  const [tripIdFilter, setTripIdFilter] = useState("");
  const [selected, setSelected] = useState<PaymentRead | null>(null);
  const [railModal, setRailModal] = useState<{ open: boolean; from: PaymentRead | null }>({ open: false, from: null });
  const [exporting, setExporting] = useState(false);

  const { data, isLoading, isError, error } = usePaymentsList(method, {
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
    status: statusFilter || undefined,
    trip_id: tripIdFilter || undefined,
  });

  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const label = methodLabel(method);

  async function exportCsv() {
    setExporting(true);
    try {
      const { items, total: matched } = await listPaymentsForExport(method, {
        status: statusFilter || undefined,
        trip_id: tripIdFilter || undefined,
      });
      const csv = buildDocketCsv(items, method);
      const stamp = new Date().toISOString().slice(0, 10);
      const ok = downloadTextFile(`${method}-dockets-${stamp}.csv`, csv);
      if (!ok) {
        toast.error("Export failed", { description: "This browser could not start the download." });
        return;
      }
      if (matched > items.length) {
        toast.info(`Exported the first ${items.length} of ${matched} dockets`, {
          description: `The export is capped at ${EXPORT_FETCH_LIMIT} rows — narrow the filters (status, trip) to export the rest.`,
        });
      } else {
        toast.success(`Exported ${items.length} ${label} docket${items.length === 1 ? "" : "s"}`);
      }
    } catch (err) {
      toast.error("Export failed", { description: errorMessage(err) });
    } finally {
      setExporting(false);
    }
  }

  const baseColumns: TableColumn<PaymentRead>[] = [
    {
      key: "docket_number",
      header: "Docket",
      render: (row) => (
        <span className="font-mono text-xs font-medium text-foreground">{row.docket_number ?? "—"}</span>
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
      render: (row) => <Badge variant={STATUS_BADGE_VARIANT[row.status]}>{statusLabel(method, row.status)}</Badge>,
    },
    {
      key: "captured_at",
      header: "Captured",
      render: (row) => formatDateTime(row.captured_at),
    },
    {
      key: "settlement_ref",
      header: "Settlement ref",
      render: (row) => {
        const { reference } = splitSettlementRef(row.notes);
        return reference ? (
          <span className="font-mono text-xs">{reference}</span>
        ) : (
          <span className="text-muted-foreground">{"—"}</span>
        );
      },
    },
  ];

  const columns = [...baseColumns, ...ttssColumns, ...tailColumns];

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div className="lg:col-span-2">
        <Card>
          <CardHeader className="flex-row flex-wrap items-center justify-between gap-3 space-y-0">
            <CardTitle>{label} dockets</CardTitle>
            <div className="flex flex-wrap items-center gap-2">
              <Input
                className="w-48"
                placeholder="Filter by trip ID"
                aria-label="Filter by trip ID"
                value={tripIdFilter}
                onChange={(e) => {
                  setTripIdFilter(e.target.value);
                  setPage(0);
                }}
              />
              <Select
                className="w-44"
                aria-label="Filter by status"
                options={statusOptionsFor(method)}
                placeholder="All statuses"
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value as PaymentStatus | "");
                  setPage(0);
                }}
              />
              <Button variant="outline" size="sm" onClick={exportCsv} disabled={exporting || total === 0}>
                <Download className="h-4 w-4" /> {exporting ? "Exporting…" : "Export CSV"}
              </Button>
              {canReconcile && (
                <Button size="sm" onClick={() => setRailModal({ open: true, from: null })}>
                  {method === "cabcharge" ? (
                    <>
                      <ShieldCheck className="h-4 w-4" /> Authorise CabCharge
                    </>
                  ) : (
                    <>
                      <FileCheck2 className="h-4 w-4" /> Submit TTSS claim
                    </>
                  )}
                </Button>
              )}
            </div>
          </CardHeader>
          <CardContent>
            {isError ? (
              <ErrorBanner message={errorMessage(error) || "Failed to load " + label + " dockets."} />
            ) : (
              <>
                <Table
                  columns={columns}
                  data={data?.items ?? []}
                  rowKey={(row) => row.id}
                  isLoading={isLoading}
                  onRowClick={(row) => setSelected(row)}
                  emptyState={"No " + label + " dockets match these filters."}
                  label={`${label} dockets`}
                />
                {total > 0 && (
                  <Pagination
                    page={page}
                    pageCount={pageCount}
                    onPageChange={setPage}
                    summary={
                      <>
                        {total} docket{total === 1 ? "" : "s"} {"—"} page {page + 1} of {pageCount}
                      </>
                    }
                  />
                )}
              </>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="lg:col-span-1">
        {selected ? (
          <DocketDetailPanel
            key={selected.id}
            method={method}
            paymentId={selected.id}
            initial={selected}
            canReconcile={canReconcile}
            onClose={() => setSelected(null)}
            onRunRail={(docket) => setRailModal({ open: true, from: docket })}
          />
        ) : (
          <Card>
            <CardContent className="flex h-40 items-center justify-center text-center text-sm text-muted-foreground">
              Select a docket to update its status or attach a settlement reference.
            </CardContent>
          </Card>
        )}
      </div>

      {/* Keyed on open/from so each opening mounts a fresh form (no reset effect). */}
      {method === "cabcharge" ? (
        <AuthoriseCabchargeModal
          key={`${railModal.open}-${railModal.from?.id ?? "new"}`}
          open={railModal.open}
          from={railModal.from}
          onClose={() => setRailModal({ open: false, from: null })}
          onCreated={(payment) => setSelected(payment)}
        />
      ) : (
        <TtssClaimModal
          key={`${railModal.open}-${railModal.from?.id ?? "new"}`}
          open={railModal.open}
          from={railModal.from}
          onClose={() => setRailModal({ open: false, from: null })}
          onCreated={(payment) => setSelected(payment)}
        />
      )}
    </div>
  );
}

// --- detail panel ---------------------------------------------------------------

function DocketDetailPanel({
  method,
  paymentId,
  initial,
  canReconcile,
  onClose,
  onRunRail,
}: {
  method: ReconciliationMethod;
  paymentId: string;
  initial: PaymentRead;
  canReconcile: boolean;
  onClose: () => void;
  onRunRail: (docket: PaymentRead) => void;
}) {
  const toast = useToast();
  const paymentQuery = usePaymentQuery(paymentId, initial);
  const updateMutation = useUpdatePaymentMutation();
  const payment = paymentQuery.data ?? initial;

  // Seeded once per docket -- the caller keys this panel on `paymentId`, so
  // opening a different docket mounts a fresh form rather than re-seeding
  // an effect. After a save the server copy equals what the form holds.
  const [status, setStatus] = useState<PaymentStatus>(payment.status);
  const [reference, setReference] = useState(() => splitSettlementRef(payment.notes).reference);
  const [notes, setNotes] = useState(() => splitSettlementRef(payment.notes).rest);
  const [saveError, setSaveError] = useState<string | null>(null);

  const dirty =
    status !== payment.status || joinSettlementRef(notes, reference) !== (payment.notes ?? null);

  async function save() {
    setSaveError(null);
    const body: Parameters<typeof updateMutation.mutateAsync>[0]["body"] = {};
    if (status !== payment.status) {
      body.status = status;
      // Marking settled/paid stamps the capture time if nothing has yet.
      if (status === "succeeded" && !payment.captured_at) body.captured_at = new Date().toISOString();
    }
    const nextNotes = joinSettlementRef(notes, reference);
    if (nextNotes !== (payment.notes ?? null)) body.notes = nextNotes;
    try {
      await updateMutation.mutateAsync({ id: payment.id, body });
      toast.success("Docket updated", {
        description: body.status ? `Status set to ${statusLabel(method, body.status)}.` : undefined,
      });
    } catch (err) {
      setSaveError(errorMessage(err));
    }
  }

  const railPossible = payment.status === "pending" && payment.method === method;

  return (
    <Card className="sticky top-4">
      <CardHeader className="flex-row items-start justify-between gap-2 space-y-0">
        <div>
          <CardTitle className="flex items-center gap-2 text-base">
            {method === "cabcharge" ? <CreditCard className="h-4 w-4 text-brand-accent" /> : <Ticket className="h-4 w-4 text-brand-accent" />}
            Docket {payment.docket_number ?? payment.id.slice(0, 8)}
          </CardTitle>
          <p className="mt-0.5 font-mono text-xs text-muted-foreground">{payment.id}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close docket panel"
          className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <X className="h-4 w-4" />
        </button>
      </CardHeader>

      <CardContent className="flex flex-col gap-5">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <Field label="Status">
            <Badge variant={STATUS_BADGE_VARIANT[payment.status]}>{statusLabel(method, payment.status)}</Badge>
          </Field>
          <Field label="Trip">
            <span className="break-all font-mono text-xs">{payment.trip_id}</span>
          </Field>
          <Field label="Amount">{formatAud(payment.amount)}</Field>
          <Field label="Surcharge">{formatAud(payment.surcharge)}</Field>
          {method === "ttss" && (
            <>
              <Field label="Subsidy">{formatAud(payment.subsidy_amount)}</Field>
              <Field label="Passenger paid">{formatAud(payment.passenger_paid_amount)}</Field>
            </>
          )}
          <Field label="Captured">{formatDateTime(payment.captured_at)}</Field>
          <Field label="Created">{formatDateTime(payment.created_at)}</Field>
        </dl>

        {paymentQuery.isError && (
          <p className="text-xs text-destructive">Could not refresh this docket — showing the list copy.</p>
        )}

        <div className="flex flex-col gap-3 border-t border-border pt-4">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="docket-status" className="text-xs font-medium text-muted-foreground">
              Status
            </label>
            <Select
              id="docket-status"
              options={statusOptionsFor(method)}
              value={status}
              onChange={(e) => setStatus(e.target.value as PaymentStatus)}
              disabled={!canReconcile || updateMutation.isPending}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="docket-reference" className="text-xs font-medium text-muted-foreground">
              {method === "ttss" ? "Claim / remittance reference" : "Settlement reference"}
            </label>
            <Input
              id="docket-reference"
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder={method === "ttss" ? "e.g. TTSS remittance 2026-09" : "e.g. settlement batch 4417"}
              maxLength={200}
              disabled={!canReconcile || updateMutation.isPending}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="docket-notes" className="text-xs font-medium text-muted-foreground">
              Notes
            </label>
            <textarea
              id="docket-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              className="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground disabled:opacity-60"
              disabled={!canReconcile || updateMutation.isPending}
            />
          </div>

          {!canReconcile && (
            <p className="text-xs text-muted-foreground">
              Reconciling dockets is restricted to owner/admin/dispatcher roles.
            </p>
          )}

          {canReconcile && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span title={!railPossible ? "Only a pending docket on this rail can be sent" : undefined}>
                <Button variant="outline" size="sm" disabled={!railPossible} onClick={() => onRunRail(payment)}>
                  {method === "cabcharge" ? "Authorise via CabCharge" : "Claim via TTSS"}
                </Button>
              </span>
              <Button size="sm" disabled={!dirty || updateMutation.isPending} onClick={save}>
                {updateMutation.isPending ? "Saving…" : "Save"}
              </Button>
            </div>
          )}
          {saveError && <p className="text-xs text-destructive">{saveError}</p>}
        </div>
      </CardContent>
    </Card>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{children}</dd>
    </div>
  );
}

// --- rail flows -------------------------------------------------------------------
//
// Both flows create a NEW payment row on the backend (that is how
// `authorize`/`claim` are modelled: authorisation -> docket creation). When
// launched from an existing pending manual docket the form is prefilled
// from it and, by default, the manual docket is cancelled afterwards with a
// note pointing at the new one -- otherwise the trip would carry two
// pending dockets for the same fare.

function useSupersede() {
  const updateMutation = useUpdatePaymentMutation();
  return async (from: PaymentRead, newDocket: PaymentRead) => {
    const { reference, rest } = splitSettlementRef(from.notes);
    const note = [rest, `Superseded by docket ${newDocket.docket_number ?? newDocket.id}`].filter(Boolean).join("\n");
    await updateMutation.mutateAsync({
      id: from.id,
      body: { status: "canceled", notes: joinSettlementRef(note, reference) },
    });
  };
}

function AuthoriseCabchargeModal({
  open,
  from,
  onClose,
  onCreated,
}: {
  open: boolean;
  from: PaymentRead | null;
  onClose: () => void;
  onCreated: (payment: PaymentRead) => void;
}) {
  const toast = useToast();
  const authorise = useAuthorizeCabchargeMutation();
  const supersede = useSupersede();
  // Prefilled from the launching docket; the parent keys this modal on
  // open/from so every opening starts from these initial values.
  const [tripId, setTripId] = useState(from?.trip_id ?? "");
  const [card, setCard] = useState("");
  const [amount, setAmount] = useState(from?.amount ?? "");
  const [surcharge, setSurcharge] = useState(from?.surcharge ?? "0.00");
  const [cancelOriginal, setCancelOriginal] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setError(null);
    try {
      const result = await authorise.mutateAsync({
        trip_id: tripId.trim(),
        card_identifier: card.trim(),
        amount: amount.trim(),
        surcharge: surcharge.trim() || "0.00",
      });
      if (from && cancelOriginal) await supersede(from, result.payment);
      toast[result.mock ? "info" : "success"](
        result.mock ? "CabCharge authorisation simulated" : "CabCharge authorised",
        {
          description: `Docket ${result.payment.docket_number ?? result.payment.id}${
            result.authorization_id ? ` — authorisation ${result.authorization_id}` : ""
          }${result.mock ? ". No CabCharge credentials are configured, so nothing was sent to CabCharge." : ""}`,
        },
      );
      onCreated(result.payment);
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const valid = tripId.trim() && card.trim() && Number(amount) > 0;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Authorise a CabCharge payment"
      description="Runs the authorisation → docket step. A new pending docket is created with the CabCharge docket number; settle it from this page once the batch clears."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!valid || authorise.isPending} onClick={submit}>
            {authorise.isPending ? "Authorising…" : "Authorise"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <LabelledInput id="cc-trip" label="Trip ID" value={tripId} onChange={setTripId} disabled={!!from} />
        <LabelledInput id="cc-card" label="CabCharge card / NFC identifier" value={card} onChange={setCard} />
        <div className="grid grid-cols-2 gap-3">
          <LabelledInput id="cc-amount" label="Amount (AUD)" value={amount} onChange={setAmount} inputMode="decimal" />
          <LabelledInput id="cc-surcharge" label="Surcharge (AUD)" value={surcharge} onChange={setSurcharge} inputMode="decimal" />
        </div>
        {from && (
          <Checkbox
            label={`Cancel the manual docket ${from.docket_number ?? from.id.slice(0, 8)} once authorised`}
            checked={cancelOriginal}
            onChange={(e) => setCancelOriginal(e.target.checked)}
          />
        )}
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    </Modal>
  );
}

function TtssClaimModal({
  open,
  from,
  onClose,
  onCreated,
}: {
  open: boolean;
  from: PaymentRead | null;
  onClose: () => void;
  onCreated: (payment: PaymentRead) => void;
}) {
  const toast = useToast();
  const claim = useTtssClaimMutation();
  const supersede = useSupersede();
  const [tripId, setTripId] = useState(from?.trip_id ?? "");
  const [concession, setConcession] = useState("");
  const [amount, setAmount] = useState(from?.amount ?? "");
  const [cancelOriginal, setCancelOriginal] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setError(null);
    try {
      const result = await claim.mutateAsync({
        trip_id: tripId.trim(),
        concession_identifier: concession.trim(),
        amount: amount.trim(),
      });
      if (from && cancelOriginal) await supersede(from, result.payment);
      toast[result.mock ? "info" : "success"](result.mock ? "TTSS claim simulated" : "TTSS claim submitted", {
        description: `Subsidy ${formatAud(result.subsidy_amount)}, passenger paid ${formatAud(result.passenger_paid_amount)}${
          result.claim_id ? ` — claim ${result.claim_id}` : ""
        }${result.mock ? ". No TTSS credentials are configured, so nothing was sent to the scheme." : ""}`,
      });
      onCreated(result.payment);
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const valid = tripId.trim() && concession.trim() && Number(amount) > 0;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Submit a TTSS claim"
      description="Runs the subsidy calculation → claim step. The subsidy is 50% of the fare, capped at $60.00, computed server-side; a new pending claim docket is created."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!valid || claim.isPending} onClick={submit}>
            {claim.isPending ? "Submitting…" : "Submit claim"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <LabelledInput id="ttss-trip" label="Trip ID" value={tripId} onChange={setTripId} disabled={!!from} />
        <LabelledInput id="ttss-concession" label="Passenger TTSS card / concession identifier" value={concession} onChange={setConcession} />
        <LabelledInput id="ttss-amount" label="Full fare before subsidy (AUD)" value={amount} onChange={setAmount} inputMode="decimal" />
        {from && (
          <Checkbox
            label={`Cancel the manual docket ${from.docket_number ?? from.id.slice(0, 8)} once claimed`}
            checked={cancelOriginal}
            onChange={(e) => setCancelOriginal(e.target.checked)}
          />
        )}
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    </Modal>
  );
}

function LabelledInput({
  id,
  label,
  value,
  onChange,
  disabled,
  inputMode,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
  inputMode?: "decimal" | "text";
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <Input id={id} value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled} inputMode={inputMode} />
    </div>
  );
}
