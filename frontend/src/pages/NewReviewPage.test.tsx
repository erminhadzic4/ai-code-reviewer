import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewSummary } from '../api/types'
import { writeToken } from '../auth/tokenStore'
import { fakeBackend, problem, type FakeAnswer } from '../test/api'
import { REVIEW_ID, SHA, status } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const ME = { 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } }
const RECENT = 'GET /api/reviews?page=0&size=3'

function summary(overrides: Partial<ReviewSummary>): ReviewSummary {
  return {
    id: 'r1',
    repo: 'octo/demo',
    branch: 'main',
    commitSha: SHA,
    status: 'COMPLETED',
    overallScore: 82,
    createdAt: '2026-10-14T09:00:00Z',
    finishedAt: '2026-10-14T09:02:00Z',
    ...overrides,
  }
}

function page(items: ReviewSummary[]): FakeAnswer {
  return { status: 200, body: { items, page: 0, size: 3, totalItems: items.length } }
}

describe('NewReviewPage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends a visitor to log in', async () => {
    fakeBackend({})
    renderApp('/reviews/new')

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  })

  it('shows the form, the example and the latest reviews, and is not taken for a review id', async () => {
    writeToken('valid')
    const fetchMock = fakeBackend({
      ...ME,
      [RECENT]: page([
        summary({ id: 'r1', repo: 'octo/demo', overallScore: 82 }),
        summary({ id: 'r2', repo: 'octo/queue', status: 'RUNNING', commitSha: null, overallScore: null }),
      ]),
    })
    renderApp('/reviews/new')

    expect(await screen.findByRole('heading', { name: 'Paste a repository.' })).toBeInTheDocument()
    expect(screen.getByLabelText('GitHub repository URL')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Example report' })).toBeInTheDocument()

    const recent = await screen.findByRole('region', { name: 'Recent reviews' })
    expect(within(recent).getByRole('link', { name: /octo\/demo/ })).toHaveAttribute('href', '/reviews/r1')
    expect(within(recent).getByRole('link', { name: /octo\/demo/ })).toHaveTextContent('3f9a1c2')
    expect(within(recent).getByRole('link', { name: /octo\/queue/ })).toHaveTextContent('Running')
    expect(within(recent).getByRole('link', { name: 'All reviews' })).toHaveAttribute('href', '/reviews')

    expect(screen.getByRole('link', { name: 'New review' })).toHaveAttribute('aria-current', 'page')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/reviews/new', expect.anything())
  })

  it('leaves the list out when there are no reviews or they cannot be loaded', async () => {
    writeToken('valid')
    fakeBackend({ ...ME, [RECENT]: page([]) })
    const { unmount } = renderApp('/reviews/new')
    await screen.findByRole('heading', { name: 'Paste a repository.' })
    await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Recent reviews' })).not.toBeInTheDocument())
    unmount()
    vi.unstubAllGlobals()

    const fetchMock = fakeBackend({ ...ME, [RECENT]: problem(500, 'Internal Server Error') })
    renderApp('/reviews/new')
    await screen.findByRole('heading', { name: 'Paste a repository.' })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/reviews?page=0&size=3', expect.anything()))
    expect(screen.queryByRole('region', { name: 'Recent reviews' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('submits the repository and opens the new review', async () => {
    writeToken('valid')
    const fetchMock = fakeBackend({
      ...ME,
      [RECENT]: page([]),
      'POST /api/reviews': { status: 202, body: status() },
      [`GET /api/reviews/${REVIEW_ID}`]: { status: 200, body: status() },
    })
    renderApp('/reviews/new')

    fireEvent.change(await screen.findByLabelText('GitHub repository URL'), {
      target: { value: 'https://github.com/octo/orders-service' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Review repository' }))

    expect(await screen.findByText('Waiting in the queue. Reviews run one at a time.')).toBeInTheDocument()
    const body = JSON.parse(fetchMock.mock.calls.find(([path]) => path === '/api/reviews')![1]!.body as string)
    expect(body).toEqual({ repoUrl: 'https://github.com/octo/orders-service' })
  })

  it('is where the navigation sends a signed-in user for a new review', async () => {
    writeToken('valid')
    fakeBackend({ ...ME, 'GET /api/reviews?page=0&size=20': page([]), [RECENT]: page([]) })
    renderApp('/reviews')

    fireEvent.click(await screen.findByRole('link', { name: 'New review' }))

    expect(await screen.findByRole('heading', { name: 'Paste a repository.' })).toBeInTheDocument()
  })
})
