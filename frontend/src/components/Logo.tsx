import { Link } from 'react-router'
import styles from './Logo.module.css'

/** Two overlapping rings: a second pair of eyes on the code. */
export function Logo() {
  return (
    <Link to="/" className={styles.logo}>
      <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true" className={styles.mark}>
        <circle cx="9" cy="12" r="6.5" className={styles.ringFront} />
        <circle cx="15" cy="12" r="6.5" className={styles.ringBack} />
      </svg>
      Second Pair
    </Link>
  )
}
