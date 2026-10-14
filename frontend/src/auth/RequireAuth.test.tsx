import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppHeader } from '../components/AppHeader'
import { fakeBackend, problem } from '../test/api'
import { AuthProvider } from './AuthProvider'
import { RequireAuth } from './RequireAuth'
import { readToken, writeToken } from './tokenStore'

function LoginProbe() {
  const location = useLocation()
  return <p>Login page, back to {(location.state as { from?: string } | null)?.from}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <AppHeader />
        <Routes>
          <Route path="/" element={<p>Home</p>} />
          <Route path="/login" element={<LoginProbe />} />
          <Route
            path="/reviews/:id"
            element={
              <RequireAuth>
                <p>Secret review</p>
              </RequireAuth>
            }
          />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends a visitor to the login page and remembers where they were going', async () => {
    fakeBackend({})

    renderAt('/reviews/42?tab=all')

    expect(await screen.findByText('Login page, back to /reviews/42?tab=all')).toBeInTheDocument()
    expect(screen.queryByText('Secret review')).not.toBeInTheDocument()
  })

  it('shows the page once the stored token is confirmed', async () => {
    writeToken('valid')
    fakeBackend({ 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } })

    renderAt('/reviews/42')

    expect(await screen.findByText('Secret review')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reviews' })).toBeInTheDocument()
  })

  it('treats a token the backend refuses as signed out', async () => {
    writeToken('expired')
    fakeBackend({ 'GET /api/auth/me': problem(401, 'Authentication required') })

    renderAt('/reviews/42')

    expect(await screen.findByText(/Login page/)).toBeInTheDocument()
    expect(readToken()).toBeNull()
  })

  it('logging out forgets the token and returns to the start page', async () => {
    writeToken('valid')
    fakeBackend({ 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } })
    renderAt('/reviews/42')

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }))

    expect(await screen.findByText('Home')).toBeInTheDocument()
    expect(readToken()).toBeNull()
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
  })
})
