import { createContext, useContext } from 'react'
import type { AuthResponse, User } from '../api/types'

export type AuthState = { status: 'loading' } | { status: 'signedIn'; user: User } | { status: 'signedOut' }

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
