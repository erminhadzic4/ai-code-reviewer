import type { ReviewStatusResponse } from '../api/types'
import { Button, ButtonLink } from '../components/Button'
import { InlineError } from '../components/InlineError'
import { adviceFor } from './errorActions'
import styles from './ReviewFailed.module.css'
import { useResubmit } from './useResubmit'

export function ReviewFailed({ review }: { review: ReviewStatusResponse }) {
  const { resubmit, busy, error } = useResubmit(review.repo)
  const code = review.error?.code ?? 'UNKNOWN'
  const advice = adviceFor(code)

  return (
    <section className={styles.panel} aria-labelledby="failed-title">
      <h2 id="failed-title" className={styles.title}>
        The review failed
      </h2>
      <p className={styles.message}>{review.error?.message ?? 'The review stopped without a reason.'}</p>
      {advice.hint && <p className={styles.hint}>{advice.hint}</p>}
      <p className={styles.code}>
        {code} · attempt {review.attempt} of {review.maxAttempts}
      </p>
      <div className={styles.actions}>
        {advice.canRetry ? (
          <>
            <Button variant="primary" busy={busy} onClick={resubmit}>
              {busy ? 'Submitting…' : 'Try again'}
            </Button>
            <ButtonLink to="/" variant="text">
              Review another repository
            </ButtonLink>
          </>
        ) : (
          <ButtonLink to="/" variant="primary">
            Review another repository
          </ButtonLink>
        )}
      </div>
      {error && <InlineError>{error}</InlineError>}
    </section>
  )
}
