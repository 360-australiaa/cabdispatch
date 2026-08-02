/** Mirrors backend `app/schemas/jobs.py` + `app/models/jobs.py` (shared/openapi.json). */

export type JobStatus = "queued" | "offered" | "accepted" | "expired" | "cancelled";

export type JobOfferStatus = "pending" | "accepted" | "declined" | "expired";

export const JOB_TERMINAL_STATUSES: readonly JobStatus[] = ["accepted", "expired", "cancelled"];

export function isTerminalJobStatus(status: string): boolean {
  return (JOB_TERMINAL_STATUSES as readonly string[]).includes(status);
}

export interface Job {
  id: string;
  tenant_id: string;
  origin_lat: number;
  origin_lng: number;
  origin_address: string;
  dest_lat: number;
  dest_lng: number;
  dest_address: string;
  status: JobStatus | string;
  fare_estimate_low: string;
  fare_estimate_high: string;
  requested_at: string;
  created_by_user_id: string | null;
  accepted_by_driver_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface JobOffer {
  id: string;
  job_id: string;
  tenant_id: string;
  driver_id: string;
  status: JobOfferStatus | string;
  offered_at: string;
  expires_at: string;
  responded_at: string | null;
}

export interface JobListResponse {
  items: Job[];
  total: number;
  skip: number;
  limit: number;
}

export interface JobListParams {
  skip?: number;
  limit?: number;
  status?: string;
}

export interface JobCreateBody {
  origin_lat: number;
  origin_lng: number;
  origin_address: string;
  dest_lat: number;
  dest_lng: number;
  dest_address: string;
  fare_estimate_low: number;
  fare_estimate_high: number;
}
