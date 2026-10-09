import { useEffect, useState } from 'react'
import styles from './App.module.css'

type BackendStatus = 'checking' | 'up' | 'down'

const STATUS_TEXT: Record<BackendStatus, string> = {
  checking: 'Backend: checking',
  up: 'Backend: UP',
  down: 'Backend: DOWN',
}

function App() {
  const [status, setStatus] = useState<BackendStatus>('checking')

  useEffect(() => {
    const controller = new AbortController()

    fetch('/actuator/health', { signal: controller.signal })
      .then((response) => (response.ok ? response.json() : null))
      .then((body) => setStatus(body?.status === 'UP' ? 'up' : 'down'))
      .catch(() => {
        if (!controller.signal.aborted) {
          setStatus('down')
        }
      })

    return () => controller.abort()
  }, [])

  const statusClass = status === 'checking' ? '' : styles[status]

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>AI Code Reviewer</h1>
      <p className={`${styles.status} ${statusClass}`} role="status">
        <span className={styles.dot} aria-hidden="true" />
        {STATUS_TEXT[status]}
      </p>
    </main>
  )
}

export default App
