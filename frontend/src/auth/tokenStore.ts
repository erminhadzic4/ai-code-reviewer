// The access token lives in localStorage (see ARCHITECTURE: no cookies, so no CSRF).
// Storage can be unavailable, for example in a private window; the app then works for
// the current page only.

const KEY = 'secondPair.accessToken'

export function readToken(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function writeToken(token: string): void {
  try {
    localStorage.setItem(KEY, token)
  } catch {
    // Not stored; the user signs in again on the next visit.
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Nothing stored to clear.
  }
}
