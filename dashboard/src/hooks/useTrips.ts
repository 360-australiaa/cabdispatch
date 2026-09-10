import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import apiClient from "@/lib/apiClient";

/**
 * Data layer for the Trips module (`src/pages/trips`). Mirrors
 * `shared/openapi.json` schemas TripRead / TripCreate / TripUpdate /
 * TripCloseRequest and the `/v1/trips`, `/v1/vehicles`, `/v1/drivers`,
 * `/v1/tariffs` read endpoints (see shared/API_SUMMARY.md).
 *
 * Money fields (flag_fall, dist_amount, wait_amount, peak_amount, tolls,
 * psl, extras, subtotal, surcharge, total, gst_component, variance_pct) are
 * decimal strings straight off the wire — never coerced here. Formatting
 * happens explicitly at render time in the page component.
 */

export type TripType = "rank_hail" | "booked" | "airport_fixed" | "multi_hire";
export type TripStatus = "open" | "closed";
export type TimeClass = "day" | "night" | "holiday";
export type PaymentMethod = "cash" | "card" | "voucher" | "account" | "split_fare";

/** One leg of a split-fare trip — `PATCH .../close` and `POST /v1/trips/sync`
 * both require these to sum, to the cent, to the trip's grand total. */
export interface SplitPaymentItem {
  method: string;
  amount: string;
}

export interface GpsBlackoutEvent {
  start: string;
  end: string;
  elapsed_s: number;
  /** Real corridor distance billed for this gap, as a decimal string — `null`
   * when no known toll-road corridor explained it (billed nothing extra). */
  matched_km: string | null;
}

export interface Trip {
  id: string;
  tenant_id: string;
  client_uuid: string;
  vehicle_id: string;
  driver_id: string;
  shift_id: string | null;
  tariff_id: string;
  type: TripType;
  /** True when the meter drove this trip on FABRICATED GPS from its built-in test
   * simulator, not a real road. A simulated trip's trace replays cleanly and passes
   * the server's fare-variance check exactly like a real fare, so this flag is the
   * only thing distinguishing a test drive from real revenue — surface it wherever a
   * trip is shown as money. See backend `app/models/trips.py::Trip.simulated`. */
  simulated: boolean;
  status: TripStatus;
  time_class: TimeClass;
  is_peak: boolean;
  /** Resolved server-side from the vehicle's real fleet-domain vehicle_class
   * at creation time — advisory only if sent on create/update, never trusted
   * for billing (see backend/app/services/trips.py::resolve_is_maxi_vehicle).
   * The real maxi-rate triggers are passenger_count/wheelchair_hiring/
   * airport_rank_requested_maxi below. */
  maxi: boolean;
  passenger_count: number;
  wheelchair_hiring: boolean;
  airport_rank_requested_maxi: boolean;
  voucher_code: string | null;
  account_reference: string | null;
  split_payments: SplitPaymentItem[] | null;
  start_at: string;
  end_at: string | null;
  start_lat: number;
  start_lng: number;
  end_lat: number | null;
  end_lng: number | null;
  distance_m: number;
  moving_s: number;
  waiting_s: number;
  flag_fall: string;
  dist_amount: string;
  wait_amount: string;
  peak_amount: string;
  tolls: string;
  psl: string;
  extras: string;
  subtotal: string;
  surcharge: string;
  total: string;
  gst_component: string;
  payment_method: PaymentMethod;
  gps_trace_ref: string | null;
  max_fare_check_passed: boolean;
  variance_pct: string | null;
  receipt_ref: string | null;
  flagged_for_review: boolean;
  review_notes: string | null;
  /** Ad hoc-geofence toll ids (kind="toll" OR kind="airport") already folded
   * into `tolls` above — see backend/app/models/trips.py::Trip.auto_tolls_applied's
   * own doc comment. IDs only, no amount/kind on this field alone; the
   * Vehicle page's Tolls tab cross-references these against `GET /v1/geofences`
   * (which carries `kind` and `toll_amount`) to split an auto-charged airport
   * access fee from an ad hoc toll circle. Optional because this dashboard's
   * `Trip` type predates the field being surfaced here -- a trip fetched
   * before this addition landed in a cached response simply omits it, same
   * "absent means not known here yet" convention as everywhere else this
   * type uses `| null`. */
  auto_tolls_applied?: string[] | null;
  /** Real NSW toll-road registry charges already folded into `tolls` above,
   * keyed by `TollRoad.id` (or `TollPoint.id` for a per_point road) -- one
   * entry per road/point, value is the dollar amount charged for it as a
   * string. See backend/app/models/trips.py::Trip.auto_tolled_roads's own
   * doc comment. This is the one field that gives an exact per-road dollar
   * breakdown; cross-reference the keys against `GET /v1/toll-roads` for
   * road/point names. */
  auto_tolled_roads?: Record<string, string> | null;
  /** Real toll roads/points this trip genuinely crossed but that were NOT
   * auto-charged (no captured price for that road) -- surfaced so a
   * dispatcher knows a manual toll may be missing, never a guessed amount. */
  unpriced_toll_road_ids?: string[] | null;
  /** One entry per real GPS blackout (a road tunnel) this trip's telemetry
   * passed through — see backend/app/models/trips.py::Trip.gps_blackout_events's
   * own doc comment for the exact shape. `matched_km` is the real distance
   * billed for that gap if a known, mapped toll-road corridor explained it,
   * `null` if none did (the gap was billed nothing extra) — either way, this
   * is the audit trail a dispute over a tunnel-crossed fare is checked
   * against. Optional for the same "absent means not known here yet" reason
   * as the toll fields above. */
  gps_blackout_events?: GpsBlackoutEvent[] | null;
  created_at: string;
  updated_at: string;
}

