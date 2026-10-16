import { fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readToken, writeToken } from '../auth/tokenStore'
import { fakeBackend, problem } from '../test/api'
import { renderApp } from '../test/renderApp'

const USER = { id: 7, email: 'ana@example.com' }
const ME = { 'GET /api/auth/me': { status: 200, body: USER } }
const LOGGED_IN = { accessToken: 'new-token', expiresIn: 86400, user: USER }
const EMPTY_PAGE = { items: [], page: 0, size: 20, totalItems: 0 }

function fill(email: string, password: string) {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
}

describe('AuthPage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('marks empty fields without calling the backend', async () => {
    const fetchMock = fakeBackend({})
    renderApp('/login')

    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByText('Email is required.')).toBeInTheDocument()
    expect(screen.getByText('Password is required.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a refused login above the form', async () => {
    fakeBackend({ 'POST /api/auth/login': problem(401, 'Invalid email or password') })
    renderApp('/login')

    fill('ana@example.com', 'wrong-password')
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(readToken()).toBeNull()
  })

  it('shows the backend validation messages next to their fields', async () => {
    fakeBackend({
      'POST /api/auth/register': problem(400, 'Validation failed', [
        { field: 'password', message: 'must be at least 8 characters' },
      ]),
    })
    renderApp('/register')

    fill('ana@example.com', 'short')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('Password must be at least 8 characters.')).toBeInTheDocument()
    expect(screen.queryByText('Validation failed')).not.toBeInTheDocument()
  })

  it('says the session has expired and returns to the same page after logging in', async () => {
    writeToken('valid')
    fakeBackend({
      ...ME,
      'GET /api/reviews?page=0&size=20': [problem(401, 'Authentication required'), { status: 200, body: EMPTY_PAGE }],
      'POST /api/auth/login': { status: 200, body: LOGGED_IN },
    })
    renderApp('/reviews')

    expect(await screen.findByText('Your session has expired. Log in again.')).toHaveAttribute('role', 'status')
    expect(readToken()).toBeNull()

    fill('ana@example.com', 'long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('heading', { name: 'Your reviews' })).toBeInTheDocument()
    expect(screen.queryByText(/Your session has expired/)).not.toBeInTheDocument()
  })

  it('says the session has expired when the stored token is refused on load', async () => {
    writeToken('expired-since-last-visit')
    fakeBackend({ 'GET /api/auth/me': problem(401, 'Authentication required') })
    renderApp('/reviews')

    expect(await screen.findByText('Your session has expired. Log in again.')).toHaveAttribute('role', 'status')
  })

  it('says nothing about a session to a new visitor', async () => {
    fakeBackend({})
    renderApp('/login')

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.queryByText(/Your session has expired/)).not.toBeInTheDocument()
  })

  it('says nothing about a session after logging out', async () => {
    writeToken('valid')
    fakeBackend(ME)
    renderApp('/')

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }))
    fireEvent.click(await screen.findByRole('link', { name: 'Log in' }))

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.queryByText(/Your session has expired/)).not.toBeInTheDocument()
  })

  it('signs in after registering and goes to the start page', async () => {
    fakeBackend({
      'POST /api/auth/register': {
        status: 201,
        body: { accessToken: 'new-token', expiresIn: 86400, user: { id: 7, email: 'ana@example.com' } },
      },
    })
    renderApp('/register')

    fill('ana@example.com', 'long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('heading', { name: /Paste a repo/ })).toBeInTheDocument()
    expect(readToken()).toBe('new-token')
    expect(screen.getByRole('link', { name: 'Reviews' })).toBeInTheDocument()
  })
})
