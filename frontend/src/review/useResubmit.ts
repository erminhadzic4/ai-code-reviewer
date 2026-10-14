import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { submitReview } from '../api/reviews'

/**
 * Submits a repository again and opens the new review. A new commit gets a new analysis;
 * the same commit is answered from the cache.
 */
export function useResubmit(repo: string) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function resubmit() {
    setBusy(true)
    setError(null)
    try {
      const review = await submitReview(`https://github.com/${repo}`)
      navigate(`/reviews/${review.id}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The review could not be submitted.')
    } finally {
      setBusy(false)
    }
  }

  return { resubmit, busy, error }
}
