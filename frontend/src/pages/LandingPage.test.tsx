import { fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { writeToken } from '../auth/tokenStore'
import { fakeBackend, problem } from '../test/api'
import { renderApp } from '../test/renderApp'

const ME = { 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } }

function urlField() {
  return screen.getByLabelText('GitHub repository URL')
}

function submit(url: string) {
  fireEvent.change(urlField(), { target: { value: url } })
  fireEvent.click(screen.getByRole('button', { name: 'Review repository' }))
}

describe('LandingPage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('asks for a URL before sending anything', async () => {
    const fetchMock = fakeBackend({})
    renderApp('/')

    submit('   ')

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter the URL of a GitHub repository.')
    expect(urlField()).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends a visitor to log in and brings the URL back afterwards', async () => {
    fakeBackend({
      'POST /api/auth/login': { status: 200, body: { accessToken: 't', expiresIn: 86400, user: { id: 1, email: 'ana@example.com' } } },
    })
    renderApp('/')

    submit('https://github.com/octo/demo')

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.getByText('Sign in to review https://github.com/octo/demo.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'ana@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByDisplayValue('https://github.com/octo/demo')).toBeInTheDocument()
  })

  it('sends a user whose session expired to log in and keeps the URL', async () => {
    writeToken('valid')
    fakeBackend({
      ...ME,
      'POST /api/reviews': problem(401, 'Authentication required'),
      'POST /api/auth/login': { status: 200, body: { accessToken: 't', expiresIn: 86400, user: { id: 1, email: 'ana@example.com' } } },
    })
    renderApp('/')
    await screen.findByRole('button', { name: 'Log out' })

    submit('https://github.com/octo/demo')

    expect(await screen.findByText('Your session has expired. Log in again.')).toHaveAttribute('role', 'status')
    expect(screen.getByText('Sign in to review https://github.com/octo/demo.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'ana@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByDisplayValue('https://github.com/octo/demo')).toBeInTheDocument()
  })

  it('shows why the backend refused the URL next to the field', async () => {
    writeToken('valid')
    fakeBackend({
      ...ME,
      'POST /api/reviews': problem(400, 'Enter a GitHub repository URL in the form https://github.com/owner/repository'),
    })
    renderApp('/')
    await screen.findByRole('link', { name: 'Reviews' })

    submit('gitlab.com/octo/demo')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter a GitHub repository URL in the form https://github.com/owner/repository',
    )
  })

  it('opens the new review once it is submitted', async () => {
    writeToken('valid')
    const fetchMock = fakeBackend({
      ...ME,
      'POST /api/reviews': { status: 202, body: { id: 'abc-123', status: 'QUEUED' } },
    })
    renderApp('/')
    await screen.findByRole('link', { name: 'Reviews' })

    submit('https://github.com/octo/demo')

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/reviews', expect.objectContaining({ method: 'POST' })))
    const body = JSON.parse(fetchMock.mock.calls.find(([path]) => path === '/api/reviews')![1]!.body as string)
    expect(body).toEqual({ repoUrl: 'https://github.com/octo/demo' })
  })
})
