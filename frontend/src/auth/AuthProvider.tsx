import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { currentUser } from '../api/auth'
import { ApiError, setSessionExpiredHandler } from '../api/client'
import type { AuthResponse } from '../api/types'
import { AuthContext, type AuthState } from './AuthContext'
import { clearToken, readToken, writeToken } from './tokenStore'

const EXPIRED: AuthState = { status: 'signedOut', reason: 'expired' }
const UNREACHABLE: AuthState = { status: 'unreachable' }

/** What a failed check of the stored token means: only a refusal signs the user out. */
function afterFailedCheck(error: unknown): AuthState {
  if (error instanceof ApiError && error.status === 401) {
    // The token has expired since the last visit.
    return EXPIRED
  }
  if (error instanceof ApiError && (error.status === 0 || error.status >= 500)) {
    // No answer, or the proxy's answer for a missing backend: the token may still be good.
    return UNREACHABLE
  }
  return { status: 'signedOut' }
}

/**
 * Knows whether someone is signed in. A stored token is checked against the backend once
 * on load; a later 401 on any request signs the user out as expired, and the protected
 * route then sends them to the login page. If the check gets no answer, the token is kept
 * and the user can ask again.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(() => (readToken() ? { status: 'loading' } : { status: 'signedOut' }))

  useEffect(() => {
    setSessionExpiredHandler(() => setState(EXPIRED))
    return () => setSessionExpiredHandler(() => {})
  }, [])

  useEffect(() => {
    if (state.status !== 'loading') {
      return
    }
    const controller = new AbortController()
    currentUser(controller.signal)
      .then((user) => setState({ status: 'signedIn', user }))
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setState(afterFailedCheck(e))
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

  // Loading again runs the check above once more with the token that was kept.
  const retry = useCallback(() => {
    setState((previous) => (previous.status === 'unreachable' ? { status: 'loading' } : previous))
  }, [])

  const value = useMemo(() => ({ state, signIn, signOut, retry }), [state, signIn, signOut, retry])
  return <AuthContext value={value}>{children}</AuthContext>
}
