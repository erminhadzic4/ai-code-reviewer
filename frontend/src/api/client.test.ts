import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readToken, writeToken } from '../auth/tokenStore'
import { fakeBackend, problem } from '../test/api'
import { ApiError, apiRequest, setSessionExpiredHandler } from './client'

/** Answers every fetch with one response that is not JSON, as a proxy would. */
function stubFetch(response: Response) {
  vi.stubGlobal('fetch', vi.fn(async () => response))
}

describe('apiRequest', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setSessionExpiredHandler(() => {})
  })

  it('sends the stored token and returns the parsed body', async () => {
    writeToken('abc')
    const fetchMock = fakeBackend({ 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'a@b.co' } } })

    await expect(apiRequest('/api/auth/me')).resolves.toEqual({ id: 1, email: 'a@b.co' })

    const headers = fetchMock.mock.calls[0][1]!.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer abc')
  })

  it('sends a JSON body without a token when nobody is signed in', async () => {
    const fetchMock = fakeBackend({ 'POST /api/auth/login': { status: 200, body: {} } })

    await apiRequest('/api/auth/login', { method: 'POST', body: { email: 'a@b.co' } })

    const init = fetchMock.mock.calls[0][1]!
    expect(init.body).toBe('{"email":"a@b.co"}')
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('turns a problem detail into an ApiError with the field messages', async () => {
    fakeBackend({
      'POST /api/auth/register': problem(400, 'Validation failed', [
        { field: 'email', message: 'must be a valid email address' },
        { field: 'password', message: 'must be at least 8 characters' },
      ]),
    })

    const error = await apiRequest('/api/auth/register', { method: 'POST', body: {} }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      message: 'Validation failed',
      fieldErrors: { email: 'must be a valid email address', password: 'must be at least 8 characters' },
    })
  })

  it('does not show what a server error says', async () => {
    fakeBackend({ 'GET /api/reviews': problem(500, 'An unexpected error occurred') })

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({
      status: 500,
      message: 'The server ran into a problem. Try again in a moment.',
    })
  })

  it('reports a proxy that cannot reach the backend like a network failure', async () => {
    stubFetch(new Response('<html><head><title>502 Bad Gateway</title></head></html>', {
      status: 502,
      headers: { 'Content-Type': 'text/html' },
    }))

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({
      status: 502,
      message: 'Could not reach the server. Check that the backend is running and try again.',
    })
  })

  it('reports a proxy timeout with an empty body like a network failure', async () => {
    stubFetch(new Response(null, { status: 504 }))

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({
      status: 504,
      message: 'Could not reach the server. Check that the backend is running and try again.',
    })
  })

  it('treats a gateway status with a problem detail as an error of the backend itself', async () => {
    fakeBackend({ 'GET /api/reviews': problem(503, 'GitHub rate limit reached') })

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({
      status: 503,
      message: 'The server ran into a problem. Try again in a moment.',
    })
  })

  it('reports a network failure as status 0', async () => {
    fakeBackend({ 'GET /api/reviews': { networkError: true } })

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({ status: 0 })
  })

  it('signs out when a request with a token is refused', async () => {
    writeToken('expired')
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    fakeBackend({ 'GET /api/reviews': problem(401, 'Authentication required') })

    await expect(apiRequest('/api/reviews')).rejects.toMatchObject({ status: 401 })

    expect(readToken()).toBeNull()
    expect(expired).toHaveBeenCalledOnce()
  })

  it('leaves a 401 without a token to the form that sent it', async () => {
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    fakeBackend({ 'POST /api/auth/login': problem(401, 'Invalid email or password') })

    await expect(apiRequest('/api/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({
      message: 'Invalid email or password',
    })
    expect(expired).not.toHaveBeenCalled()
  })
})
