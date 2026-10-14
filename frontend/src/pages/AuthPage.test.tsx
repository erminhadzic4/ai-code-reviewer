import { fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readToken } from '../auth/tokenStore'
import { fakeBackend, problem } from '../test/api'
import { renderApp } from '../test/renderApp'

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
