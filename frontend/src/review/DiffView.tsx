import type { CSSProperties } from 'react'
import styles from './DiffView.module.css'
import { gutterWidth, type DiffRow } from './diff'

const SIGN: Record<DiffRow['kind'], string> = { context: ' ', marked: '›', removed: '-', added: '+' }
const SPOKEN: Record<DiffRow['kind'], string | null> = {
  context: null,
  marked: 'Flagged line',
  removed: 'Removed',
  added: 'Added',
}

/** Code with two line-number columns, old and new, so a replaced line never shows one number twice. */
export function DiffView({ rows, label }: { rows: DiffRow[]; label: string }) {
  if (rows.length === 0) {
    return null
  }
  const style = { '--gutter': `${gutterWidth(rows)}ch` } as CSSProperties
  return (
    <figure className={styles.diff} style={style} aria-label={label}>
      <div className={styles.scroller}>
        {rows.map((row, i) => (
          <div key={i} className={`${styles.row} ${styles[row.kind]}`}>
            <span className={styles.number} aria-hidden="true">
              {row.oldNumber ?? ''}
            </span>
            <span className={styles.number} aria-hidden="true">
              {row.newNumber ?? ''}
            </span>
            <span className={styles.sign} aria-hidden="true">
              {SIGN[row.kind]}
            </span>
            <code className={styles.code}>
              {SPOKEN[row.kind] && <span className="visually-hidden">{SPOKEN[row.kind]}: </span>}
              {row.text}
            </code>
          </div>
        ))}
      </div>
    </figure>
  )
}
