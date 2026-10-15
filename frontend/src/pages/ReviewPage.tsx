import { useParams } from 'react-router'
import { ButtonLink } from '../components/Button'
import { Eyebrow } from '../components/Eyebrow'
import { InlineError } from '../components/InlineError'
import { Skeleton } from '../components/Skeleton'
import { shortSha } from '../review/format'
import { ReviewFailed } from '../review/ReviewFailed'
import { ReviewHeader } from '../review/ReviewHeader'
import { ReviewProgress } from '../review/ReviewProgress'
import { ReviewReport } from '../review/ReviewReport'
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
            <Eyebrow>Review</Eyebrow>
            <h1 className={styles.missingTitle}>Review not found</h1>
            <p className={styles.missingText}>It does not exist, or it belongs to another account.</p>
            <ButtonLink to="/reviews" variant="primary" size="lg" arrow>
              Your reviews
            </ButtonLink>
          </div>
        </div>
      )
    }
    return (
      <div className={`container ${styles.page} ${styles.loading}`}>
        {error && <InlineError>{error.message} Retrying…</InlineError>}
        <Skeleton rows={4} label="Loading the review" />
      </div>
    )
  }

  if (review.status === 'COMPLETED') {
    return (
      <div className={`container ${styles.page}`}>
        <ReviewReport review={review} />
      </div>
    )
  }

  const header = <ReviewHeader repo={review.repo} meta={[review.branch, review.commitSha && shortSha(review.commitSha)]} />
  const retrying = error && <InlineError>{error.message} Retrying…</InlineError>

  if (review.status === 'FAILED') {
    return (
      <div className={`container ${styles.page}`}>
        {header}
        {retrying}
        <ReviewFailed review={review} />
      </div>
    )
  }

  return (
    <div className={`container ${styles.progress}`}>
      <div className={styles.intro}>
        <Eyebrow>Review in progress</Eyebrow>
        {header}
        <p className={styles.note}>You can leave this page; the review keeps running and stays in your list.</p>
        {retrying}
      </div>
      <ReviewProgress review={review} />
    </div>
  )
}
