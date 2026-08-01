import axios from "axios";
import type { DocType } from "@/hooks/useComplianceVault";

export const DOC_TYPE_LABELS: Record<DocType, string> = {
  calibration_record: "Calibration record",
  mounting_photo: "Mounting photo",
  accuracy_test: "Accuracy test",
  cl14_checklist: "Cl.14 checklist",
  camera_register: "Camera register",
  duress_register: "Duress register",
  tracking_register: "Tracking register",
};

export const DOC_TYPE_OPTIONS = (Object.keys(DOC_TYPE_LABELS) as DocType[]).map((value) => ({
  value,
  label: DOC_TYPE_LABELS[value],
}));

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-AU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Extracts a human-readable message from an axios error. Handles both
 * shapes the API returns on 4xx: a plain string `detail` and the
 * FastAPI/pydantic `HTTPValidationError` array shape (field-level 422s). */
export function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      return detail
        .map((d) => {
          const field = Array.isArray(d?.loc) ? d.loc.at(-1) : undefined;
          return field ? `${field}: ${d.msg}` : (d?.msg ?? JSON.stringify(d));
        })
        .join("; ");
    }
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}
