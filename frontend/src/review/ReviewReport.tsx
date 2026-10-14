import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { getReviewResult } from '../api/reviews'
import type { ReviewResult, ReviewStatusResponse } from '../api/types'
import { Button } from '../components/Button'
import { InlineError } from '../components/InlineError'
import { Skeleton } from '../components/Skeleton'
import { applyFilter, groupByFile, type FileGroup, type Filter } from './findings'
import { FindingRow } from './FindingRow'
import { formatAgo, plural, shortSha } from './format'
import styles from './ReviewReport.module.css'
import { ReviewHeader } from './ReviewHeader'
import { ScoreStrip } from './ScoreStrip'
import { useResubmit } from './useResubmit'

/** Rows rendered at first; a very long report shows the rest on request. */
export const RENDER_LIMIT = 100

const FILTERS: { value: Filter; label: string; count: (r: ReviewResult) => number }[] = [
  { value: 'ALL', label: 'All', count: (r) => r.findings.length },
  { value: 'CRITICAL', label: 'Critical', count: (r) => r.counts.critical },
  { value: 'WARNING', label: 'Warnings', count: (r) => r.counts.warnings },
  { value: 'SMELL', label: 'Smells', count: (r) => r.counts.codeSmells },
]

const EMPTY_FILTER: Record<Filter, string> = {
  ALL: '',
  CRITICAL: 'No critical issues.',
  WARNING: 'No warnings.',
  SMELL: 'No code smells.',
}

export function ReviewReport({ review }: { review: ReviewStatusResponse }) {
  const [result, setResult] = useState<ReviewResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const { resubmit, busy, error: resubmitError } = useResubmit(review.repo)

  useEffect(() => {
    const controller = new AbortController()
    setError(null)
    getReviewResult(review.id, controller.signal)
      .then(setResult)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : 'The report could not be loaded.')
        }
      })
    return () => controller.abort()
  }, [review.id, attempt])

  const meta = [
    review.branch,
    review.commitSha && shortSha(review.commitSha),
    result && plural(result.stats.filesAnalyzed, 'file'),
    result && plural(result.stats.linesAnalyzed, 'line'),
    review.finishedAt && `finished ${formatAgo(review.finishedAt)}`,
    review.cacheHit && 'reused from an earlier review of this commit',
  ]

  return (
    <>
      <ReviewHeader
        repo={review.repo}
        meta={meta}
        actions={
          <Button variant="secondary" busy={busy} onClick={resubmit}>
            {busy ? 'Submitting…' : 'Review again'}
          </Button>
        }
      />
      {resubmitError && <InlineError>{resubmitError}</InlineError>}
      {error && (
        <div className={styles.loadError}>
          <InlineError>{error}</InlineError>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </Button>
        </div>
      )}
      {!result && !error && <Skeleton rows={8} label="Loading the report" />}
      {result && <Report result={result} />}
    </>
  )
}

