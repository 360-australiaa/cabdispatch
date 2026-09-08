import { useMemo, useState } from "react";
import { Plus } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  PageHeader,
  Pagination,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import {
  useDriverOptionsQuery,
  useDriverWalletQuery,
  useFleetWalletBalancesQuery,
  useWalletTransactionsQuery,
  type DriverWalletBalance,
  type WalletTransaction,
} from "./hooks";
import { WalletTransactionFormModal } from "./WalletTransactionFormModal";
import { formatDateTime, formatMoney, WALLET_KIND_LABELS } from "./format";

const LEDGER_PAGE_SIZE = 20;

/** Driver Wallets — operator view of every driver's balance and one driver's
 * ledger. Two `/v1/wallet/*` endpoints back this:
 * `GET /v1/wallet/drivers/{driver_id}` (one driver's derived balance) and
 * `GET /v1/wallet/transactions` (the real server-paged ledger, with a
 * `total` from an actual `count(*)`, filterable by `driver_id`). The whole
 * page is owner/admin server-side, so it renders a notice for other roles
 * rather than a failing table. The driver sees the same numbers on their
 * tablet via `GET /v1/me/wallet`. */
export default function WalletPage() {
  const { user } = useAuth();
  const canAccess = user?.role === "owner" || user?.role === "admin";

  const [driverId, setDriverId] = useState<string>("");
  const [postOpen, setPostOpen] = useState(false);
  const [ledgerPage, setLedgerPage] = useState(0);

  const driversQuery = useDriverOptionsQuery();
  const drivers = useMemo(() => driversQuery.data ?? [], [driversQuery.data]);
  const selectedDriver = drivers.find((d) => d.id === driverId) ?? null;

  const walletQuery = useDriverWalletQuery(canAccess ? driverId || null : null, 1);
  const wallet = walletQuery.data;
  const balance = Number(wallet?.balance_aud ?? "0");

  const fleetBalances = useFleetWalletBalancesQuery(canAccess && !selectedDriver ? drivers : []);

  const ledgerQuery = useWalletTransactionsQuery({
    driver_id: canAccess ? driverId || undefined : undefined,
    skip: ledgerPage * LEDGER_PAGE_SIZE,
    limit: LEDGER_PAGE_SIZE,
  });
  const ledgerRows = ledgerQuery.data?.items ?? [];
  const ledgerTotal = ledgerQuery.data?.total ?? 0;
  const ledgerPageCount = Math.max(1, Math.ceil(ledgerTotal / LEDGER_PAGE_SIZE));

  const driverOptions = [
    { value: "", label: driversQuery.isLoading ? "Loading drivers…" : "All drivers (fleet-wide)" },
    ...drivers.map((d) => ({
      value: d.id,
      label: d.driver_code ? `${d.name} (${d.driver_code})` : d.name,
    })),
  ];

  const balanceColumns: TableColumn<DriverWalletBalance>[] = [
    {
      key: "driver",
      header: "Driver",
      render: (row) => (
        <div>
          <p className="font-medium">{row.driver.name}</p>
          <p className="text-xs text-muted-foreground">
            {row.driver.driver_code ?? row.driver.email}
          </p>
        </div>
      ),
    },
    {
      key: "balance",
      header: "Balance",
      className: "text-right",
      render: (row) => {
        if (row.isLoading) return <span className="text-muted-foreground">…</span>;
        if (row.isError) return <span className="text-destructive">Failed to load</span>;
        const negative = row.balance_aud?.trim().startsWith("-") ?? false;
        return (
          <span className={cn("font-mono", negative ? "text-destructive" : "text-foreground")}>
            {formatMoney(row.balance_aud)}
          </span>
        );
      },
    },
    {
      key: "view",
      header: "",
      className: "text-right",
      render: (row) => (
        <Button variant="ghost" size="sm" onClick={() => setDriverId(row.driver.id)}>
          View ledger
        </Button>
      ),
    },
  ];

  const ledgerColumns: TableColumn<WalletTransaction>[] = [
    { key: "created_at", header: "When", render: (row) => formatDateTime(row.created_at) },
    ...(driverId
      ? []
      : [
          {
            key: "driver_id",
            header: "Driver",
            render: (row: WalletTransaction) => {
              const d = drivers.find((x) => x.id === row.driver_id);
              return <span className="font-mono text-xs">{d?.name ?? row.driver_id}</span>;
            },
          } satisfies TableColumn<WalletTransaction>,
        ]),
    {
      key: "kind",
      header: "Kind",
      render: (row) => <Badge variant="outline">{WALLET_KIND_LABELS[row.kind] ?? row.kind}</Badge>,
    },
    {
      key: "amount_aud",
      header: "Amount",
      className: "text-right",
      render: (row) => {
        const negative = row.amount_aud.trim().startsWith("-");
        return (
          <span className={cn("font-mono", negative ? "text-destructive" : "text-success")}>
            {negative ? "" : "+"}
            {formatMoney(row.amount_aud)}
          </span>
        );
      },
    },
    {
      key: "reference",
      header: "Reference",
      render: (row) =>
        row.reference ? (
          <span className="font-mono text-xs text-muted-foreground">{row.reference}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      key: "note",
      header: "Note",
      render: (row) => row.note ?? <span className="text-muted-foreground">—</span>,
    },
  ];

  if (!canAccess) {
    return (
      <div>
        <PageHeader title="Driver Wallets" description="Balances and ledgers for every driver in your fleet." />
        <Card>
          <CardContent className="pt-4 text-sm text-muted-foreground">
            Driver wallets are visible to owner and admin roles only.
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Driver Wallets"
        description="Each driver's balance is the sum of their ledger — post a top-up, adjustment or payout here and the driver tablet's Wallet tile updates on its next read."
        actions={
          selectedDriver ? (
            <Button onClick={() => setPostOpen(true)}>
              <Plus className="h-4 w-4" /> Post transaction
            </Button>
          ) : undefined
        }
      />

      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Driver</label>
            <Select
              className="w-72"
              options={driverOptions}
              value={driverId}
              onChange={(e) => {
                setLedgerPage(0);
                setDriverId(e.target.value);
              }}
              disabled={driversQuery.isLoading}
            />
          </div>
          {driversQuery.isError && (
            <p className="text-sm text-destructive">Failed to load the driver list.</p>
          )}
          {!driversQuery.isLoading && drivers.length === 0 && (
            <p className="text-sm text-muted-foreground">
              No drivers yet — create one under Fleet &amp; Drivers first.
            </p>
          )}
        </CardContent>
      </Card>

      {!selectedDriver && drivers.length > 0 && (
        <>
          <p className="mb-2 text-xs text-muted-foreground">
            One request per driver — there is no single "every balance" endpoint yet. Showing the
            first {drivers.length} driver{drivers.length === 1 ? "" : "s"} in the tenant.
          </p>
          <Table
            columns={balanceColumns}
            data={fleetBalances}
            rowKey={(row) => row.driver.id}
            isLoading={false}
            emptyState="No drivers yet."
            className="mb-6"
          />
        </>
      )}

      {selectedDriver && (
        <div className="mb-4 grid gap-4 sm:grid-cols-3">
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Balance</p>
              <p
                className={cn(
                  "mt-1 text-2xl font-semibold tabular-nums",
                  balance < 0 ? "text-destructive" : "text-foreground",
                )}
              >
                {walletQuery.isLoading ? "…" : formatMoney(wallet?.balance_aud)}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">Derived from the ledger on every read</p>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Driver</p>
              <p className="mt-1 truncate text-lg font-medium">{selectedDriver.name}</p>
              <p className="truncate text-xs text-muted-foreground">{selectedDriver.email}</p>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="pt-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Ledger lines</p>
              <p className="mt-1 text-lg font-medium tabular-nums">{ledgerTotal}</p>
              <p className="text-xs text-muted-foreground">Total for this driver, all time</p>
            </CardContent>
          </Card>
        </div>
      )}

      {walletQuery.isError && selectedDriver && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load this driver's wallet. Check the backend connection and try again.
        </p>
      )}
      {ledgerQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load the wallet ledger. Check the backend connection and try again.
        </p>
      )}

      {(selectedDriver || (drivers.length === 0 && !driversQuery.isLoading)) && (
        <>
          <Table
            columns={ledgerColumns}
            data={ledgerRows}
            rowKey={(row) => row.id}
            isLoading={ledgerQuery.isLoading}
            emptyState="No wallet transactions for this driver yet."
          />

          {ledgerPageCount > 1 && (
            <Pagination
              page={ledgerPage}
              pageCount={ledgerPageCount}
              onPageChange={setLedgerPage}
              summary={
                <>
                  Page {ledgerPage + 1} of {ledgerPageCount} ({ledgerTotal} transactions)
                </>
              }
            />
          )}
        </>
      )}

      {selectedDriver && (
        <WalletTransactionFormModal open={postOpen} onClose={() => setPostOpen(false)} driver={selectedDriver} />
      )}
    </div>
  );
}
