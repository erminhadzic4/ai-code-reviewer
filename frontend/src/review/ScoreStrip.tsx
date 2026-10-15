import type { CSSProperties } from 'react'
import type { ReviewResult } from '../api/types'
import { Panel } from '../components/Panel'
import styles from './ScoreStrip.module.css'
import { verdict } from './verdict'

const SUB_SCORES = [
  ['security', 'Security'],
  ['maintainability', 'Maintainability'],
  ['performance', 'Performance'],
] as const

/** The overall score with its verdict and the three sub-scores, in one band. */
export function ScoreStrip({ result }: { result: ReviewResult }) {
  const { headline, detail } = verdict(result)
  return (
    <Panel as="section" padding="lg" bodyClassName={styles.strip} aria-label="Scores">
      <div className={styles.overall}>
        <p className={styles.score}>
          <span className={styles.number}>{result.scores.overall}</span>
          <span className={styles.outOf}>/100</span>
          <span className="visually-hidden"> overall score</span>
        </p>
        <div>
          <p className={styles.headline}>{headline}</p>
          <p className={styles.detail}>{detail}</p>
        </div>
      </div>
      <dl className={styles.subScores}>
        {SUB_SCORES.map(([key, label]) => (
          <div key={key} className={styles.subScore}>
            <dt className={styles.subLabel}>{label}</dt>
            <dd className={styles.subValue}>{result.scores[key]}</dd>
            <dd className={styles.track} aria-hidden="true">
              <span className={styles.fill} style={{ '--fraction': result.scores[key] / 100 } as CSSProperties} />
            </dd>
          </div>
        ))}
      </dl>
    </Panel>
  )
}
