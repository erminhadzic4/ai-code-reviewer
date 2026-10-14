import { useLocation } from 'react-router'
import type { Finding } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import type { LoginRedirectState } from '../auth/RequireAuth'
import { SeverityMark } from '../components/SeverityMark'
import { buildDiff } from '../review/diff'
import { DiffView } from '../review/DiffView'
import { RepoUrlForm } from '../review/RepoUrlForm'
import styles from './LandingPage.module.css'

// A finding in the shape the backend returns, shown as an example of a report.
const EXAMPLE: Finding = {
  id: 0,
  level: 'CRITICAL',
  category: 'SECURITY',
  severity: 'CRITICAL',
  filePath: 'src/main/java/shop/UserRepository.java',
  lineStart: 42,
  lineEnd: 43,
  title: 'SQL query built from user input',
  explanation: 'The username goes straight into the query string.',
  suggestion: 'Use a parameterized query.',
  suggestedCode: '    String q = "SELECT * FROM users WHERE name = ?";\n    return jdbc.queryForObject(q, mapper, username);',
  codeSnippet: [
    '  public User find(String username) {',
    '    String q = "SELECT * FROM users WHERE name=\'" + username + "\'";',
    '    return jdbc.queryForObject(q, mapper);',
    '  }',
  ].join('\n'),
  snippetStartLine: 41,
}

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

        <figure className={styles.example} aria-label="Example finding">
          <figcaption className={styles.exampleHead}>
            <span className={styles.exampleTag}>Example</span>
            <SeverityMark level={EXAMPLE.level} />
            <span className={styles.exampleLocation}>UserRepository.java:42–43</span>
          </figcaption>
          <p className={styles.exampleTitle}>{EXAMPLE.title}</p>
          <p className={styles.exampleText}>
            Anyone can inject SQL through the username. Use a parameterized query instead.
          </p>
          <DiffView rows={buildDiff(EXAMPLE)} label="Suggested change to UserRepository.java" />
        </figure>
      </section>

      <section className={`container ${styles.how}`} aria-labelledby="how-title">
        <h2 id="how-title" className={styles.howTitle}>
          How it works
        </h2>
        <dl className={styles.steps}>
          {STEPS.map((step) => (
            <div key={step.term} className={styles.step}>
              <dt className={styles.stepTerm}>{step.term}</dt>
              <dd className={styles.stepText}>{step.text}</dd>
            </div>
          ))}
        </dl>
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
