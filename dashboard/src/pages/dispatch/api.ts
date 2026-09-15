import apiClient from "@/lib/apiClient";
import type { Job, JobCreateBody, JobListParams, JobListResponse, JobOffer } from "./types";

export async function listJobs(params: JobListParams): Promise<JobListResponse> {
  const res = await apiClient.get<JobListResponse>("/v1/jobs", { params });
  return res.data;
}

export async function getJob(id: string): Promise<Job> {
  const res = await apiClient.get<Job>(`/v1/jobs/${id}`);
  return res.data;
}

export async function createJob(body: JobCreateBody): Promise<Job> {
  const res = await apiClient.post<Job>("/v1/jobs", body);
  return res.data;
}

export async function cancelJob(id: string): Promise<void> {
  await apiClient.delete(`/v1/jobs/${id}`);
}

export async function listJobOffers(jobId: string): Promise<JobOffer[]> {
  const res = await apiClient.get<JobOffer[]>(`/v1/jobs/${jobId}/offers`);
  return res.data;
}

/** Body for the on-behalf accept/decline calls below. The backend's
 * `POST /v1/jobs/{job_id}/offers/{offer_id}/accept|decline` are
 * driver-identity-scoped today (`driver_id=user.id`, `app/api/v1/jobs.py`)
 * -- the driver answers their own offer from the tablet. Dispatch answering
 * *for* the driver (admin plan §3) needs the route to take the target
 * driver, which the backend is adding in parallel; sending `driver_id`
 * here is what that update keys on. Against an older backend the body is
 * simply not read (no body param declared) and the call 403s "not
 * addressed to you" -- the panel shows that verbatim. */
export interface OfferOnBehalfBody {
  driver_id: string;
}

export async function acceptJobOffer(
  jobId: string,
  offerId: string,
  body?: OfferOnBehalfBody,
): Promise<JobOffer> {
  const res = await apiClient.post<JobOffer>(`/v1/jobs/${jobId}/offers/${offerId}/accept`, body);
  return res.data;
}

export async function declineJobOffer(
  jobId: string,
  offerId: string,
  body?: OfferOnBehalfBody,
): Promise<JobOffer> {
  const res = await apiClient.post<JobOffer>(`/v1/jobs/${jobId}/offers/${offerId}/decline`, body);
  return res.data;
}
