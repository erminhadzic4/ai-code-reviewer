import { useParams } from 'react-router'
import { ButtonLink } from '../components/Button'
import { InlineError } from '../components/InlineError'
import { Skeleton } from '../components/Skeleton'
import { shortSha } from '../review/format'
import { ReviewFailed } from '../review/ReviewFailed'
import { ReviewHeader } from '../review/ReviewHeader'
import { ReviewProgress } from '../review/ReviewProgress'
import { useReviewStatus } from '../review/useReviewStatus'
import styles from './ReviewPage.module.css'

/** One review at one address: its progress while it runs, then its failure or its report. */
export function ReviewPage() {
  const { id = '' } = useParams()
  const { review, error } = useReviewStatus(id)

  if (!review) {
    if (error?.status === 404 || error?.status === 400) {
      return (
        <div className={`container ${styles.page}`}>
          <div className={styles.missing}>
            <h1 className={styles.missingTitle}>Review not found</h1>
            <p className={styles.missingText}>It does not exist, or it belongs to another account.</p>
            <ButtonLink to="/reviews" variant="secondary">
              Your reviews
            </ButtonLink>
          </div>
        </div>
      )
    }
    return (
      <div className={`container ${styles.page}`}>
        {error && <InlineError>{error.message} Retrying…</InlineError>}
        <Skeleton rows={4} label="Loading the review" />
      </div>
    )
  }

  return (
    <div className={`container ${styles.page}`}>
      <ReviewHeader repo={review.repo} meta={[review.branch, review.commitSha && shortSha(review.commitSha)]} />
      {error && <InlineError>{error.message} Retrying…</InlineError>}
      {review.status === 'FAILED' && <ReviewFailed review={review} />}
      {(review.status === 'QUEUED' || review.status === 'RUNNING') && <ReviewProgress review={review} />}
      {review.status === 'COMPLETED' && <p>The review is complete.</p>}
    </div>
  )
}
