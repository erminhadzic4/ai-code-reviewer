import { apiRequest } from './client'
import type { Page, ReviewResult, ReviewStatusResponse, ReviewSummary } from './types'

export function submitReview(repoUrl: string): Promise<ReviewStatusResponse> {
  return apiRequest('/api/reviews', { method: 'POST', body: { repoUrl } })
}

export function getReviewStatus(id: string, signal?: AbortSignal): Promise<ReviewStatusResponse> {
  return apiRequest(`/api/reviews/${encodeURIComponent(id)}`, { signal })
}

export function getReviewResult(id: string, signal?: AbortSignal): Promise<ReviewResult> {
  return apiRequest(`/api/reviews/${encodeURIComponent(id)}/result`, { signal })
}

export function getReviewHistory(page: number, size: number, signal?: AbortSignal): Promise<Page<ReviewSummary>> {
  return apiRequest(`/api/reviews?page=${page}&size=${size}`, { signal })
}
