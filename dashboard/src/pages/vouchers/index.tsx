import { useState } from "react";
import { Building2, Pencil, Plus, Ticket, Trash2 } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Modal,
  PageHeader,
  Select,
  Table,
  type TableColumn,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import {
  useCorporateAccountsQuery,
  useDeleteCorporateAccountMutation,
  useDeleteVoucherMutation,
  useVouchersQuery,
  type CorporateAccount,
  type Voucher,
} from "./hooks";
import { VoucherFormModal } from "./VoucherFormModal";
import { CorporateAccountFormModal } from "./CorporateAccountFormModal";
import { extractErrorMessage, formatDateTime, formatMoney, voucherStatus } from "./format";

const PAGE_SIZE = 15;

type VouchersTab = "vouchers" | "corporate-accounts";

const TABS: { key: VouchersTab; label: string; icon: typeof Ticket }[] = [
  { key: "vouchers", label: "Vouchers", icon: Ticket },
  { key: "corporate-accounts", label: "Corporate Accounts", icon: Building2 },
];

const REDEEMED_FILTER_OPTIONS = [
  { value: "", label: "All vouchers" },
  { value: "false", label: "Available" },
  { value: "true", label: "Redeemed" },
];

const ACTIVE_FILTER_OPTIONS = [
  { value: "", label: "All accounts" },
  { value: "true", label: "Active only" },
  { value: "false", label: "Inactive only" },
];

/** Vouchers & Corporate Accounts — CRUD console over the two real ledgers
 * backing the trips domain's "voucher"/"account" Trip.payment_method values
 * (see app.services.payments.redeem_voucher / validate_account_reference).
 * Two tabs in one page rather than two routes, mirroring the Tariff Studio
 * page's own two-tab ("Rate cards" / "Toll Zones") pattern. */
