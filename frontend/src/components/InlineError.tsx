import type { ReactNode } from 'react'
import styles from './InlineError.module.css'

/** A message next to what caused it. Announced as soon as it appears. */
export function InlineError({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <p id={id} className={styles.error} role="alert">
      <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" className={styles.icon}>
        <path d="M8 1.5 15 14H1z" />
        <path d="M8 6v3.5M8 11.5v.5" className={styles.stroke} />
      </svg>
      {children}
    </p>
  )
}
