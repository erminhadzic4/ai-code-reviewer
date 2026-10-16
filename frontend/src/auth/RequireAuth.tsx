import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { Button } from '../components/Button'
import { Eyebrow } from '../components/Eyebrow'
import { useAuth } from './AuthContext'
import styles from './RequireAuth.module.css'

/** Where the login page sends the user back to, and the repository URL they entered. */
export interface LoginRedirectState {
  from?: string
  repoUrl?: string
}

/**
 * Renders its children for a signed-in user and sends anyone else to the login page. When
 * the server could not be asked, the user is neither, so the page offers to ask again.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state, retry } = useAuth()
  const location = useLocation()

  if (state.status === 'loading') {
    return null
  }
  if (state.status === 'unreachable') {
    return (
      <div className={`container ${styles.page}`}>
        <Eyebrow>Connection</Eyebrow>
        <h1 className={styles.title}>The server cannot be reached</h1>
        <p className={styles.text}>Check that the backend is running, then try again.</p>
        <Button variant="primary" size="lg" onClick={retry}>
          Try again
        </Button>
      </div>
    )
  }
  if (state.status === 'signedOut') {
    const redirect: LoginRedirectState = { from: location.pathname + location.search }
    return <Navigate to="/login" replace state={redirect} />
  }
  return children
}
