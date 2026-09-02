import { useEffect, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { CheckCircle2, Download, FileSpreadsheet, Pencil, Plus, ShieldCheck, Trash2, XCircle } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
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
  downloadComplianceDocument,
  useComplianceDocumentsQuery,
  useDeleteDocumentMutation,
  useVehicleDossierQuery,
  useVehiclesQuery,
  type ComplianceDocument,
  type DocType,
} from "@/hooks/useComplianceVault";
import { DocumentFormModal } from "./DocumentFormModal";
import { NswPtpExportCard } from "./NswPtpExportCard";
import { RevenueSection } from "./RevenueSection";
import { DOC_TYPE_LABELS, DOC_TYPE_OPTIONS, extractErrorMessage, formatDateTime } from "./format";

const PAGE_SIZE = 10;

const DOC_TYPE_FILTER_OPTIONS = [{ value: "", label: "All document types" }, ...DOC_TYPE_OPTIONS];

type ViewTab = "vault" | "reports";

/** Mirrors `compliance.py`'s write-endpoint restriction (upload/update/
 * delete documents) — owner/admin/dispatcher only. Without this, every role
 * including `driver` saw fully-enabled write controls that just 403'd. */
const MANAGE_ROLES = new Set(["owner", "admin", "dispatcher"]);

