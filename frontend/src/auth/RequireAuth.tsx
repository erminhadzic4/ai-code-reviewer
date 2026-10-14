import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from './AuthContext'

/** Where the login page sends the user back to, and the repository URL they entered. */
export interface LoginRedirectState {
  from?: string
  repoUrl?: string
}

/** Renders its children for a signed-in user and sends anyone else to the login page. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth()
  const location = useLocation()

  if (state.status === 'loading') {
    return null
  }
  if (state.status === 'signedOut') {
    const redirect: LoginRedirectState = { from: location.pathname + location.search }
    return <Navigate to="/login" replace state={redirect} />
  }
  return children
}
