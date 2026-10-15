import type { ReviewStatus } from '../api/types'
import styles from './StatusMark.module.css'

const STATUS_LABEL: Record<ReviewStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
}

/**
 * The status of a review as a dot and its name. Dots are round, so they never look like the
 * square marks of finding levels.
 */
export function StatusMark({ status }: { status: ReviewStatus }) {
  return (
    <span className={`${styles.status} ${styles[status]}`}>
      <span className={styles.dot} aria-hidden="true" />
      {STATUS_LABEL[status]}
    </span>
  )
}
