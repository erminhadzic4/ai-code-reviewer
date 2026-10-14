import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { submitReview } from '../api/reviews'
import { useAuth } from '../auth/AuthContext'
import type { LoginRedirectState } from '../auth/RequireAuth'
import { Button } from '../components/Button'
import { InlineError } from '../components/InlineError'
import styles from './RepoUrlForm.module.css'

interface Props {
  /** A URL to start with, such as one entered before signing in. */
  initialUrl?: string
  autoFocus?: boolean
}

/**
 * Submits a repository for review. Only an empty field is caught here: whether the text is
 * a GitHub repository URL is the backend's decision, and its message is shown as it is.
 */
export function RepoUrlForm({ initialUrl = '', autoFocus = false }: Props) {
  const { state } = useAuth()
  const navigate = useNavigate()
  const [url, setUrl] = useState(initialUrl)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const errorId = useId()

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const repoUrl = url.trim()
    if (!repoUrl) {
      setError('Enter the URL of a GitHub repository.')
      return
    }
    if (state.status !== 'signedIn') {
      const redirect: LoginRedirectState = { from: '/', repoUrl }
      navigate('/login', { state: redirect })
      return
    }
    setBusy(true)
    setError(null)
    try {
      const review = await submitReview(repoUrl)
      navigate(`/reviews/${review.id}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The review could not be submitted.')
      setBusy(false)
    }
  }

  return (
    <form className={styles.form} onSubmit={onSubmit} noValidate>
      <div className={styles.row}>
        <label htmlFor={`${errorId}-url`} className="visually-hidden">
          GitHub repository URL
        </label>
        <input
          id={`${errorId}-url`}
          className={styles.input}
          type="url"
          inputMode="url"
          autoComplete="off"
          spellCheck={false}
          placeholder="https://github.com/owner/repository"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          autoFocus={autoFocus}
        />
        <Button type="submit" variant="primary" size="lg" busy={busy}>
          {busy ? 'Submitting…' : 'Review repository'}
        </Button>
      </div>
      {error && <InlineError id={errorId}>{error}</InlineError>}
    </form>
  )
}
