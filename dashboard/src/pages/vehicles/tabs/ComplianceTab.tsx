import { useState } from "react";
import { Download, FileWarning } from "lucide-react";
import { Badge, Button, EmptyState, ErrorBanner, Skeleton, Table, Tooltip, type TableColumn } from "@/components/ui";
import { errorMessage, formatDate, formatDateTimeShort } from "@/lib/format";
import { useComplianceExpiry } from "@/pages/fleet/api";
import { COMPLIANCE_EXPIRY_FIELD_LABELS, type Vehicle } from "@/pages/fleet/types";
import {
  downloadComplianceDocument,
  downloadVehicleDossierPdf,
  useComplianceDocumentsQuery,
  useVehicleDossierQuery,
  type ComplianceDocument,
} from "@/hooks/useComplianceVault";

export interface ComplianceTabProps {
  vehicle: Vehicle;
}

/** A four-digit year no real registration/insurance card carries (the
 * "0028" data bug the plan flags): more than ~20 years from today in either
 * direction. Mirrors the backend's own WRITE-side plausibility check
 * (`app.schemas.fleet._plausible_expiry`, 1900-2200) but is deliberately
 * tighter and READ-side -- a row saved before that guard existed (or by an
 * older client) can still carry a bad year, and this page must not render
 * it as if it were a normal date. */
function isImplausibleExpiry(value: string | null): boolean {
  if (!value) return false;
  const year = new Date(value).getFullYear();
  if (Number.isNaN(year)) return true;
  const thisYear = new Date().getFullYear();
  return Math.abs(year - thisYear) > 20;
}

function ExpiryField({ label, value }: { label: string; value: string | null }) {
  const implausible = isImplausibleExpiry(value);
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="flex items-center gap-2 font-medium text-foreground">
        {value ? formatDate(value) : <span className="text-muted-foreground">Unknown</span>}
        {implausible && (
          <Tooltip content={`"${value}" is more than 20 years from today -- almost certainly a data-entry error, not a real expiry. Fix it from Edit.`}>
            <Badge variant="destructive" className="inline-flex items-center gap-1">
              <FileWarning className="h-3 w-3" /> Implausible date
            </Badge>
          </Tooltip>
        )}
      </div>
    </div>
  );
}

const DOCUMENT_COLUMNS: TableColumn<ComplianceDocument>[] = [
  { key: "doc_type", header: "Document type", render: (d) => d.doc_type.replace(/_/g, " ") },
  { key: "original_filename", header: "File", render: (d) => d.original_filename },
  { key: "uploaded_at", header: "Uploaded", sortable: true, render: (d) => formatDateTimeShort(d.uploaded_at) },
  { key: "notes", header: "Notes", render: (d) => d.notes || "—" },
  {
    key: "actions",
    header: "",
    className: "text-right",
    render: (d) => (
      <Button variant="ghost" size="icon" aria-label={`Download ${d.original_filename}`} onClick={() => downloadComplianceDocument(d)}>
        <Download className="h-4 w-4" />
      </Button>
    ),
  },
];

/**
 * Plan §5.4 -- registration/insurance expiry (with the implausible-year
 * warning), this vehicle's own hits from the fleet-wide compliance-expiry
 * feed, the cl.14 dossier checklist, and its documents. Reuses the existing
 * Compliance Vault data layer (`hooks/useComplianceVault.ts`) wholesale
 * rather than re-fetching the same dossier/documents a second way.
 */
