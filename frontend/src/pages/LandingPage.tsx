import { useLocation } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import type { LoginRedirectState } from '../auth/RequireAuth'
import { Eyebrow } from '../components/Eyebrow'
import { Panel } from '../components/Panel'
import { SeverityMark } from '../components/SeverityMark'
import { buildDiff } from '../review/diff'
import { DiffView } from '../review/DiffView'
import { EXAMPLE_FINDING } from '../review/example'
import { RepoUrlForm } from '../review/RepoUrlForm'
import styles from './LandingPage.module.css'

const STEPS = [
  {
    term: 'Pinned to a line',
    text: 'Every finding names a file and lines that exist in it. Findings that point at nothing are dropped before you see them.',
  },
  {
    term: 'A fix you can copy',
    text: 'Each issue says why it matters and, where it can, suggests replacement code, shown as a diff against your file.',
  },
  {
    term: 'Scores from a formula',
    text: 'Security, maintainability and performance scores are computed from the findings by fixed rules, not guessed by the model.',
  },
]

export function LandingPage() {
  const { state } = useAuth()
  const location = useLocation()
  const returned = location.state as LoginRedirectState | null

  return (
    <>
      <section className={`container ${styles.hero}`} aria-labelledby="hero-title">
        <div className={styles.pitch}>
          <Eyebrow>Public repositories</Eyebrow>
          <h1 id="hero-title" className={styles.title}>
            Paste a repo.
            <br />
            Get the review.
          </h1>
          <p className={styles.lead}>
            Bugs, security holes and code smells, pinned to the exact line, with a fix you can copy.
          </p>
          <RepoUrlForm initialUrl={returned?.repoUrl} autoFocus={Boolean(returned?.repoUrl)} />
          <p className={styles.note}>
            {state.status === 'signedIn'
              ? 'Public GitHub repositories.'
              : 'Public GitHub repositories. Sign in to keep your reviews.'}
          </p>
        </div>

        <Panel as="figure" padding="none" aria-label="Example finding">
          <figcaption className={styles.exampleHead}>
            <span className={styles.exampleTag}>Example</span>
            <span className={styles.exampleLocation}>UserRepository.java:42–43</span>
          </figcaption>
          <div className={styles.exampleBody}>
            <SeverityMark level={EXAMPLE_FINDING.level} />
            <p className={styles.exampleTitle}>{EXAMPLE_FINDING.title}</p>
            <p className={styles.exampleText}>
              Anyone can inject SQL through the username. Use a parameterized query instead.
            </p>
          </div>
          <div className={styles.exampleDiff}>
            <DiffView rows={buildDiff(EXAMPLE_FINDING)} label="Suggested change to UserRepository.java" />
          </div>
        </Panel>
      </section>

      <section className={`container ${styles.how}`} aria-labelledby="how-title">
        <h2 id="how-title" className={styles.howTitle}>
          How it works
        </h2>
        <ol className={styles.steps}>
          {STEPS.map((step, i) => (
            <li key={step.term} className={styles.step}>
              <Panel padding="lg" className={styles.stepFrame} bodyClassName={styles.stepBody}>
                <span className={styles.stepNumber} aria-hidden="true">
                  {String(i + 1).padStart(2, '0')}
                </span>
                <h3 className={styles.stepTerm}>{step.term}</h3>
                <p className={styles.stepText}>{step.text}</p>
              </Panel>
            </li>
          ))}
        </ol>
      </section>

      <footer className={`container ${styles.footer}`}>
        <span>© 2026 Second Pair. Not affiliated with GitHub.</span>
        <a className={styles.footerLink} href="https://github.com/erminhadzic4/ai-code-reviewer">
          Source
        </a>
      </footer>
    </>
  )
}
