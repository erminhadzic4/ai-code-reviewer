import { createContext, useContext } from 'react'
import type { AuthResponse, User } from '../api/types'

/**
 * Who is signed in. A sign-out caused by a refused token carries the reason `expired`, so
 * the login page can say why it appeared; logging out and a first visit carry none.
 */
export type AuthState =
  | { status: 'loading' }
  | { status: 'signedIn'; user: User }
  | { status: 'signedOut'; reason?: 'expired' }

export interface AuthContextValue {
  state: AuthState
  signIn: (response: AuthResponse) => void
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return value
}
