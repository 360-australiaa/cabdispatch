import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useUpdateDocumentMutation,
  useUploadDocumentMutation,
  type ComplianceDocument,
  type DocType,
} from "@/hooks/useComplianceVault";
import { DOC_TYPE_OPTIONS, extractErrorMessage } from "./format";

export interface DocumentFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  /** Vehicle the document belongs to (create mode) — display only, sourced
   * from the page's vehicle selector; documents cannot be moved between
   * vehicles once uploaded. */
  vehicleId: string;
  vehicleLabel: string;
  document?: ComplianceDocument;
}

/** Create (multipart upload) / edit (doc_type + notes correction only —
 * the backend does not allow changing the underlying file, vehicle, or
 * uploader on PATCH; delete and re-upload instead) modal for a compliance
 * document. */
export function DocumentFormModal({
  open,
  onClose,
  mode,
  vehicleId,
  vehicleLabel,
  document,
}: DocumentFormModalProps) {
  const [docType, setDocType] = useState<DocType>(document?.doc_type ?? "calibration_record");
  const [notes, setNotes] = useState(document?.notes ?? "");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);

  const uploadMutation = useUploadDocumentMutation();
  const updateMutation = useUpdateDocumentMutation();
  const isPending = uploadMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setDocType(document?.doc_type ?? "calibration_record");
      setNotes(document?.notes ?? "");
      setFile(null);
      setError(null);
    }
  }, [open, document]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    try {
      if (mode === "create") {
        if (!file) {
          setError("Choose a file to upload.");
          return;
        }
        await uploadMutation.mutateAsync({
          vehicle_id: vehicleId,
          doc_type: docType,
          notes: notes.trim() || null,
          file,
        });
      } else {
        if (!document) return;
        await updateMutation.mutateAsync({
          id: document.id,
          input: { doc_type: docType, notes: notes.trim() || null },
        });
      }
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "Upload document" : "Edit document"}
      description={
        mode === "create"
          ? `Uploading for vehicle ${vehicleLabel}.`
          : "Doc type and notes only — delete and re-upload to replace the file."
      }
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="document-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Upload" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="document-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Vehicle</label>
          <Input value={vehicleLabel} disabled />
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Document type</label>
          <Select
            options={DOC_TYPE_OPTIONS}
            value={docType}
            onChange={(e) => setDocType(e.target.value as DocType)}
            required
          />
        </div>

        {mode === "create" && (
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">File</label>
            <Input
              type="file"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              required
            />
          </div>
        )}

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Notes (optional)</label>
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes" />
        </div>

        {error && (
          <div className="flex items-start gap-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </form>
    </Modal>
  );
}
