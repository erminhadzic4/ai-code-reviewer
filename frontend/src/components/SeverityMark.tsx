import type { FindingLevel } from '../api/types'
import styles from './SeverityMark.module.css'

const LEVEL_LABEL: Record<FindingLevel, string> = {
  CRITICAL: 'Critical',
  WARNING: 'Warning',
  SMELL: 'Smell',
  GOOD_PRACTICE: 'Good practice',
}

/** A small square in the level's colour and the level's name: colour is never the only cue. */
export function SeverityMark({ level }: { level: FindingLevel }) {
  return (
    <span className={`${styles.mark} ${styles[level]}`}>
      <span className={styles.square} aria-hidden="true" />
      {LEVEL_LABEL[level]}
    </span>
  )
}
