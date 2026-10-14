import styles from './Skeleton.module.css'

/** Grey bars in the shape of what is loading, so the page does not jump when it arrives. */
export function Skeleton({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className={styles.skeleton} role="status" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => (
        <span key={i} className={styles.bar} style={{ width: `${92 - ((i * 17) % 40)}%` }} />
      ))}
    </div>
  )
}