export interface TripListResponse {
  items: Trip[];
  total: number;
  skip: number;
  limit: number;
}

export interface TripListFilters {
  status?: TripStatus;
  type?: TripType;
  vehicle_id?: string;
  driver_id?: string;
  flagged_for_review?: boolean;
  /** ISO datetime bounds on `start_at`/`end_at`, inclusive — real server-side
   * filters (`backend/app/api/v1/trips.py::list_trips`), not a client-side
   * approximation over a capped page. `end_from`/`end_to` only ever match
   * closed trips (an open trip's `end_at` is null), so pair them with
   * `status: "closed"` — see `useIncentiveProgressQuery` in
   * `pages/driver-engagement/hooks.ts` for exactly that combination, which
   * can now read an exact `total` for a trip-count window instead of the
   * "floor over the most recent 200" it previously had to report. */
  start_from?: string;
  start_to?: string;
  end_from?: string;
  end_to?: string;
  skip?: number;
  limit?: number;
}

export interface TripCreateInput {
  client_uuid: string;
  vehicle_id: string;
  driver_id: string;
  shift_id?: string | null;
  tariff_id: string;
  type: TripType;
  start_at?: string | null;
  start_lat: number;
  start_lng: number;
  payment_method?: PaymentMethod;
  time_class?: TimeClass;
  is_peak?: boolean;
  /** Advisory only — see Trip.maxi's doc comment. Kept purely for backward
   * wire compatibility; the dashboard form no longer surfaces this as if it
   * controlled billing. */
  maxi?: boolean;
  passenger_count?: number;
  wheelchair_hiring?: boolean;
  airport_rank_requested_maxi?: boolean;
  voucher_code?: string | null;
  account_reference?: string | null;
  tolls?: string | number;
  extras?: string | number;
  gps_trace_ref?: string | null;
}

export interface TripUpdateInput {
  vehicle_id?: string | null;
  driver_id?: string | null;
  shift_id?: string | null;
  tariff_id?: string | null;
  payment_method?: PaymentMethod | null;
  voucher_code?: string | null;
  account_reference?: string | null;
  split_payments?: SplitPaymentItem[] | null;
  tolls?: string | number | null;
  extras?: string | number | null;
  gps_trace_ref?: string | null;
  receipt_ref?: string | null;
  end_lat?: number | null;
  end_lng?: number | null;
}

export interface TripCloseInput {
  end_at?: string | null;
  end_lat?: number | null;
  end_lng?: number | null;
  payment_method?: PaymentMethod | null;
  voucher_code?: string | null;
  account_reference?: string | null;
  split_payments?: SplitPaymentItem[] | null;
  surcharge_pct?: string | number | null;
  cleaning_fee?: string | number;
  include_psl?: boolean;
  receipt_ref?: string | null;
}

