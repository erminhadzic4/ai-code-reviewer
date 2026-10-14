import { describe, expect, it } from 'vitest'
import { adviceFor } from './errorActions'

describe('adviceFor', () => {
  it.each(['REPO_NOT_FOUND', 'REPO_EMPTY', 'REPO_TOO_LARGE', 'NO_REVIEWABLE_FILES'])(
    'does not offer to repeat a review that failed because of the repository (%s)',
    (code) => {
      expect(adviceFor(code)).toEqual({ canRetry: false, hint: 'Check the URL, or review another repository.' })
    },
  )

  it('points at the configuration when a key is refused', () => {
    expect(adviceFor('PROVIDER_AUTH_FAILED')).toMatchObject({ canRetry: true, hint: expect.stringContaining('API keys') })
  })

  it('offers to try again after a passing failure', () => {
    expect(adviceFor('GITHUB_UNAVAILABLE')).toEqual({ canRetry: true, hint: null })
  })

  it('offers to try again for a code it does not know', () => {
    expect(adviceFor('SOMETHING_NEW')).toEqual({ canRetry: true, hint: null })
  })
})
