import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { currentUser } from '../api/auth'
import { setSessionExpiredHandler } from '../api/client'
import type { AuthResponse } from '../api/types'
import { AuthContext, type AuthState } from './AuthContext'
import { clearToken, readToken, writeToken } from './tokenStore'

/**
 * Knows whether someone is signed in. A stored token is checked against the backend once
 * on load; a later 401 on any request signs the user out, and the protected route then
 * sends them to the login page.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() => (readToken() ? { status: 'loading' } : { status: 'signedOut' }))

  useEffect(() => {
    setSessionExpiredHandler(() => setState({ status: 'signedOut' }))
    return () => setSessionExpiredHandler(() => {})
  }, [])

  useEffect(() => {
    if (state.status !== 'loading') {
      return
    }
    const controller = new AbortController()
    currentUser(controller.signal)
      .then((user) => setState({ status: 'signedIn', user }))
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ status: 'signedOut' })
        }
      })
    return () => controller.abort()
  }, [state.status])

  const signIn = useCallback((response: AuthResponse) => {
    writeToken(response.accessToken)
    setState({ status: 'signedIn', user: response.user })
  }, [])

  const signOut = useCallback(() => {
    clearToken()
    setState({ status: 'signedOut' })
  }, [])

  const value = useMemo(() => ({ state, signIn, signOut }), [state, signIn, signOut])
  return <AuthContext value={value}>{children}</AuthContext>
}
