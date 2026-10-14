import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { getReviewStatus } from '../api/reviews'
import type { ReviewStatusResponse } from '../api/types'

export const POLL_INTERVAL_MS = 2000

export interface ReviewStatusState {
  review: ReviewStatusResponse | null
  /** The last request failed; a passing failure is retried, a 4xx is final. */
  error: ApiError | null
}

function isFinal(review: ReviewStatusResponse): boolean {
  return review.status === 'COMPLETED' || review.status === 'FAILED'
}

/**
 * Follows a review: asks for its status, then again every two seconds until it is
 * completed or failed. The next request is planned only when the previous one has
 * answered, so a slow network never stacks requests.
 */
export function useReviewStatus(id: string): ReviewStatusState {
  // The state remembers which review it belongs to, so another id starts empty without a
  // reset inside the effect.
  const [state, setState] = useState<ReviewStatusState & { id: string }>({ id, review: null, error: null })

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    async function poll() {
      try {
        const review = await getReviewStatus(id, controller.signal)
        setState({ id, review, error: null })
        if (!isFinal(review)) {
          timer = setTimeout(poll, POLL_INTERVAL_MS)
        }
      } catch (e) {
        if (controller.signal.aborted) {
          return
        }
        const error = e instanceof ApiError ? e : new ApiError(0, 'The review could not be loaded.')
        setState((previous) => ({ id, review: previous.id === id ? previous.review : null, error }))
        // A network failure or a server error can pass; a 404 or a 401 cannot.
        if (error.status === 0 || error.status >= 500) {
          timer = setTimeout(poll, POLL_INTERVAL_MS)
        }
      }
    }

    poll()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [id])

  return state.id === id ? { review: state.review, error: state.error } : { review: null, error: null }
}
