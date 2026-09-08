/** Display formatting for the Compliance page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

import type { DocType } from "@/hooks/useComplianceVault";

export { formatDateTime, extractErrorMessage } from "@/lib/format";

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