function Report({ result }: { result: ReviewResult }) {
  const [filter, setFilter] = useState<Filter>('ALL')
  const [openIds, setOpenIds] = useState<Set<number>>(() => {
    const firstCritical = result.findings.find((finding) => finding.level === 'CRITICAL')
    return new Set(firstCritical ? [firstCritical.id] : [])
  })
  const [showAll, setShowAll] = useState(false)

  const groups = useMemo(() => groupByFile(applyFilter(result.findings, filter)), [result, filter])
  const visible = useMemo(() => limitGroups(groups, showAll ? Infinity : RENDER_LIMIT), [groups, showAll])
  const shown = visible.reduce((sum, group) => sum + group.findings.length, 0)
  const total = groups.reduce((sum, group) => sum + group.findings.length, 0)
  const { stats } = result

  function toggle(id: number) {
    setOpenIds((previous) => {
      const next = new Set(previous)
      if (!next.delete(id)) next.add(id)
      return next
    })
  }

  return (
    <div className={styles.report}>
      <ScoreStrip result={result} />

      {(stats.filesFailed > 0 || stats.findingsDiscarded > 0) && (
        <p className={styles.notice} role="note">
          {stats.filesFailed > 0 && (
            <>
              <strong>Partial report:</strong> {plural(stats.filesFailed, 'file')} could not be reviewed and{' '}
              {stats.filesFailed === 1 ? 'is' : 'are'} not in the scores.{' '}
            </>
          )}
          {stats.findingsDiscarded > 0 &&
            `${plural(stats.findingsDiscarded, 'finding')} pointed at code that does not exist and ${
              stats.findingsDiscarded === 1 ? 'was' : 'were'
            } left out.`}
        </p>
      )}

      <section className={styles.findings} aria-labelledby="findings-title">
        <div className={styles.toolbar}>
          <h2 id="findings-title" className={styles.sectionTitle}>
            Findings
          </h2>
          {result.findings.length > 0 && (
            <div className={styles.filters} role="group" aria-label="Show findings">
              {FILTERS.map(({ value, label, count }) => (
                <button
                  key={value}
                  type="button"
                  className={styles.filter}
                  aria-pressed={filter === value}
                  onClick={() => {
                    setFilter(value)
                    setShowAll(false)
                  }}
                >
                  {label}{' '}
                  <span className={styles.filterCount}>{count(result)}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {result.findings.length === 0 ? (
          <p className={styles.empty}>No issues found in {plural(stats.filesAnalyzed, 'file')}.</p>
        ) : total === 0 ? (
          <p className={styles.empty}>{EMPTY_FILTER[filter]}</p>
        ) : (
          <div className={styles.layout}>
            <nav className={styles.fileNav} aria-label="Files">
              <ul className={styles.fileNavList}>
                {visible.map((group, i) => (
                  <li key={group.filePath ?? '(repository)'}>
                    <a className={styles.fileNavLink} href={`#file-${i}`} title={group.filePath ?? undefined}>
                      <span className={styles.fileNavName}>{group.filePath ? baseName(group.filePath) : 'Repository-wide'}</span>
                      <span className={styles.fileNavCount}>{group.findings.length}</span>
                    </a>
                  </li>
                ))}
              </ul>
            </nav>

            <div className={styles.groups}>
              {visible.map((group, i) => (
                <section key={group.filePath ?? '(repository)'} id={`file-${i}`} className={styles.group} aria-labelledby={`file-${i}-title`}>
                  <h3 id={`file-${i}-title`} className={styles.groupTitle}>
                    <span className={styles.path}>{group.filePath ?? 'Repository-wide'}</span>
                    <span className={styles.groupCount}>{plural(group.findings.length, 'finding')}</span>
                  </h3>
                  <ul className={styles.rows}>
                    {group.findings.map((finding) => (
                      <FindingRow key={finding.id} finding={finding} open={openIds.has(finding.id)} onToggle={() => toggle(finding.id)} />
                    ))}
                  </ul>
                </section>
              ))}
              {shown < total && (
                <Button variant="secondary" className={styles.showAll} onClick={() => setShowAll(true)}>
                  Show all {total} findings
                </Button>
              )}
            </div>
          </div>
        )}
      </section>

      {result.goodPractices.length > 0 && (
        <section className={styles.good} aria-labelledby="good-title">
          <h2 id="good-title" className={styles.sectionTitle}>
            What is working
          </h2>
          <ul className={styles.goodList}>
            {result.goodPractices.map((practice) => (
              <li key={practice.id} className={styles.goodItem}>
                <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true" className={styles.check}>
                  <path d="M5 13l4 4L19 7" />
                </svg>
                <span>
                  <span className={styles.goodTitle}>{practice.title}</span>
                  {practice.explanation && <span className={styles.goodText}> {practice.explanation}</span>}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function baseName(path: string): string {
  return path.split('/').pop() ?? path
}

/** The first `limit` findings, keeping whole groups up to the one the limit falls in. */
function limitGroups(groups: FileGroup[], limit: number): FileGroup[] {
  const kept: FileGroup[] = []
  let left = limit
  for (const group of groups) {
    if (left <= 0) break
    kept.push(left >= group.findings.length ? group : { ...group, findings: group.findings.slice(0, left) })
    left -= group.findings.length
  }
  return kept
}
