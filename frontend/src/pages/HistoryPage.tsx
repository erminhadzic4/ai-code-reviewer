import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { getReviewHistory } from '../api/reviews'
import type { Page, ReviewSummary } from '../api/types'
import { Button } from '../components/Button'
import { InlineError } from '../components/InlineError'
import { Panel } from '../components/Panel'
import { Skeleton } from '../components/Skeleton'
import { formatAgo, shortSha } from '../review/format'
import { RepoUrlForm } from '../review/RepoUrlForm'
import { StatusMark } from '../review/StatusMark'
import styles from './HistoryPage.module.css'

export const PAGE_SIZE = 20

/** The user's reviews, newest first. The page number in the address starts at 1. */
export function HistoryPage() {
  const [params] = useSearchParams()
  const pageNumber = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  const [attempt, setAttempt] = useState(0)
  // What was loaded, and for which page and attempt: a stale answer counts as loading.
  const key = `${pageNumber}:${attempt}`
  const [loaded, setLoaded] = useState<{ key: string; page: Page<ReviewSummary> | null; error: string | null }>({
    key: '',
    page: null,
    error: null,
  })
  const page = loaded.key === key ? loaded.page : null
  const error = loaded.key === key ? loaded.error : null

  useEffect(() => {
    const controller = new AbortController()
    const requested = `${pageNumber}:${attempt}`
    getReviewHistory(pageNumber - 1, PAGE_SIZE, controller.signal)
      .then((result) => setLoaded({ key: requested, page: result, error: null }))
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          const message = e instanceof ApiError ? e.message : 'Your reviews could not be loaded.'
          setLoaded({ key: requested, page: null, error: message })
        }
      })
    return () => controller.abort()
  }, [pageNumber, attempt])

  const pages = page ? Math.max(1, Math.ceil(page.totalItems / page.size)) : 1

  return (
    <div className={`container ${styles.page}`}>
      <div className={styles.head}>
        <h1 className={styles.title}>Your reviews</h1>
        {page && page.totalItems > 0 && (
          <p className={styles.count}>{page.totalItems === 1 ? '1 review' : `${page.totalItems} reviews`}</p>
        )}
      </div>

      {error && (
        <div className={styles.error}>
          <InlineError>{error}</InlineError>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </Button>
        </div>
      )}
      {!page && !error && <Skeleton rows={6} label="Loading your reviews" />}

      {page && page.totalItems === 0 && (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>No reviews yet.</p>
          <p className={styles.emptyText}>Paste the URL of a public GitHub repository to start the first one.</p>
          <RepoUrlForm />
        </div>
      )}

      {page && page.items.length > 0 && (
        <>
          <Panel padding="none">
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">Repository</th>
                    <th scope="col">Commit</th>
                    <th scope="col">Status</th>
                    <th scope="col" className={styles.number}>
                      Score
                    </th>
                    <th scope="col">Submitted</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((review) => (
                    <tr key={review.id}>
                      <td>
                        <Link to={`/reviews/${review.id}`} className={styles.repo}>
                          {review.repo}
                        </Link>
                      </td>
                      <td className={styles.mono}>{review.commitSha ? shortSha(review.commitSha) : '–'}</td>
                      <td>
                        <StatusMark status={review.status} />
                      </td>
                      <td className={`${styles.number} ${styles.mono}`}>{review.overallScore ?? '–'}</td>
                      <td className={styles.muted}>
                        <time dateTime={review.createdAt}>{formatAgo(review.createdAt)}</time>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>

          {pages > 1 && (
            <nav className={styles.pager} aria-label="Pages">
              {pageNumber > 1 ? (
                <Link to={`/reviews?page=${pageNumber - 1}`} className={styles.pagerLink}>
                  Newer
                </Link>
              ) : (
                <span />
              )}
              <span className={styles.muted}>
                Page {pageNumber} of {pages}
              </span>
              {pageNumber < pages ? (
                <Link to={`/reviews?page=${pageNumber + 1}`} className={styles.pagerLink}>
                  Older
                </Link>
              ) : (
                <span />
              )}
            </nav>
          )}
        </>
      )}

      {page && page.totalItems > 0 && page.items.length === 0 && (
        <p className={styles.muted}>
          This page is empty. <Link to="/reviews">Go to the newest reviews</Link>
        </p>
      )}
    </div>
  )
}
