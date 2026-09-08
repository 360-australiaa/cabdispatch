import { useMemo, useState } from "react";
import { Plus } from "lucide-react";
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, EmptyState, Pagination, Table, type TableColumn } from "@/components/ui";
import { cn } from "@/lib/utils";
import { errorMessage, formatDateTime } from "@/lib/format";
import {
  useDriverWalletQuery,
  useIncentivesQuery,
  useWalletTransactionsQuery,
  type WalletTransaction,
} from "@/pages/driver-engagement/hooks";
import { WalletTransactionFormModal } from "@/pages/driver-engagement/WalletTransactionFormModal";
import { formatMoney, WALLET_KIND_LABELS } from "@/pages/driver-engagement/format";
import { IncentiveProgressRow } from "./IncentiveProgressRow";
import type { DriverUser } from "../api";

const PAGE_SIZE = 15;

/**
 * Driver page Earnings & wallet tab (dashboard command-centre plan §4.4):
 * this driver's wallet balance and ledger (`GET /v1/wallet/drivers/{id}`,
 * `GET /v1/wallet/transactions?driver_id=`, both real and driver-scoped),
 * a manual adjustment form (`POST /v1/wallet/transactions`, real --
 * reuses `WalletTransactionFormModal` from the Driver Wallets page rather
 * than forking a second copy of the same form), and progress toward each
 * currently active incentive (derived client-side the same way the Driver
 * Wallets/Incentives pages already do -- there is no per-driver-progress
 * endpoint for an arbitrary driver id, only `GET /v1/me/incentives` for the
 * caller's own).
 */
export function EarningsTab({ driver, canAdjustWallet }: { driver: DriverUser; canAdjustWallet: boolean }) {
  const [ledgerSkip, setLedgerSkip] = useState(0);
  const [adjustOpen, setAdjustOpen] = useState(false);

  const walletQuery = useDriverWalletQuery(driver.id, 1);
  const ledgerQuery = useWalletTransactionsQuery({ driver_id: driver.id, skip: ledgerSkip, limit: PAGE_SIZE });
  const activeIncentivesQuery = useIncentivesQuery({ active: true, limit: 10 });

  const balance = walletQuery.data?.balance_aud ?? null;
  const negative = balance?.trim().startsWith("-") ?? false;

  const ledgerRows = ledgerQuery.data?.items ?? [];
  const ledgerTotal = ledgerQuery.data?.total ?? 0;
  const page = Math.floor(ledgerSkip / PAGE_SIZE);
  const pageCount = Math.max(1, Math.ceil(ledgerTotal / PAGE_SIZE));

  const walletDriverOption = useMemo(
    () => ({ id: driver.id, name: driver.name, email: driver.email, driver_code: driver.driver_code, status: driver.status }),
    [driver.id, driver.name, driver.email, driver.driver_code, driver.status],
  );

  const columns: TableColumn<WalletTransaction>[] = [
    { key: "created_at", header: "When", render: (row) => formatDateTime(row.created_at) },
    { key: "kind", header: "Kind", render: (row) => <Badge variant="outline">{WALLET_KIND_LABELS[row.kind] ?? row.kind}</Badge> },
    {
      key: "amount_aud",
      header: "Amount",
      className: "text-right",
      render: (row) => (
        <span className={cn("font-mono", row.amount_aud.trim().startsWith("-") ? "text-destructive" : "text-foreground")}>
          {formatMoney(row.amount_aud)}
        </span>
      ),
    },
    { key: "reference", header: "Reference", render: (row) => row.reference ?? "—" },
    { key: "note", header: "Note", render: (row) => row.note ?? "—" },
  ];

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle>Wallet balance</CardTitle>
          {canAdjustWallet && (
            <Button size="sm" onClick={() => setAdjustOpen(true)}>
              <Plus className="h-3.5 w-3.5" /> Post adjustment
            </Button>
          )}
        </CardHeader>
        <CardContent>
          {walletQuery.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : walletQuery.isError ? (
            <p className="text-sm text-destructive">
              Failed to load wallet balance (GET /v1/wallet/drivers/{driver.id}): {errorMessage(walletQuery.error)}
            </p>
          ) : (
            <span className={cn("text-2xl font-semibold", negative ? "text-destructive" : "text-foreground")}>
              {formatMoney(balance)}
            </span>
          )}
        </CardContent>
      </Card>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">Ledger</h4>
        {ledgerQuery.isError ? (
          <EmptyState
            title="Couldn't load the ledger"
            description={`GET /v1/wallet/transactions?driver_id= failed: ${errorMessage(ledgerQuery.error)}`}
          />
        ) : (
          <div className="flex flex-col gap-3">
            <Table
              columns={columns}
              data={ledgerRows}
              rowKey={(r) => r.id}
              isLoading={ledgerQuery.isLoading}
              label="Wallet ledger"
              emptyState={<EmptyState title="No wallet activity" description="No transactions recorded for this driver yet." />}
            />
            {ledgerTotal > PAGE_SIZE && (
              <Pagination
                page={page}
                pageCount={pageCount}
                onPageChange={(p) => setLedgerSkip(p * PAGE_SIZE)}
                summary={
                  <>
                    {ledgerSkip + 1}–{Math.min(ledgerTotal, ledgerSkip + PAGE_SIZE)} of {ledgerTotal}
                  </>
                }
              />
            )}
          </div>
        )}
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">Incentive progress</h4>
        {activeIncentivesQuery.isError ? (
          <p className="text-sm text-muted-foreground">Incentives unavailable right now (GET /v1/incentives).</p>
        ) : activeIncentivesQuery.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (activeIncentivesQuery.data?.items ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">No active incentives right now.</p>
        ) : (
          <div className="flex flex-col gap-2">
            {(activeIncentivesQuery.data?.items ?? []).map((incentive) => (
              <IncentiveProgressRow key={incentive.id} driverId={driver.id} incentive={incentive} />
            ))}
          </div>
        )}
      </div>

      {canAdjustWallet && (
        <WalletTransactionFormModal open={adjustOpen} onClose={() => setAdjustOpen(false)} driver={walletDriverOption} />
      )}
    </div>
  );
}
