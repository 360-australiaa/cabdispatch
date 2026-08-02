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
