import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'

function mockHealthResponse(response: Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(() => response))
}

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows that the backend is up when the health endpoint reports UP', async () => {
    mockHealthResponse(Promise.resolve(Response.json({ status: 'UP' })))

    render(<App />)

    expect(await screen.findByText('Backend: UP')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/actuator/health', expect.anything())
  })

  it('shows that the backend is down when the request fails', async () => {
    mockHealthResponse(Promise.reject(new TypeError('Failed to fetch')))

    render(<App />)

    expect(await screen.findByText('Backend: DOWN')).toBeInTheDocument()
  })
})
