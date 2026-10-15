import type { CSSProperties } from 'react'
import type { ReviewStatusResponse } from '../api/types'
import { Panel } from '../components/Panel'
import { formatTime } from './format'
import styles from './ReviewProgress.module.css'

const STEPS = ['Queued', 'Fetching', 'Analysing', 'Scoring'] as const

function stepIndex(review: ReviewStatusResponse): number {
  if (review.status === 'QUEUED') return 0
  switch (review.stage) {
    case 'FETCHING':
      return 1
    case 'ANALYZING':
      return 2
    case 'SCORING':
      return 3
    default:
      return 1
  }
}

/** One sentence on what the review is doing now. */
function describeProgress(review: ReviewStatusResponse): string {
  if (review.status === 'QUEUED') {
    if (review.attempt === 0) {
      return 'Waiting in the queue. Reviews run one at a time.'
    }
    const failed = `Attempt ${review.attempt} of ${review.maxAttempts} did not finish.`
    return review.retryAt
      ? `${failed} The next attempt starts at ${formatTime(review.retryAt)}.`
      : `${failed} The next attempt starts shortly.`
  }
  switch (review.stage) {
    case 'FETCHING':
      return 'Downloading the repository from GitHub.'
    case 'ANALYZING':
      return review.chunksTotal === 0
        ? 'Preparing the files for review.'
        : `Reviewing part ${Math.min(review.chunksDone + 1, review.chunksTotal)} of ${review.chunksTotal}.`
    case 'SCORING':
      return 'Computing the scores from the findings.'
    default:
      return 'Starting the review.'
  }
}

/**
 * The steps of a running review. The sentence on top stays in one place, so a screen reader
 * announces each change; a live region that moved from step to step would be remounted.
 */
export function ReviewProgress({ review }: { review: ReviewStatusResponse }) {
  const current = stepIndex(review)
  const analysing = review.status === 'RUNNING' && review.stage === 'ANALYZING' && review.chunksTotal > 0
  const fraction = analysing ? review.chunksDone / review.chunksTotal : 0

  return (
    <Panel as="section" padding="none" bodyClassName={styles.panel} aria-labelledby="progress-title">
      <h2 id="progress-title" className="visually-hidden">
        Progress
      </h2>
      <p className={styles.sentence} aria-live="polite">
        {describeProgress(review)}
      </p>

      <ol className={styles.steps}>
        {STEPS.map((step, i) => {
          const state = i < current ? 'done' : i === current ? 'current' : 'pending'
          const measured = analysing && state === 'current'
          return (
            <li key={step} className={`${styles.step} ${styles[state]}`} aria-current={state === 'current' ? 'step' : undefined}>
              <span className={styles.stepRow}>
                <span className={styles.marker} aria-hidden="true">
                  {state === 'done' ? (
                    <svg width="14" height="14" viewBox="0 0 16 16">
                      <path d="M3.5 8.5l3 3 6-7" />
                    </svg>
                  ) : (
                    <span className={styles.markerDot} />
                  )}
                </span>
                <span className={styles.stepName}>{step}</span>
                {state === 'done' && <span className="visually-hidden"> (done)</span>}
                {measured && (
                  <span className={styles.percent} aria-hidden="true">
                    {Math.round(fraction * 100)}%
                  </span>
                )}
              </span>
              {measured && (
                <span className={styles.meter}>
                  <span
                    className={styles.track}
                    role="progressbar"
                    aria-label="Parts reviewed"
                    aria-valuemin={0}
                    aria-valuemax={review.chunksTotal}
                    aria-valuenow={review.chunksDone}
                  >
                    <span className={styles.fill} style={{ '--fraction': fraction } as CSSProperties} />
                  </span>
                  <span className={styles.meterText}>
                    {review.chunksDone} of {review.chunksTotal} parts
                  </span>
                </span>
              )}
            </li>
          )
        })}
      </ol>

      {review.status === 'QUEUED' && review.error && (
        <p className={styles.reason}>
          <span className={styles.reasonLabel}>Last attempt</span> {review.error.message}
          <code className={styles.code}>{review.error.code}</code>
        </p>
      )}
      {review.status === 'RUNNING' && review.attempt > 1 && (
        <p className={styles.reason}>
          Attempt {review.attempt} of {review.maxAttempts}.
        </p>
      )}
    </Panel>
  )
}
