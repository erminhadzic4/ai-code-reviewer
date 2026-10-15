import type { ReactNode } from 'react'
import styles from './Eyebrow.module.css'

/** The small label above a page title. */
export function Eyebrow({ children }: { children: ReactNode }) {
  return <p className={styles.eyebrow}>{children}</p>
}
