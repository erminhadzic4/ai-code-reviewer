/** What the failure screen offers for an error code of a failed review. */
export interface FailureAdvice {
  /** Whether submitting the same repository again can succeed. */
  canRetry: boolean
  /** A sentence on what to do, added to the backend's message. */
  hint: string | null
}

// The same repository fails the same way again: the problem is the repository itself.
const ABOUT_THE_REPOSITORY = new Set([
  'REPO_NOT_FOUND',
  'REPO_UNAVAILABLE',
  'REPO_EMPTY',
  'REPO_TOO_LARGE',
  'NO_REVIEWABLE_FILES',
  'PROVIDER_BLOCKED',
])

// Fixed only by whoever runs the application.
const ABOUT_THE_SETUP = new Set(['GITHUB_AUTH_FAILED', 'PROVIDER_AUTH_FAILED', 'PROVIDER_MISCONFIGURED'])

export function adviceFor(code: string): FailureAdvice {
  if (ABOUT_THE_REPOSITORY.has(code)) {
    return { canRetry: false, hint: 'Check the URL, or review another repository.' }
  }
  if (ABOUT_THE_SETUP.has(code)) {
    return { canRetry: true, hint: 'Check the API keys the backend is configured with, then try again.' }
  }
  if (code === 'PROVIDER_QUOTA_EXHAUSTED') {
    return { canRetry: true, hint: 'The model’s daily quota is used up. It resets within a day.' }
  }
  return { canRetry: true, hint: null }
}
