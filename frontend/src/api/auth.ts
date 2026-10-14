import { apiRequest } from './client'
import type { AuthResponse, User } from './types'

export interface Credentials {
  email: string
  password: string
}

export function login(credentials: Credentials): Promise<AuthResponse> {
  return apiRequest('/api/auth/login', { method: 'POST', body: credentials })
}

export function register(credentials: Credentials): Promise<AuthResponse> {
  return apiRequest('/api/auth/register', { method: 'POST', body: credentials })
}

export function currentUser(signal?: AbortSignal): Promise<User> {
  return apiRequest('/api/auth/me', { signal })
}
