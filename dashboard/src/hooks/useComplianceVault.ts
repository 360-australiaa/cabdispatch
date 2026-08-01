import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Compliance Vault module (`src/pages/compliance`).
 * Mirrors `shared/openapi.json` schemas ComplianceDocumentRead /
 * ComplianceDocumentUpdate / ComplianceDossierRead / ChecklistItemRead and
 * the `/v1/compliance/...` endpoints (see shared/API_SUMMARY.md).
 *
 * Also pulls a lightweight vehicle list from `/v1/fleet/vehicles` purely to
 * populate the vehicle picker — the Fleet module (src/pages/fleet) owns the
 * real fleet data layer; this hook only reads `id`/`rego` for its own
 * selector and does not attempt CRUD on vehicles.
 */

export const DOC_TYPES = [
  "calibration_record",
  "mounting_photo",
  "accuracy_test",
  "cl14_checklist",
  "camera_register",
  "duress_register",
  "tracking_register",
] as const;

export type DocType = (typeof DOC_TYPES)[number];

export interface ComplianceDocument {
  id: string;
  tenant_id: string;
  vehicle_id: string;
  doc_type: DocType;
  file_path: string;
  original_filename: string;
  content_type: string | null;
  uploaded_by: string;
  uploaded_at: string;
  notes: string | null;
  created_at: string;
  updated_at: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

export interface Vehicle {
  id: string;
  rego: string;
  vehicle_class: string;
  status: string;
}

export interface ChecklistItem {
  key: string;
  label: string;
  doc_types: DocType[];
  satisfied: boolean;
  document_count: number;
  documents: ComplianceDocument[];
}

export interface VehicleDossier {
  tenant_id: string;
  vehicle_id: string;
  generated_at: string;
  items: ChecklistItem[];
  overall_compliant: boolean;
  missing_items: string[];
}

export interface DocumentListFilters {
  vehicle_id: string | null;
  doc_type?: DocType | "";
  skip?: number;
  limit?: number;
}

export interface DocumentUploadInput {
  vehicle_id: string;
  doc_type: DocType;
  notes?: string | null;
  file: File;
}

export interface DocumentUpdateInput {
  doc_type?: DocType;
  notes?: string | null;
}

const VEHICLES_KEY = "compliance-vehicles";
const DOCUMENTS_KEY = "compliance-documents";
const DOSSIER_KEY = "compliance-dossier";

/** Vehicle picker source. Fetches up to 100 vehicles (enough for the demo
 * fleets this dashboard targets) — swap for a searchable/paginated picker
 * if a tenant's fleet ever exceeds that. */
export function useVehiclesQuery() {
  return useQuery({
    queryKey: [VEHICLES_KEY],
    queryFn: async () => {
      const res = await apiClient.get<Page<Vehicle>>("/v1/fleet/vehicles", {
        params: { limit: 100 },
      });
      return res.data;
    },
    staleTime: 30_000,
  });
}

export function useComplianceDocumentsQuery(filters: DocumentListFilters) {
  return useQuery({
    queryKey: [DOCUMENTS_KEY, filters],
    queryFn: async () => {
      const params: Record<string, string | number> = {
        skip: filters.skip ?? 0,
        limit: filters.limit ?? 20,
      };
      if (filters.vehicle_id) params.vehicle_id = filters.vehicle_id;
      if (filters.doc_type) params.doc_type = filters.doc_type;
      const res = await apiClient.get<Page<ComplianceDocument>>("/v1/compliance/documents", {
        params,
      });
      return res.data;
    },
    enabled: filters.vehicle_id != null,
    placeholderData: (prev) => prev,
  });
}

export function useVehicleDossierQuery(vehicleId: string | null) {
  return useQuery({
    queryKey: [DOSSIER_KEY, vehicleId],
    queryFn: async () => {
      const res = await apiClient.get<VehicleDossier>(
        `/v1/compliance/vehicles/${vehicleId}/dossier`,
      );
      return res.data;
    },
    enabled: vehicleId != null,
  });
}

export function useUploadDocumentMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: DocumentUploadInput) => {
      const formData = new FormData();
      formData.append("vehicle_id", input.vehicle_id);
      formData.append("doc_type", input.doc_type);
      if (input.notes) formData.append("notes", input.notes);
      formData.append("file", input.file);
      const res = await apiClient.post<ComplianceDocument>("/v1/compliance/documents", formData);
      return res.data;
    },
    onSuccess: (doc) => {
      queryClient.invalidateQueries({ queryKey: [DOCUMENTS_KEY] });
      queryClient.invalidateQueries({ queryKey: [DOSSIER_KEY, doc.vehicle_id] });
    },
  });
}

export function useUpdateDocumentMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: DocumentUpdateInput }) => {
      const res = await apiClient.patch<ComplianceDocument>(
        `/v1/compliance/documents/${id}`,
        input,
      );
      return res.data;
    },
    onSuccess: (doc) => {
      queryClient.invalidateQueries({ queryKey: [DOCUMENTS_KEY] });
      queryClient.invalidateQueries({ queryKey: [DOSSIER_KEY, doc.vehicle_id] });
    },
  });
}

export function useDeleteDocumentMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; vehicleId: string }) => {
      await apiClient.delete(`/v1/compliance/documents/${id}`);
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: [DOCUMENTS_KEY] });
      queryClient.invalidateQueries({ queryKey: [DOSSIER_KEY, variables.vehicleId] });
    },
  });
}

/** Streams the file via the authenticated apiClient (the backend has no
 * public/unauthenticated download URL) and triggers a browser save via a
 * throwaway object URL + anchor click. */
export async function downloadComplianceDocument(doc: ComplianceDocument): Promise<void> {
  const res = await apiClient.get(`/v1/compliance/documents/${doc.id}/download`, {
    responseType: "blob",
  });
  const blob = new Blob([res.data], { type: doc.content_type ?? "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = doc.original_filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
