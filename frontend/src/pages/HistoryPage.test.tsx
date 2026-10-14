import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewSummary } from '../api/types'
import { writeToken } from '../auth/tokenStore'
import { fakeBackend, type FakeAnswer } from '../test/api'
import { SHA } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const ME = { 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } }

function summary(overrides: Partial<ReviewSummary>): ReviewSummary {
  return {
    id: 'r1',
    repo: 'octo/demo',
    branch: null,
    commitSha: null,
    status: 'QUEUED',
    overallScore: null,
    createdAt: new Date().toISOString(),
    finishedAt: null,
    ...overrides,
  }
}

function page(items: ReviewSummary[], number: number, totalItems = items.length): FakeAnswer {
  return { status: 200, body: { items, page: number, size: 20, totalItems } }
}

describe('HistoryPage', () => {
  beforeEach(() => {
    localStorage.clear()
    writeToken('valid')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists the reviews with their status and score', async () => {
    fakeBackend({
      ...ME,
      'GET /api/reviews?page=0&size=20': page(
        [
          summary({ id: 'r2', repo: 'octo/api', status: 'RUNNING' }),
          summary({ id: 'r1', repo: 'octo/demo', status: 'COMPLETED', commitSha: SHA, overallScore: 81 }),
        ],
        0,
      ),
    })
    renderApp('/reviews')

    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(3)
    const done = within(rows[2])
    expect(done.getByRole('link', { name: 'octo/demo' })).toHaveAttribute('href', '/reviews/r1')
    expect(done.getByText('3f9a1c2')).toBeInTheDocument()
    expect(done.getByText('Completed')).toBeInTheDocument()
    expect(done.getByText('81')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Running')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reviews' })).toHaveAttribute('aria-current', 'page')
  })

  it('offers the form when there are no reviews yet', async () => {
    fakeBackend({ ...ME, 'GET /api/reviews?page=0&size=20': page([], 0) })
    renderApp('/reviews')

    expect(await screen.findByText('No reviews yet.')).toBeInTheDocument()
    expect(screen.getByLabelText('GitHub repository URL')).toBeInTheDocument()
  })

  it('moves between pages', async () => {
    const fetchMock = fakeBackend({
      ...ME,
      'GET /api/reviews?page=0&size=20': page([summary({ id: 'new', repo: 'octo/new' })], 0, 21),
      'GET /api/reviews?page=1&size=20': page([summary({ id: 'old', repo: 'octo/old' })], 1, 21),
    })
    renderApp('/reviews')

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Newer' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Older' }))

    expect(await screen.findByRole('link', { name: 'octo/old' })).toBeInTheDocument()
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Newer' })).toHaveAttribute('href', '/reviews?page=1')
    expect(fetchMock).toHaveBeenCalledWith('/api/reviews?page=1&size=20', expect.anything())
  })
})

describe('NotFoundPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('answers an unknown address', async () => {
    localStorage.clear()
    fakeBackend({})
    renderApp('/no/such/page')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the start page' })).toHaveAttribute('href', '/')
  })
})
