import type { ReviewStatusResponse } from '../api/types'
import { Button, ButtonLink } from '../components/Button'
import { InlineError } from '../components/InlineError'
import { Panel } from '../components/Panel'
import { adviceFor } from './errorActions'
import styles from './ReviewFailed.module.css'
import { useResubmit } from './useResubmit'

export function ReviewFailed({ review }: { review: ReviewStatusResponse }) {
  const { resubmit, busy, error } = useResubmit(review.repo)
  const code = review.error?.code ?? 'UNKNOWN'
  const advice = adviceFor(code)

  return (
    <Panel as="section" padding="none" className={styles.frame} bodyClassName={styles.panel} aria-labelledby="failed-title">
      <p className={styles.top}>
        <span className={styles.status}>
          <span className={styles.square} aria-hidden="true" />
          Failed
        </span>
        <span className={styles.code}>
          {code} · attempt {review.attempt} of {review.maxAttempts}
        </span>
      </p>
      <h2 id="failed-title" className={styles.title}>
        The review failed
      </h2>
      <p className={styles.message}>{review.error?.message ?? 'The review stopped without a reason.'}</p>
      {advice.hint && <p className={styles.hint}>{advice.hint}</p>}
      <div className={styles.actions}>
        {advice.canRetry ? (
          <>
            <Button variant="primary" size="lg" arrow busy={busy} onClick={resubmit}>
              {busy ? 'Submitting…' : 'Try again'}
            </Button>
            <ButtonLink to="/reviews/new" variant="text">
              Review another repository
            </ButtonLink>
          </>
        ) : (
          <ButtonLink to="/reviews/new" variant="primary" size="lg" arrow>
            Review another repository
          </ButtonLink>
        )}
      </div>
      {error && <InlineError>{error}</InlineError>}
    </Panel>
  )
}
