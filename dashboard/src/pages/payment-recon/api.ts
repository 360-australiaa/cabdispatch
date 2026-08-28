import { keepPreviousData, useQuery } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";
import type { PaymentListResponse, PaymentStatus, ReconciliationMethod } from "./types";

export interface ListPaymentsFilters {
  status?: PaymentStatus;
  trip_id?: string;
  skip: number;
  limit: number;
}

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
    queryKey: ["payment-recon", method, filters],
    queryFn: () => listPayments(method, filters),
    placeholderData: keepPreviousData,
  });
}
