import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { getReviewHistory } from '../api/reviews'
import type { ReviewSummary } from '../api/types'
import { Eyebrow } from '../components/Eyebrow'
import { shortSha } from '../review/format'
import { RepoUrlForm } from '../review/RepoUrlForm'
import { ReviewPreview } from '../review/ReviewPreview'
import { StatusMark } from '../review/StatusMark'
import styles from './NewReviewPage.module.css'

export const RECENT_COUNT = 3

/** The form for a new review, the latest reviews, and an example of what a report looks like. */
export function NewReviewPage() {
  const recent = useRecentReviews()

  return (
    <div className={`container ${styles.page}`}>
      <div className={styles.intro}>
        <Eyebrow>New review</Eyebrow>
        <h1 className={styles.title}>Paste a repository.</h1>
        <p className={styles.lead}>
          Second Pair reads the public code and returns findings pinned to the exact line, each with a fix you can
          copy.
        </p>
        <RepoUrlForm />
        <p className={styles.note}>Public GitHub repositories. Reviewing the same commit again returns the earlier result.</p>

        {recent.length > 0 && (
          <section className={styles.recent} aria-labelledby="recent-title">
            <div className={styles.recentHead}>
              <h2 id="recent-title" className={styles.recentTitle}>
                Recent reviews
              </h2>
              <Link to="/reviews" className={styles.allLink}>
                All reviews
              </Link>
            </div>
            <ul className={styles.recentList}>
              {recent.map((review) => (
                <li key={review.id}>
                  <Link to={`/reviews/${review.id}`} className={styles.recentRow}>
                    <span className={styles.recentRepo}>{review.repo}</span>
                    {review.commitSha && <span className={styles.recentSha}>{shortSha(review.commitSha)}</span>}
                    <span className={styles.recentState}>
                      {review.overallScore !== null ? (
                        <span className={styles.recentScore}>
                          {review.overallScore}
                          <span className="visually-hidden"> out of 100</span>
                        </span>
                      ) : (
                        <StatusMark status={review.status} />
                      )}
                    </span>
                    <svg className={styles.arrow} width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                      <path d="M3 8h10M9 4l4 4-4 4" />
                    </svg>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <ReviewPreview />
    </div>
  )
}

/** The latest reviews. The list is a shortcut, so a failed request just leaves it out. */
function useRecentReviews(): ReviewSummary[] {
  const [items, setItems] = useState<ReviewSummary[]>([])

  useEffect(() => {
    const controller = new AbortController()
    getReviewHistory(0, RECENT_COUNT, controller.signal)
      .then((page) => setItems(page.items))
      .catch(() => {})
    return () => controller.abort()
  }, [])

  return items
}