export function ComplianceTab({ vehicle }: ComplianceTabProps) {
  const [dossierDownloading, setDossierDownloading] = useState(false);
  const [dossierError, setDossierError] = useState<string | null>(null);

  // A generous window so this vehicle's own registration/insurance rows show
  // up here even when they're not imminent -- this feed's only job on this
  // page is "does anything about THIS vehicle already show up on the
  // fleet-wide warning list", not a fresh expiry calculation of its own.
  const expiryQuery = useComplianceExpiry(3650);
  const ownExpiryItems = (expiryQuery.data?.items ?? []).filter(
    (item) => item.entity_type === "vehicle" && item.entity_id === vehicle.id,
  );

  const dossierQuery = useVehicleDossierQuery(vehicle.id);
  const documentsQuery = useComplianceDocumentsQuery({ vehicle_id: vehicle.id, limit: 50 });

  async function handleDownloadDossier() {
    setDossierError(null);
    setDossierDownloading(true);
    try {
      await downloadVehicleDossierPdf(vehicle.id);
    } catch (err) {
      setDossierError(errorMessage(err));
    } finally {
      setDossierDownloading(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Expiry dates</p>
        <div className="grid grid-cols-1 gap-x-4 gap-y-3 text-sm sm:grid-cols-2">
          <ExpiryField label="Registration expiry" value={vehicle.registration_expiry} />
          <ExpiryField label="Insurance expiry" value={vehicle.insurance_expiry} />
        </div>
        <p className="mt-2 text-xs text-muted-foreground">
          Required under NSW Point to Point Transport regulation. Cab Dispatch reminds you when these
          are expiring but does not verify or enforce them.
        </p>
      </div>

      {ownExpiryItems.length > 0 && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-3">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-destructive">
            On the compliance-expiry feed
          </p>
          <ul className="flex flex-col gap-1.5">
            {ownExpiryItems.map((item) => (
              <li key={`${item.field}-${item.expiry_date}`} className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant={item.status === "expired" ? "destructive" : "accent"}>
                  {item.status === "expired" ? "Expired" : "Expiring soon"}
                </Badge>
                <span>{COMPLIANCE_EXPIRY_FIELD_LABELS[item.field]}</span>
                <span className="text-muted-foreground">{formatDate(item.expiry_date)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="rounded-lg border border-border p-3">
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Cl.14 checklist dossier
          </p>
          <Button size="sm" variant="outline" onClick={handleDownloadDossier} disabled={dossierDownloading || dossierQuery.isError}>
            <Download className="h-3.5 w-3.5" /> Download PDF
          </Button>
        </div>
        {dossierError && <p className="mb-2 text-sm text-destructive">Failed to download dossier: {dossierError}</p>}
        {dossierQuery.isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : dossierQuery.isError ? (
          <ErrorBanner message={`Failed to load the compliance dossier: ${errorMessage(dossierQuery.error)}`} />
        ) : dossierQuery.data ? (
          <>
            <div className="mb-2 flex items-center gap-2 text-sm">
              <Badge variant={dossierQuery.data.overall_compliant ? "success" : "destructive"}>
                {dossierQuery.data.overall_compliant ? "Compliant" : "Missing items"}
              </Badge>
              <span className="text-xs text-muted-foreground">
                Generated {formatDateTimeShort(dossierQuery.data.generated_at)}
              </span>
            </div>
            <ul className="flex flex-col gap-1.5 text-sm">
              {dossierQuery.data.items.map((item) => (
                <li key={item.key} className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-1.5">
                  <span>{item.label}</span>
                  <span className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">
                      {item.document_count} document{item.document_count === 1 ? "" : "s"}
                    </span>
                    <Badge variant={item.satisfied ? "success" : "outline"}>{item.satisfied ? "On file" : "Missing"}</Badge>
                  </span>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </div>

      <div className="rounded-lg border border-border p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Documents</p>
        {documentsQuery.isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : documentsQuery.isError ? (
          <ErrorBanner message={`Failed to load documents: ${errorMessage(documentsQuery.error)}`} />
        ) : (documentsQuery.data?.items.length ?? 0) === 0 ? (
          <EmptyState title="No documents uploaded" description="No compliance documents are on file for this vehicle yet." />
        ) : (
          <Table
            columns={DOCUMENT_COLUMNS}
            data={documentsQuery.data!.items}
            rowKey={(d) => d.id}
            pageSize={10}
            label="Compliance documents for this vehicle"
          />
        )}
      </div>
    </div>
  );
}
