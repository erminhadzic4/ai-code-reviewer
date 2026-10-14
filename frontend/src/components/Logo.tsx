import { Link } from 'react-router'
import styles from './Logo.module.css'

/** Two overlapping rings: a second pair of eyes on the code. */
export function Logo() {
  return (
    <Link to="/" className={styles.logo}>
      <svg width="26" height="26" viewBox="0 0 28 28" aria-hidden="true" className={styles.mark}>
        <circle cx="10" cy="14" r="7" className={styles.ringAccent} />
        <circle cx="18" cy="14" r="7" className={styles.ring} />
      </svg>
      Second Pair
    </Link>
  )
}
