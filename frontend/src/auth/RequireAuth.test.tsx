import { act, fireEvent, render, screen } from '@testing-library/react'
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

  it('keeps the token when the server cannot be reached and shows the page once it answers', async () => {
    writeToken('valid')
    fakeBackend({
      'GET /api/auth/me': [{ networkError: true }, { status: 200, body: { id: 1, email: 'ana@example.com' } }],
    })

    renderAt('/reviews/42')

    expect(await screen.findByRole('heading', { name: 'The server cannot be reached' })).toBeInTheDocument()
    expect(screen.queryByText('Secret review')).not.toBeInTheDocument()
    expect(screen.queryByText(/Login page/)).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
    expect(readToken()).toBe('valid')

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Secret review')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reviews' })).toBeInTheDocument()
  })

  it('treats the proxy answer for a missing backend as unreachable, not signed out', async () => {
    writeToken('valid')
    fakeBackend({ 'GET /api/auth/me': { status: 502 } })

    renderAt('/reviews/42')

    expect(await screen.findByRole('heading', { name: 'The server cannot be reached' })).toBeInTheDocument()
    expect(readToken()).toBe('valid')
  })

  it('still shows a public page when the server cannot be reached', async () => {
    writeToken('valid')
    const fetchMock = fakeBackend({ 'GET /api/auth/me': { networkError: true } })

    renderAt('/')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    // Let the failed check settle.
    await act(async () => {})

    expect(screen.getByText('Home')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'The server cannot be reached' })).not.toBeInTheDocument()
    // Neither signed in nor out: the header offers neither the account links nor "Log in".
    expect(screen.queryByRole('link', { name: 'Log in' })).not.toBeInTheDocument()
    expect(readToken()).toBe('valid')
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
