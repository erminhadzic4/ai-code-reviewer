import { createContext, useContext } from 'react'
import type { AuthResponse, User } from '../api/types'

/**
 * Who is signed in. A sign-out caused by a refused token carries the reason `expired`, so
 * the login page can say why it appeared; logging out and a first visit carry none.
 * `unreachable` means a stored token could not be checked because the server did not
 * answer: the token is kept, and nobody is known to be signed in or out.
 */
export type AuthState =
  | { status: 'loading' }
  | { status: 'signedIn'; user: User }
  | { status: 'signedOut'; reason?: 'expired' }
  | { status: 'unreachable' }

export interface AuthContextValue {
  state: AuthState
  signIn: (response: AuthResponse) => void
  signOut: () => void
  /** Checks the stored token again after the server could not be reached. */
  retry: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return value
}
