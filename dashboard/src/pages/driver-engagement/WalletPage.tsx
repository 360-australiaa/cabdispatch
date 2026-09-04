import { useMemo, useState } from "react";
import { Plus } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import { useDriverOptionsQuery, useDriverWalletQuery, type WalletTransaction } from "./hooks";
import { WalletTransactionFormModal } from "./WalletTransactionFormModal";
import { formatDateTime, formatMoney, WALLET_KIND_LABELS } from "./format";

/** Driver Wallets — operator view of one driver's ledger and derived balance
 * (`GET /v1/wallet/drivers/{driver_id}`) with a "post top-up / adjustment /
 * payout" action (`POST /v1/wallet/transactions`). The whole page is
 * owner/admin server-side, so it renders a notice for other roles rather
 * than a failing table. The driver sees the same numbers on their tablet via
 * `GET /v1/me/wallet`. */
export default function WalletPage() {
  const { user } = useAuth();
  const canAccess = user?.role === "owner" || user?.role === "admin";

  const [driverId, setDriverId] = useState<string>("");
  const [postOpen, setPostOpen] = useState(false);

  const driversQuery = useDriverOptionsQuery();
  const drivers = useMemo(() => driversQuery.data ?? [], [driversQuery.data]);
  const selectedDriver = drivers.find((d) => d.id === driverId) ?? null;

  const walletQuery = useDriverWalletQuery(canAccess ? driverId || null : null);
  const wallet = walletQuery.data;
  const balance = Number(wallet?.balance_aud ?? "0");

  const driverOptions = [
    { value: "", label: driversQuery.isLoading ? "Loading drivers…" : "Select a driver" },
    ...drivers.map((d) => ({
      value: d.id,
      label: d.driver_code ? `${d.name} (${d.driver_code})` : d.name,
    })),
  ];

  const columns: TableColumn<WalletTransaction>[] = [
    { key: "created_at", header: "When", render: (row) => formatDateTime(row.created_at) },
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
              onChange={(e) => setDriverId(e.target.value)}
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

      {selectedDriver && (
        <>
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
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Recent lines</p>
                <p className="mt-1 text-lg font-medium tabular-nums">{wallet?.recent.length ?? 0}</p>
                <p className="text-xs text-muted-foreground">Most recent 50 shown below</p>
              </CardContent>
            </Card>
          </div>

          {walletQuery.isError && (
            <p className="mb-3 text-sm text-destructive">
              Failed to load this driver's wallet. Check the backend connection and try again.
            </p>
          )}

          <Table
            columns={columns}
            data={wallet?.recent ?? []}
            rowKey={(row) => row.id}
            isLoading={walletQuery.isLoading}
            emptyState="No wallet transactions for this driver yet."
          />

          <WalletTransactionFormModal open={postOpen} onClose={() => setPostOpen(false)} driver={selectedDriver} />
        </>
      )}
    </div>
  );
}
