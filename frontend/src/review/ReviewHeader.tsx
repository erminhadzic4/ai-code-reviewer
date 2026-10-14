import type { ReactNode } from 'react'
import styles from './ReviewHeader.module.css'

interface Props {
  repo: string
  /** Facts shown in one monospaced line under the name, left out when empty. */
  meta: (string | null | false)[]
  actions?: ReactNode
}

export function ReviewHeader({ repo, meta, actions }: Props) {
  const facts = meta.filter(Boolean) as string[]
  return (
    <div className={styles.header}>
      <div className={styles.name}>
        <h1 className={styles.repo}>{repo}</h1>
        {facts.length > 0 && <p className={styles.meta}>{facts.join(' · ')}</p>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  )
}
