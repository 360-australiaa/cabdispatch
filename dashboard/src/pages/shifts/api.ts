import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type {
  DriverLite,
  Shift,
  ShiftCreateInput,
  ShiftEndInput,
  ShiftListFilters,
  ShiftListResponse,
  ShiftReport,
  ShiftStartInput,
  ShiftUpdateInput,
  VehicleLite,
} from "./types";

const SHIFTS_KEY = "shifts";

export function useShiftsQuery(filters: ShiftListFilters) {
  return useQuery({
    queryKey: [SHIFTS_KEY, filters],
    queryFn: async () => {
      const res = await apiClient.get<ShiftListResponse>("/v1/shifts", { params: filters });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

export function useShiftReportQuery(shiftId: string | null) {
  return useQuery({
    queryKey: [SHIFTS_KEY, "report", shiftId],
    queryFn: async () => {
      const res = await apiClient.get<ShiftReport>(`/v1/shifts/${shiftId}/report`);
      return res.data;
    },
    enabled: shiftId != null,
  });
}

/** Streams GET /v1/shifts/{id}/report.pdf via the authenticated apiClient
 * (no public download URL) and triggers a browser save via a throwaway
 * object URL + anchor click -- same pattern as downloadComplianceDocument
 * and downloadNswPtpExport. */
export async function downloadShiftReportPdf(shiftId: string): Promise<void> {
  const res = await apiClient.get(`/v1/shifts/${shiftId}/report.pdf`, {
    responseType: "blob",
  });
  const blob = new Blob([res.data], { type: "application/pdf" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `shift_report_${shiftId}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Streams GET /v1/shifts/{id}/report.csv via the authenticated apiClient
 * and triggers a browser save the same way as downloadShiftReportPdf. */
export async function downloadShiftReportCsv(shiftId: string): Promise<void> {
  const res = await apiClient.get(`/v1/shifts/${shiftId}/report.csv`, {
    responseType: "blob",
  });
  const blob = new Blob([res.data], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `shift_report_${shiftId}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Lookup lists for the create/edit forms and for resolving id -> label in
 * the table. Capped at each endpoint's server-side max. */
export function useDriversLookupQuery() {
  return useQuery({
    queryKey: ["shifts-drivers-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: DriverLite[] }>("/v1/drivers", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useVehiclesLookupQuery() {
  return useQuery({
    queryKey: ["shifts-vehicles-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: VehicleLite[] }>("/v1/vehicles", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

function invalidateShifts(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: [SHIFTS_KEY] });
}

/** `POST /v1/shifts/start` — the normal way a shift gets opened from the
 * dashboard side (backfilling one the driver app didn't open). */
export function useStartShiftMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ShiftStartInput) => {
      const res = await apiClient.post<Shift>("/v1/shifts/start", body);
      return res.data;
    },
    onSuccess: () => invalidateShifts(queryClient),
  });
}

/** `POST /v1/shifts` — generic admin-backfill create (lets an admin key in a
 * full historical shift record, reconciliation figures included). */
export function useCreateShiftMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: ShiftCreateInput) => {
      const res = await apiClient.post<Shift>("/v1/shifts", body);
      return res.data;
    },
    onSuccess: () => invalidateShifts(queryClient),
  });
}

/** `PATCH /v1/shifts/{id}` — admin corrections (fixing a mis-keyed
 * reconciliation figure, reassigning driver/vehicle, etc). */
export function useUpdateShiftMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ShiftUpdateInput }) => {
      const res = await apiClient.patch<Shift>(`/v1/shifts/${id}`, body);
      return res.data;
    },
    onSuccess: () => invalidateShifts(queryClient),
  });
}

/** `POST /v1/shifts/{id}/end` — closes an active shift and records the
 * driver's reconciliation figures (psl_owed, whether cash counted matches). */
export function useEndShiftMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ShiftEndInput }) => {
      const res = await apiClient.post<Shift>(`/v1/shifts/${id}/end`, body);
      return res.data;
    },
    onSuccess: () => invalidateShifts(queryClient),
  });
}

export function useDeleteShiftMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/shifts/${id}`);
    },
    onSuccess: () => invalidateShifts(queryClient),
  });
}
