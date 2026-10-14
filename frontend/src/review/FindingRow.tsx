import { useId, useState } from 'react'
import type { Finding, FindingCategory } from '../api/types'
import { Button } from '../components/Button'
import { SeverityMark } from '../components/SeverityMark'
import { buildDiff } from './diff'
import { DiffView } from './DiffView'
import { lineLabel } from './findings'
import styles from './FindingRow.module.css'

const CATEGORY: Record<FindingCategory, string> = {
  SECURITY: 'Security',
  MAINTAINABILITY: 'Maintainability',
  PERFORMANCE: 'Performance',
}

interface Props {
  finding: Finding
  open: boolean
  onToggle: () => void
}

/** One finding as a row; opening it shows the explanation and the suggested change in place. */
export function FindingRow({ finding, open, onToggle }: Props) {
  const detailsId = useId()
  const location = lineLabel(finding)
  const fileName = finding.filePath?.split('/').pop() ?? ''

  return (
    <li className={`${styles.item} ${open ? styles.open : ''}`}>
      <button type="button" className={styles.row} aria-expanded={open} aria-controls={detailsId} onClick={onToggle}>
        <span className={styles.level}>
          <SeverityMark level={finding.level} />
        </span>
        <span className={styles.title}>{finding.title}</span>
        <span className={styles.location}>{location}</span>
        <span className={styles.category}>{CATEGORY[finding.category]}</span>
        <svg className={styles.chevron} width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M3 4.5 6 7.5 9 4.5" />
        </svg>
      </button>
      {open && (
        <div id={detailsId} className={styles.details}>
          <div className={styles.text}>
            {finding.explanation && (
              <>
                <h4 className={styles.heading}>Why it matters</h4>
                <p>{finding.explanation}</p>
              </>
            )}
            {finding.suggestion && (
              <>
                <h4 className={styles.heading}>Suggested fix</h4>
                <p>{finding.suggestion}</p>
              </>
            )}
            {finding.suggestedCode && <CopyButton code={finding.suggestedCode} />}
          </div>
          <DiffView
            rows={buildDiff(finding)}
            label={`${finding.suggestedCode ? 'Suggested change to' : 'Code in'} ${fileName}${location}`}
          />
        </div>
      )}
    </li>
  )
}

function CopyButton({ code }: { code: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')

  async function copy() {
    try {
      await navigator.clipboard.writeText(code)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  return (
    <div className={styles.copy}>
      <Button variant="primary" onClick={copy}>
        Copy fix
      </Button>
      <span className={styles.copyStatus} aria-live="polite">
        {state === 'copied' && 'Copied to the clipboard.'}
        {state === 'failed' && 'Could not copy. Select the added lines instead.'}
      </span>
    </div>
  )
}
