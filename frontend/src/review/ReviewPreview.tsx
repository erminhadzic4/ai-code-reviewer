import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react'
import { SeverityMark } from '../components/SeverityMark'
import { buildDiff } from './diff'
import { EXAMPLE_FINDING, EXAMPLE_SCORES } from './example'
import { lineLabel } from './findings'
import styles from './ReviewPreview.module.css'
import { verdict } from './verdict'

type LayerKey = 'score' | 'finding' | 'diff' | 'count'

/**
 * The layers from back to front. depth is how far a layer stands in front of the score card
 * at rest; spread is how much further it moves out while the pointer is over the preview.
 */
const LAYERS: { key: LayerKey; name: string; depth: number; spread: number }[] = [
  { key: 'score', name: 'Score card', depth: 0, spread: 0 },
  { key: 'finding', name: 'Finding card', depth: 30, spread: 30 },
  { key: 'diff', name: 'Suggested fix', depth: 50, spread: 40 },
  { key: 'count', name: 'Issue count', depth: 70, spread: 50 },
]

const SUB_SCORES = [
  ['security', 'Security'],
  ['maintainability', 'Maintainability'],
  ['performance', 'Performance'],
] as const

const CHANGED_ROWS = buildDiff(EXAMPLE_FINDING).filter((row) => row.kind === 'removed' || row.kind === 'added')

/**
 * An example report as four cards in 3D space. Clicking a card brings it forward and moves the
 * others back. The tilt follows the pointer through two CSS variables written straight to the
 * element: keeping the pointer position in React state would re-render on every move.
 */
export function ReviewPreview() {
  const [focus, setFocus] = useState<LayerKey | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const frame = useRef(0)

  useEffect(() => () => cancelAnimationFrame(frame.current), [])

  function tilt(x: number, y: number) {
    cancelAnimationFrame(frame.current)
    frame.current = requestAnimationFrame(() => {
      const style = rootRef.current?.style
      style?.setProperty('--px', x.toFixed(3))
      style?.setProperty('--py', y.toFixed(3))
    })
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const box = event.currentTarget.getBoundingClientRect()
    if (!box.width || !box.height) return
    tilt((event.clientX - box.left) / box.width - 0.5, (event.clientY - box.top) / box.height - 0.5)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape' && focus) {
      setFocus(null)
    }
  }

  const focused = LAYERS.find((layer) => layer.key === focus)
  const { headline, detail } = verdict(EXAMPLE_SCORES)

  function layer(key: LayerKey, content: ReactNode) {
    const { name, depth, spread } = LAYERS.find((l) => l.key === key)!
    const state = focus === null ? 'rest' : focus === key ? 'focused' : 'behind'
    return (
      <button
        type="button"
        className={`${styles.layer} ${styles[key]}`}
        data-state={state}
        style={{ '--depth': `${depth}px`, '--spread': `${spread}px` } as CSSProperties}
        aria-label={name}
        aria-pressed={focus === key}
        onClick={() => setFocus(focus === key ? null : key)}
      >
        {content}
      </button>
    )
  }

  return (
    <div className={styles.preview} role="group" aria-label="Example report" onKeyDown={onKeyDown}>
      <div
        ref={rootRef}
        className={styles.viewport}
        data-focus={focus ?? undefined}
        onPointerMove={onPointerMove}
        onPointerLeave={() => tilt(0, 0)}
      >
        <div className={styles.stage}>
          {layer(
            'score',
            <span className={styles.panel}>
              <span className={styles.shine} aria-hidden="true" />
              <span className={styles.cardHead}>
                <span className={styles.repo}>example/shop-service</span>
                <span className={styles.tag}>Example</span>
              </span>
              <span className={styles.scoreLine}>
                <span className={styles.scoreNumber}>{EXAMPLE_SCORES.scores.overall}</span>
                <span className={styles.outOf}>/ 100</span>
              </span>
              <span className={styles.verdict}>
                {headline} {detail}
              </span>
              <span className={styles.bars}>
                {SUB_SCORES.map(([key, label]) => (
                  <span key={key} className={styles.bar}>
                    <span className={styles.barLabel}>{label}</span>
                    <span className={styles.track}>
                      <span className={styles.fill} style={{ '--fraction': EXAMPLE_SCORES.scores[key] / 100 } as CSSProperties} />
                    </span>
                    <span className={styles.barValue}>{EXAMPLE_SCORES.scores[key]}</span>
                  </span>
                ))}
              </span>
            </span>,
          )}
          {layer(
            'finding',
            <>
              <SeverityMark level={EXAMPLE_FINDING.level} />
              <span className={styles.findingTitle}>{EXAMPLE_FINDING.title}</span>
              <span className={styles.location}>
                UserRepository.java{lineLabel(EXAMPLE_FINDING)}
              </span>
              <span className={styles.explanation}>{EXAMPLE_FINDING.explanation}</span>
            </>,
          )}
          {layer(
            'diff',
            <>
              {CHANGED_ROWS.map((row, i) => (
                <span key={i} className={`${styles.diffRow} ${styles[row.kind]}`}>
                  <span className={styles.diffNumber}>{row.oldNumber ?? row.newNumber}</span>
                  <span className={styles.diffText}>
                    {row.kind === 'removed' ? '- ' : '+ '}
                    {row.text.trim()}
                  </span>
                </span>
              ))}
            </>,
          )}
          {layer(
            'count',
            <>
              <span className={styles.square} aria-hidden="true" />1 critical issue
            </>,
          )}
        </div>
      </div>

      <div className={styles.footer}>
        <span className={styles.caption} aria-live="polite">
          {focused ? `${focused.name} in focus. Click another layer to switch.` : 'Click a layer to bring it forward.'}
        </span>
        {focused && (
          <button type="button" className={styles.reset} onClick={() => setFocus(null)}>
            Show all
          </button>
        )}
      </div>
    </div>
  )
}