export default function CompliancePage() {
  const { user } = useAuth();
  const canManage = !!user && MANAGE_ROLES.has(user.role);

  // Lets deep links (e.g. the Trips page's "NSW PtP export" button) land on
  // the right tab instead of always opening to the Vault.
  const [searchParams] = useSearchParams();
  const [tab, setTab] = useState<ViewTab>(() =>
    searchParams.get("tab") === "reports" ? "reports" : "vault",
  );

  return (
    <div>
      <PageHeader
        title="Compliance Vault"
        description="Per-vehicle regulatory documents, NSW PtP export, and revenue/GST reporting."
      />

      <div className="mb-4 inline-flex rounded-md border border-border bg-muted p-1">
        <TabButton active={tab === "vault"} onClick={() => setTab("vault")} icon={<ShieldCheck className="h-4 w-4" />}>
          Document Vault
        </TabButton>
        <TabButton
          active={tab === "reports"}
          onClick={() => setTab("reports")}
          icon={<FileSpreadsheet className="h-4 w-4" />}
        >
          Reports
        </TabButton>
      </div>

      {tab === "vault" ? <VaultView /> : <ReportsView />}
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

function ReportsView() {
  return (
    <div className="flex flex-col gap-4">
      <NswPtpExportCard />
      <RevenueSection />
    </div>
  );
}

function VaultView() {
  const vehiclesQuery = useVehiclesQuery();
  const vehicles = vehiclesQuery.data?.items ?? [];

  const [vehicleId, setVehicleId] = useState<string>("");
  const [docTypeFilter, setDocTypeFilter] = useState<DocType | "">("");
  const [page, setPage] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [editingDoc, setEditingDoc] = useState<ComplianceDocument | null>(null);
  const [deletingDoc, setDeletingDoc] = useState<ComplianceDocument | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Default to the first vehicle once the picker loads.
  useEffect(() => {
    if (!vehicleId && vehicles.length > 0) {
      setVehicleId(vehicles[0].id);
    }
  }, [vehicleId, vehicles]);

  const selectedVehicle = vehicles.find((v) => v.id === vehicleId) ?? null;
  const vehicleLabel = selectedVehicle ? `${selectedVehicle.rego} (${selectedVehicle.vehicle_class})` : "—";

  const documentsQuery = useComplianceDocumentsQuery({
    vehicle_id: vehicleId || null,
    doc_type: docTypeFilter,
    skip: page * PAGE_SIZE,
    limit: PAGE_SIZE,
  });
  const dossierQuery = useVehicleDossierQuery(vehicleId || null);
  const deleteMutation = useDeleteDocumentMutation();

  const documents = documentsQuery.data?.items ?? [];
  const total = documentsQuery.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function resetPageAnd<T>(setter: (v: T) => void) {
    return (v: T) => {
      setPage(0);
      setter(v);
    };
  }

  async function handleDownload(doc: ComplianceDocument) {
    setDownloadError(null);
    setDownloadingId(doc.id);
    try {
      await downloadComplianceDocument(doc);
    } catch (err) {
      setDownloadError(extractErrorMessage(err));
    } finally {
      setDownloadingId(null);
    }
  }

  const columns: TableColumn<ComplianceDocument>[] = [
    {
      key: "doc_type",
      header: "Type",
      render: (row) => <Badge variant="primary">{DOC_TYPE_LABELS[row.doc_type] ?? row.doc_type}</Badge>,
    },
    {
      key: "original_filename",
      header: "File",
      render: (row) => <span className="font-medium">{row.original_filename}</span>,
    },
    { key: "notes", header: "Notes", render: (row) => row.notes || <span className="text-muted-foreground">—</span> },
    {
      key: "uploaded_at",
      header: "Uploaded",
      sortable: true,
      sortAccessor: (row) => row.uploaded_at,
      render: (row) => formatDateTime(row.uploaded_at),
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            title="Download"
            disabled={downloadingId === row.id}
            onClick={() => handleDownload(row)}
          >
            <Download className="h-4 w-4" />
          </Button>
          {canManage && (
            <>
              <Button variant="ghost" size="icon" title="Edit" onClick={() => setEditingDoc(row)}>
                <Pencil className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                title="Delete"
                onClick={() => {
                  setDeleteError(null);
                  setDeletingDoc(row);
                }}
              >
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <Card className="mb-4">
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Vehicle</label>
            <Select
              className="w-56"
              options={vehicles.map((v) => ({ value: v.id, label: `${v.rego} (${v.vehicle_class})` }))}
              placeholder={vehiclesQuery.isLoading ? "Loading vehicles…" : "Select a vehicle"}
              value={vehicleId}
              onChange={(e) => resetPageAnd(setVehicleId)(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label className="text-xs font-medium text-muted-foreground">Document type</label>
            <Select
              className="w-52"
              options={DOC_TYPE_FILTER_OPTIONS}
              value={docTypeFilter}
              onChange={(e) => resetPageAnd(setDocTypeFilter)(e.target.value as DocType | "")}
            />
          </div>
          {canManage && (
            <Button className="ml-auto" onClick={() => setCreateOpen(true)} disabled={!vehicleId}>
              <Plus className="h-4 w-4" /> Upload document
            </Button>
          )}
        </CardContent>
      </Card>

      {vehiclesQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load the vehicle list. Check the backend connection and try again.
        </p>
      )}

      {!vehiclesQuery.isLoading && vehicles.length === 0 && (
        <Card className="mb-4">
          <CardContent className="pt-4 text-sm text-muted-foreground">
            No vehicles on file yet — add a vehicle in Fleet & Drivers before uploading compliance
            documents.
          </CardContent>
        </Card>
      )}

      {vehicleId && (
        <Card className="mb-4">
          <CardHeader className="flex-row items-center justify-between">
            <div>
              <CardTitle>Cl.14 checklist — {vehicleLabel}</CardTitle>
              <CardDescription>
                Present/missing status per required document type for this vehicle.
              </CardDescription>
            </div>
            {dossierQuery.data && (
              <Badge variant={dossierQuery.data.overall_compliant ? "success" : "destructive"}>
                {dossierQuery.data.overall_compliant ? "Compliant" : "Non-compliant"}
              </Badge>
            )}
          </CardHeader>
          <CardContent className="pt-0">
            {dossierQuery.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading checklist…</p>
            ) : dossierQuery.isError ? (
              <p className="text-sm text-destructive">Failed to load the checklist for this vehicle.</p>
            ) : dossierQuery.data ? (
              <ul className="divide-y divide-border">
                {dossierQuery.data.items.map((item) => (
                  <li key={item.key} className="flex items-center justify-between gap-4 py-2">
                    <div className="flex items-center gap-2">
                      {item.satisfied ? (
                        <CheckCircle2 className="h-4 w-4 shrink-0 text-success" />
                      ) : (
                        <XCircle className="h-4 w-4 shrink-0 text-destructive" />
                      )}
                      <span className="text-sm text-foreground">{item.label}</span>
                    </div>
                    <span className="text-xs text-muted-foreground">
                      {item.document_count} doc{item.document_count === 1 ? "" : "s"} on file
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}
          </CardContent>
        </Card>
      )}

      {documentsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load documents. Check the backend connection and try again.
        </p>
      )}
      {downloadError && <p className="mb-3 text-sm text-destructive">{downloadError}</p>}

      <Table
        columns={columns}
        data={documents}
        rowKey={(row) => row.id}
        isLoading={documentsQuery.isLoading}
        emptyState={vehicleId ? "No documents uploaded for this vehicle yet." : "Select a vehicle to view its documents."}
      />

      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
          <span>
            Page {page + 1} of {pageCount} ({total} documents)
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

      {vehicleId && (
        <DocumentFormModal
          open={createOpen}
          onClose={() => setCreateOpen(false)}
          mode="create"
          vehicleId={vehicleId}
          vehicleLabel={vehicleLabel}
        />
      )}

      {editingDoc && (
        <DocumentFormModal
          open={editingDoc != null}
          onClose={() => setEditingDoc(null)}
          mode="edit"
          vehicleId={editingDoc.vehicle_id}
          vehicleLabel={vehicleLabel}
          document={editingDoc}
        />
      )}

      <Modal
        open={deletingDoc != null}
        onClose={() => {
          setDeletingDoc(null);
          setDeleteError(null);
        }}
        title="Delete document?"
        description="This permanently removes the document from the vault. It may cause the cl.14 checklist to show a missing item."
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDeletingDoc(null);
                setDeleteError(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={async () => {
                if (!deletingDoc) return;
                setDeleteError(null);
                try {
                  await deleteMutation.mutateAsync({ id: deletingDoc.id, vehicleId: deletingDoc.vehicle_id });
                  setDeletingDoc(null);
                } catch (err) {
                  setDeleteError(extractErrorMessage(err));
                }
              }}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deletingDoc && (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">{deletingDoc.original_filename}</span> (
            {DOC_TYPE_LABELS[deletingDoc.doc_type] ?? deletingDoc.doc_type}) will be permanently removed.
          </p>
        )}
        {deleteError && <p className="mt-2 text-sm text-destructive">{deleteError}</p>}
      </Modal>
    </div>
  );
}