/** Body for `PATCH /v1/trips/{id}/flag` — Blueprint 5.2.5's dispute-flag
 * button. `flagged=true` (default) requires a non-empty `reason`; the trip
 * must be closed (409 otherwise). Clearing a flag (`flagged=false`) is
 * staff-only server-side and ignores `reason`. */
export interface TripFlagInput {
  flagged?: boolean;
  reason?: string | null;
}

export interface VehicleLite {
  id: string;
  rego: string;
  vehicle_class: string;
  status: string;
}

export interface DriverLite {
  id: string;
  name: string;
  phone: string | null;
  user_status: string;
  on_shift: boolean;
}

export interface TariffLite {
  id: string;
  name: string;
  region: "urban" | "country" | "exempt";
  booked: boolean;
}

const TRIPS_KEY = "trips";

export function useTripsQuery(filters: TripListFilters) {
  return useQuery({
    queryKey: [TRIPS_KEY, filters],
    queryFn: async () => {
      const res = await apiClient.get<TripListResponse>("/v1/trips", { params: filters });
      return res.data;
    },
    placeholderData: (prev) => prev,
  });
}

/** Lookup lists for the create/edit forms and for resolving id -> label in the table. Capped at each endpoint's server-side max. */
export function useVehiclesLookupQuery() {
  return useQuery({
    queryKey: ["trips-vehicles-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: VehicleLite[] }>("/v1/vehicles", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useDriversLookupQuery() {
  return useQuery({
    queryKey: ["trips-drivers-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: DriverLite[] }>("/v1/drivers", {
        params: { limit: 100 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useTariffsLookupQuery() {
  return useQuery({
    queryKey: ["trips-tariffs-lookup"],
    queryFn: async () => {
      const res = await apiClient.get<{ items: TariffLite[] }>("/v1/tariffs", {
        params: { limit: 200 },
      });
      return res.data.items;
    },
    staleTime: 60_000,
  });
}

export function useCreateTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: TripCreateInput) => {
      const res = await apiClient.post<Trip>("/v1/trips", input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useUpdateTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripUpdateInput }) => {
      const res = await apiClient.patch<Trip>(`/v1/trips/${id}`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useDeleteTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/v1/trips/${id}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useCloseTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripCloseInput }) => {
      const res = await apiClient.post<Trip>(`/v1/trips/${id}/close`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

export function useFlagTripMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id: string; input: TripFlagInput }) => {
      const res = await apiClient.patch<Trip>(`/v1/trips/${id}/flag`, input);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [TRIPS_KEY] });
    },
  });
}

// --- GPS trace (trip-detail route map) --------------------------------------
// `GET /v1/trips/{id}/gps-trace` -- a DEDICATED endpoint, deliberately not a
// field on `Trip` above: the backend keeps the (potentially ~100-300KB, one
// point per second recorded) trace out of TripRead/TripListResponse entirely
// so a page of trips never balloons — see backend/app/models/trips.py's
// TripGpsTrace docstring. Fetched only by useTripGpsTraceQuery below, called
// from TripDetailModal only while it's actually open (its `enabled` flag),
// never from the trips list/table.

export interface TripGpsTracePoint {
  lat: number;
  lng: number;
  speed_kmh: number;
  ts: string;
}

export interface TripGpsTraceResponse {
  trip_id: string;
  points: TripGpsTracePoint[];
  point_count: number;
}

/**
 * `points: []` is this endpoint's own honest "no trace stored for this trip"
 * answer (a trip opened+closed online never carries one; a synced trip may
 * have been uploaded with an empty `gps_trace` — today's Android-bug
 * reality) — never treated as an error. `TripRouteMap` already degrades
 * correctly on an empty/short trace (falls back to the labelled straight-line
 * stand-in), so callers can pass `data?.points` straight through unchanged.
 */
export function useTripGpsTraceQuery(tripId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: [TRIPS_KEY, tripId, "gps-trace"],
    queryFn: async () => {
      const res = await apiClient.get<TripGpsTraceResponse>(`/v1/trips/${tripId}/gps-trace`);
      return res.data;
    },
    enabled: enabled && tripId != null,
  });
}

