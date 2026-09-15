import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type {
  CabChargeAuthorizeBody,
  CabChargeAuthorizeResponse,
  PaymentListResponse,
  PaymentRead,
  PaymentStatus,
  PaymentUpdateBody,
  ReconciliationMethod,
  TTSSClaimBody,
  TTSSClaimResponse,
} from "./types";

export interface ListPaymentsFilters {
  status?: PaymentStatus;
  trip_id?: string;
  skip: number;
  limit: number;
}

const RECON_KEY = "payment-recon";

/** `GET /v1/payments`'s own per-request cap (`le=200` on the route). */
export const EXPORT_FETCH_LIMIT = 200;

/**
 * GET /v1/payments -- see backend/app/api/v1/payments.py::list_payments.
 * `method` is always sent so this view only ever sees cabcharge/ttss rows;
 * `status`/`trip_id` are optional server-side filters, `skip`/`limit` drive
 * pagination (max limit is 200 per the route).
 */
export async function listPayments(
  method: ReconciliationMethod,
  filters: ListPaymentsFilters,
): Promise<PaymentListResponse> {
  const { data } = await apiClient.get<PaymentListResponse>("/v1/payments", {
    params: {
      method,
      status: filters.status || undefined,
      trip_id: filters.trip_id || undefined,
      skip: filters.skip,
      limit: filters.limit,
    },
  });
  return data;
}

export function usePaymentsList(method: ReconciliationMethod, filters: ListPaymentsFilters) {
  return useQuery({
    queryKey: [RECON_KEY, method, filters],
    queryFn: () => listPayments(method, filters),
    placeholderData: keepPreviousData,
  });
}

/** `GET /v1/payments/{id}` -- one docket, for the detail panel. Seeded from
 * the list row via `initialData` so the panel opens instantly and the
 * refetch only corrects it. */
export function usePaymentQuery(paymentId: string | null, initial?: PaymentRead) {
  return useQuery({
    queryKey: [RECON_KEY, "payment", paymentId],
    queryFn: async () => {
      const { data } = await apiClient.get<PaymentRead>(`/v1/payments/${paymentId}`);
      return data;
    },
    enabled: paymentId != null,
    initialData: initial && initial.id === paymentId ? initial : undefined,
  });
}

function invalidateRecon(qc: ReturnType<typeof useQueryClient>) {
  qc.invalidateQueries({ queryKey: [RECON_KEY] });
}

/** `PATCH /v1/payments/{id}` -- status / notes / docket / captured_at. */
export function useUpdatePaymentMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: PaymentUpdateBody }) => {
      const { data } = await apiClient.patch<PaymentRead>(`/v1/payments/${id}`, body);
      return data;
    },
    onSuccess: () => invalidateRecon(qc),
  });
}

/** `POST /v1/payments/cabcharge/authorize` -- creates a new pending docket. */
export function useAuthorizeCabchargeMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CabChargeAuthorizeBody) => {
      const { data } = await apiClient.post<CabChargeAuthorizeResponse>("/v1/payments/cabcharge/authorize", body);
      return data;
    },
    onSuccess: () => invalidateRecon(qc),
  });
}

/** `POST /v1/payments/ttss/claim` -- creates a new pending claim docket. */
export function useTtssClaimMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: TTSSClaimBody) => {
      const { data } = await apiClient.post<TTSSClaimResponse>("/v1/payments/ttss/claim", body);
      return data;
    },
    onSuccess: () => invalidateRecon(qc),
  });
}

/** Every row matching the current filters, for the CSV export -- one call
 * at the route's 200 cap. Returns the rows and the real `total` so the
 * caller can say when the export was truncated instead of silently
 * shipping a partial claim file. */
export async function listPaymentsForExport(
  method: ReconciliationMethod,
  filters: Pick<ListPaymentsFilters, "status" | "trip_id">,
): Promise<{ items: PaymentRead[]; total: number }> {
  const page = await listPayments(method, { ...filters, skip: 0, limit: EXPORT_FETCH_LIMIT });
  return { items: page.items, total: page.total };
}