export default function VouchersPage() {
  const { user } = useAuth();
  const canWrite = user?.role === "owner" || user?.role === "admin";

  const [tab, setTab] = useState<VouchersTab>("vouchers");

  // --- vouchers tab state ---
  const [voucherRedeemedFilter, setVoucherRedeemedFilter] = useState<"" | "true" | "false">("");
  const [voucherPage, setVoucherPage] = useState(0);
  const [createVoucherOpen, setCreateVoucherOpen] = useState(false);
  const [editingVoucher, setEditingVoucher] = useState<Voucher | null>(null);
  const [deletingVoucher, setDeletingVoucher] = useState<Voucher | null>(null);
  const [voucherDeleteError, setVoucherDeleteError] = useState<string | null>(null);

  const vouchersQuery = useVouchersQuery({
    redeemed: voucherRedeemedFilter === "" ? "" : voucherRedeemedFilter === "true",
    skip: voucherPage * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const deleteVoucherMutation = useDeleteVoucherMutation();
  const vouchers = vouchersQuery.data?.items ?? [];
  const voucherTotal = vouchersQuery.data?.total ?? 0;
  const voucherPageCount = Math.max(1, Math.ceil(voucherTotal / PAGE_SIZE));

  // --- corporate accounts tab state ---
  const [accountActiveFilter, setAccountActiveFilter] = useState<"" | "true" | "false">("");
  const [accountPage, setAccountPage] = useState(0);
  const [createAccountOpen, setCreateAccountOpen] = useState(false);
  const [editingAccount, setEditingAccount] = useState<CorporateAccount | null>(null);
  const [deletingAccount, setDeletingAccount] = useState<CorporateAccount | null>(null);
  const [accountDeleteError, setAccountDeleteError] = useState<string | null>(null);

  const accountsQuery = useCorporateAccountsQuery({
    active: accountActiveFilter === "" ? "" : accountActiveFilter === "true",
    skip: accountPage * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const deleteAccountMutation = useDeleteCorporateAccountMutation();
  const accounts = accountsQuery.data?.items ?? [];
  const accountTotal = accountsQuery.data?.total ?? 0;
  const accountPageCount = Math.max(1, Math.ceil(accountTotal / PAGE_SIZE));

  const voucherColumns: TableColumn<Voucher>[] = [
    { key: "code", header: "Code", render: (row) => <span className="font-medium">{row.code}</span> },
    {
      key: "status",
      header: "Status",
      render: (row) => {
        const { label, variant } = voucherStatus(row);
        return <Badge variant={variant}>{label}</Badge>;
      },
    },
    { key: "value_aud", header: "Value", render: (row) => formatMoney(row.value_aud) },
    { key: "expires_at", header: "Expires", render: (row) => formatDateTime(row.expires_at) },
    { key: "redeemed_at", header: "Redeemed at", render: (row) => formatDateTime(row.redeemed_at) },
    {
      key: "redeemed_by_trip_id",
      header: "Trip",
      render: (row) =>
        row.redeemed_by_trip_id ? (
          <span className="font-mono text-xs text-muted-foreground">{row.redeemed_by_trip_id.slice(0, 8)}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) =>
        canWrite ? (
          <div className="flex justify-end gap-1">
            <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditingVoucher(row)}>
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Delete"
              onClick={() => {
                setVoucherDeleteError(null);
                setDeletingVoucher(row);
              }}
            >
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </div>
        ) : null,
    },
  ];

  const accountColumns: TableColumn<CorporateAccount>[] = [
    {
      key: "reference",
      header: "Reference",
      render: (row) => <span className="font-medium">{row.reference}</span>,
    },
    { key: "company_name", header: "Company" },
    {
      key: "active",
      header: "Status",
      render: (row) => (
        <Badge variant={row.active ? "success" : "destructive"}>{row.active ? "Active" : "Inactive"}</Badge>
      ),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) =>
        canWrite ? (
          <div className="flex justify-end gap-1">
            <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditingAccount(row)}>
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Delete"
              onClick={() => {
                setAccountDeleteError(null);
                setDeletingAccount(row);
              }}
            >
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <div>
      <PageHeader
        title="Vouchers & Corporate Accounts"
        description="The real ledgers behind the 'voucher' and 'account' trip payment methods — promo/prepaid vouchers redeemed once against a trip, and pre-registered pay-later corporate accounts."
        actions={
          canWrite ? (
            tab === "vouchers" ? (
              <Button onClick={() => setCreateVoucherOpen(true)}>
                <Plus className="h-4 w-4" /> New voucher
              </Button>
            ) : (
              <Button onClick={() => setCreateAccountOpen(true)}>
                <Plus className="h-4 w-4" /> New corporate account
              </Button>
            )
          ) : undefined
        }
      />

      <div className="mb-6 flex gap-1 border-b border-border">
        {TABS.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              "flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors",
              tab === key
                ? "border-brand-primary text-brand-primary"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === "vouchers" && (
        <>
          <Card className="mb-4">
            <CardContent className="flex flex-wrap items-end gap-3 pt-4">
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">Status</label>
                <Select
                  className="w-44"
                  options={REDEEMED_FILTER_OPTIONS}
                  value={voucherRedeemedFilter}
                  onChange={(e) => {
                    setVoucherPage(0);
                    setVoucherRedeemedFilter(e.target.value as "" | "true" | "false");
                  }}
                />
              </div>
              {voucherRedeemedFilter && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setVoucherPage(0);
                    setVoucherRedeemedFilter("");
                  }}
                >
                  Clear filters
                </Button>
              )}
            </CardContent>
          </Card>

          {vouchersQuery.isError && (
            <p className="mb-3 text-sm text-destructive">
              Failed to load vouchers. Check the backend connection and try again.
            </p>
          )}

          <Table
            columns={voucherColumns}
            data={vouchers}
            rowKey={(row) => row.id}
            isLoading={vouchersQuery.isLoading}
            emptyState="No vouchers match these filters."
          />

          {voucherPageCount > 1 && (
            <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
              <span>
                Page {voucherPage + 1} of {voucherPageCount} ({voucherTotal} vouchers)
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={voucherPage === 0}
                  onClick={() => setVoucherPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={voucherPage >= voucherPageCount - 1}
                  onClick={() => setVoucherPage((p) => Math.min(voucherPageCount - 1, p + 1))}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      {tab === "corporate-accounts" && (
        <>
          <Card className="mb-4">
            <CardContent className="flex flex-wrap items-end gap-3 pt-4">
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-medium text-muted-foreground">Status</label>
                <Select
                  className="w-44"
                  options={ACTIVE_FILTER_OPTIONS}
                  value={accountActiveFilter}
                  onChange={(e) => {
                    setAccountPage(0);
                    setAccountActiveFilter(e.target.value as "" | "true" | "false");
                  }}
                />
              </div>
              {accountActiveFilter && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setAccountPage(0);
                    setAccountActiveFilter("");
                  }}
                >
                  Clear filters
                </Button>
              )}
            </CardContent>
          </Card>

          {accountsQuery.isError && (
            <p className="mb-3 text-sm text-destructive">
              Failed to load corporate accounts. Check the backend connection and try again.
            </p>
          )}

          <Table
            columns={accountColumns}
            data={accounts}
            rowKey={(row) => row.id}
            isLoading={accountsQuery.isLoading}
            emptyState="No corporate accounts match these filters."
          />

          {accountPageCount > 1 && (
            <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
              <span>
                Page {accountPage + 1} of {accountPageCount} ({accountTotal} accounts)
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={accountPage === 0}
                  onClick={() => setAccountPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={accountPage >= accountPageCount - 1}
                  onClick={() => setAccountPage((p) => Math.min(accountPageCount - 1, p + 1))}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      <VoucherFormModal open={createVoucherOpen} onClose={() => setCreateVoucherOpen(false)} mode="create" />
      <VoucherFormModal
        open={editingVoucher != null}
        onClose={() => setEditingVoucher(null)}
        mode="edit"
        voucher={editingVoucher ?? undefined}
      />

      <CorporateAccountFormModal
        open={createAccountOpen}
        onClose={() => setCreateAccountOpen(false)}
        mode="create"
      />
      <CorporateAccountFormModal
        open={editingAccount != null}
        onClose={() => setEditingAccount(null)}
        mode="edit"
        account={editingAccount ?? undefined}
      />

      <Modal
        open={deletingVoucher != null}
        onClose={() => {
          setDeletingVoucher(null);
          setVoucherDeleteError(null);
        }}
        title="Delete voucher?"
        description="This permanently removes the voucher. It can no longer be redeemed against a trip."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingVoucher(null);
                setVoucherDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteVoucherMutation.isPending}
              onClick={async () => {
                if (!deletingVoucher) return;
                setVoucherDeleteError(null);
                try {
                  await deleteVoucherMutation.mutateAsync(deletingVoucher.id);
                  setDeletingVoucher(null);
                } catch (err) {
                  setVoucherDeleteError(extractErrorMessage(err));
                }
              }}
            >
              {deleteVoucherMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingVoucher && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingVoucher.code}</span> will be permanently
            removed.
          </p>
        )}
        {voucherDeleteError && <p className="mt-2 text-sm text-destructive">{voucherDeleteError}</p>}
      </Modal>

      <Modal
        open={deletingAccount != null}
        onClose={() => {
          setDeletingAccount(null);
          setAccountDeleteError(null);
        }}
        title="Delete corporate account?"
        description="This permanently removes the corporate account. It can no longer be used as an 'account' trip payment method."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingAccount(null);
                setAccountDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteAccountMutation.isPending}
              onClick={async () => {
                if (!deletingAccount) return;
                setAccountDeleteError(null);
                try {
                  await deleteAccountMutation.mutateAsync(deletingAccount.id);
                  setDeletingAccount(null);
                } catch (err) {
                  setAccountDeleteError(extractErrorMessage(err));
                }
              }}
            >
              {deleteAccountMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingAccount && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingAccount.reference}</span> (
            {deletingAccount.company_name}) will be permanently removed.
          </p>
        )}
        {accountDeleteError && <p className="mt-2 text-sm text-destructive">{accountDeleteError}</p>}
      </Modal>
    </div>
  );
}