// --- Single trip (dashboard trip-detail page) -------------------------------
// `GET /v1/trips/{id}` -- the plain `TripRead` row, real and unfiltered by
// tenant beyond the usual `get_current_tenant_id` scoping. `/trips/:tripId`
// (command-centre plan §7) is the first caller that needs one trip on its
// own rather than a page of them.

export function useTripQuery(tripId: string | null) {
  return useQuery({
    queryKey: [TRIPS_KEY, tripId],
    queryFn: async () => {
      const res = await apiClient.get<Trip>(`/v1/trips/${tripId}`);
      return res.data;
    },
    enabled: tripId != null,
  });
}

// --- Payments for one trip (Trip page Payments tab) -------------------------
// `GET /v1/payments?trip_id=` -- real server-side filter on
// `backend/app/api/v1/payments.py::list_payments`; `method` is optional
// there (defaults to every method), unlike `pages/payment-recon/api.ts`'s
// `usePaymentsList`, which always pins `method` to cabcharge/ttss for that
// page's reconciliation view specifically. This trip-scoped read wants every
// payment row for the trip regardless of method, so it calls the endpoint
// directly rather than routing through that narrower hook.

export type TripPaymentMethod = "tap_to_pay" | "link" | "cash" | "cabcharge" | "ttss";
export type TripPaymentStatus =
  | "pending"
  | "requires_action"
  | "succeeded"
  | "failed"
  | "refunded"
  | "canceled";

export interface TripPayment {
  id: string;
  tenant_id: string;
  trip_id: string;
  method: TripPaymentMethod;
  amount: string;
  surcharge: string;
  stripe_pi_id: string | null;
  status: TripPaymentStatus;
  captured_at: string | null;
  change_given: string | null;
  docket_number: string | null;
  notes: string | null;
  subsidy_amount: string | null;
  passenger_paid_amount: string | null;
  created_at: string;
  updated_at: string;
}

export interface TripPaymentListResponse {
  items: TripPayment[];
  total: number;
  skip: number;
  limit: number;
}

export function useTripPaymentsQuery(tripId: string | null) {
  return useQuery({
    queryKey: [TRIPS_KEY, tripId, "payments"],
    queryFn: async () => {
      const res = await apiClient.get<TripPaymentListResponse>("/v1/payments", {
        params: { trip_id: tripId, limit: 50 },
      });
      return res.data;
    },
    enabled: tripId != null,
  });
}

// --- Receipt resend (Trip page Receipt tab) ---------------------------------
// `POST /v1/trips/{id}/receipt/email` and `/receipt/sms` -- real, tested on
// the backend, never called from the dashboard until now (plan §7 flags
// exactly this gap). Both require the trip to be closed server-side (409
// otherwise, since the fare columns the PDF renders from are only final
// after `.../close`); both regenerate/reuse the stored receipt PDF and
// return a `mock`/`would_send_to` shape identical to the Stripe integration's
// own mock-fallback contract (see `app/services/receipts.py`).

export interface ReceiptEmailResponse {
  mock: boolean;
  would_send_to?: string | null;
  to_email?: string | null;
  sendgrid_status_code?: number | null;
  receipt_ref: string | null;
  pdf_relative_path: string;
  pdf_generated_now: boolean;
}

export interface ReceiptSmsResponse {
  mock: boolean;
  would_send_to?: string | null;
  to_phone?: string | null;
  twilio_sid?: string | null;
  message?: string | null;
  receipt_ref: string | null;
  pdf_relative_path: string;
  pdf_generated_now: boolean;
}

export function useEmailReceiptMutation() {
  return useMutation({
    mutationFn: async ({ tripId, toEmail }: { tripId: string; toEmail: string }) => {
      const res = await apiClient.post<ReceiptEmailResponse>(`/v1/trips/${tripId}/receipt/email`, {
        to_email: toEmail,
      });
      return res.data;
    },
  });
}

export function useSmsReceiptMutation() {
  return useMutation({
    mutationFn: async ({ tripId, toPhone }: { tripId: string; toPhone: string }) => {
      const res = await apiClient.post<ReceiptSmsResponse>(`/v1/trips/${tripId}/receipt/sms`, {
        to_phone: toPhone,
      });
      return res.data;
    },
  });
}
