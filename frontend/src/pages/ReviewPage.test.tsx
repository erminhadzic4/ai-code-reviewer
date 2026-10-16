import { act, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { writeToken } from '../auth/tokenStore'
import { fakeBackend, problem, type FakeAnswer } from '../test/api'
import { REVIEW_ID, SHA, result, status } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const ME = { 'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } } }
const STATUS_PATH = `GET /api/reviews/${REVIEW_ID}`

function backend(statuses: FakeAnswer[], extra: Record<string, FakeAnswer | FakeAnswer[]> = {}) {
  return fakeBackend({ ...ME, [STATUS_PATH]: statuses, ...extra })
}

/** Lets the next poll happen and its answer arrive. */
async function nextPoll() {
  await act(() => vi.advanceTimersByTimeAsync(2000))
}

function statusCalls(fetchMock: ReturnType<typeof fakeBackend>) {
  return fetchMock.mock.calls.filter(([path]) => path === `/api/reviews/${REVIEW_ID}`).length
}

describe('ReviewPage', () => {
  beforeEach(() => {
    localStorage.clear()
    writeToken('valid')
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('follows a review from the queue through each stage and stops asking once it is done', async () => {
    const fetchMock = backend([
      { status: 200, body: status() },
      { status: 200, body: status({ status: 'RUNNING', stage: 'FETCHING', attempt: 1 }) },
      {
        status: 200,
        body: status({ status: 'RUNNING', stage: 'ANALYZING', attempt: 1, branch: 'main', commitSha: SHA, chunksDone: 2, chunksTotal: 5 }),
      },
      { status: 200, body: status({ status: 'RUNNING', stage: 'SCORING', attempt: 1, branch: 'main', commitSha: SHA }) },
      { status: 200, body: status({ status: 'COMPLETED', attempt: 1, branch: 'main', commitSha: SHA }) },
    ], { [`GET /api/reviews/${REVIEW_ID}/result`]: { status: 200, body: result() } })
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText('Waiting in the queue. Reviews run one at a time.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'octo/orders-service' })).toBeInTheDocument()

    await nextPoll()
    expect(screen.getByText('Downloading the repository from GitHub.')).toBeInTheDocument()

    await nextPoll()
    expect(screen.getByText('Reviewing part 3 of 5.')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Parts reviewed' })).toHaveAttribute('aria-valuenow', '2')
    expect(screen.getByText('main · 3f9a1c2')).toBeInTheDocument()
    expect(screen.getByText('Analysing').closest('li')).toHaveAttribute('aria-current', 'step')

    await nextPoll()
    expect(screen.getByText('Computing the scores from the findings.')).toBeInTheDocument()

    await nextPoll()
    expect(screen.queryByText(/Computing the scores/)).not.toBeInTheDocument()
    expect(await screen.findByText('Solid, with things to fix.')).toBeInTheDocument()

    const calls = statusCalls(fetchMock)
    await nextPoll()
    await nextPoll()
    expect(statusCalls(fetchMock)).toBe(calls)
  })

  it('says why a queued review waits and when it is tried again', async () => {
    backend([
      {
        status: 200,
        body: status({
          attempt: 1,
          retryAt: '2026-10-14T09:05:00Z',
          error: { code: 'GITHUB_UNAVAILABLE', message: 'GitHub is not reachable right now' },
        }),
      },
    ])
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText(/Attempt 1 of 3 did not finish\. The next attempt starts at/)).toBeInTheDocument()
    expect(screen.getByText('GitHub is not reachable right now')).toBeInTheDocument()
    expect(screen.getByText('GITHUB_UNAVAILABLE')).toBeInTheDocument()
  })

  it('shows a failure with its message and offers to try again when that can help', async () => {
    const failed = status({
      status: 'FAILED',
      attempt: 3,
      error: { code: 'PROVIDER_UNAVAILABLE', message: 'The model did not answer' },
    })
    const fetchMock = backend([{ status: 200, body: failed }], {
      'POST /api/reviews': { status: 202, body: status({ id: 'second-review' }) },
      'GET /api/reviews/second-review': { status: 200, body: status({ id: 'second-review' }) },
    })
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByRole('heading', { name: 'The review failed' })).toBeInTheDocument()
    expect(screen.getByText('The model did not answer')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review another repository' })).toHaveAttribute('href', '/reviews/new')

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Waiting in the queue. Reviews run one at a time.')).toBeInTheDocument()
    const submitted = fetchMock.mock.calls.find(([path, init]) => path === '/api/reviews' && init?.method === 'POST')
    expect(JSON.parse(submitted![1]!.body as string)).toEqual({ repoUrl: 'https://github.com/octo/orders-service' })
  })

  it('does not offer to repeat a review of a repository that does not exist', async () => {
    backend([
      {
        status: 200,
        body: status({ status: 'FAILED', attempt: 1, error: { code: 'REPO_NOT_FOUND', message: 'Repository not found or private' } }),
      },
    ])
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText('Repository not found or private')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review another repository' })).toHaveAttribute('href', '/reviews/new')
  })

  it('treats a review of another account like one that does not exist', async () => {
    const fetchMock = backend([problem(404, 'Review not found')])
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByRole('heading', { name: 'Review not found' })).toBeInTheDocument()
    await nextPoll()
    expect(statusCalls(fetchMock)).toBe(1)
  })

  it('keeps asking after a network failure', async () => {
    backend([{ networkError: true }, { status: 200, body: status() }])
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText(/Could not reach the server/)).toBeInTheDocument()
    await nextPoll()
    expect(screen.getByText('Waiting in the queue. Reviews run one at a time.')).toBeInTheDocument()
  })

  it('keeps asking while the proxy cannot reach the backend', async () => {
    // No body: what a proxy answers while the backend is down or starting.
    const fetchMock = backend([{ status: 502 }, { status: 200, body: status() }])
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText(/Could not reach the server/)).toBeInTheDocument()
    await nextPoll()
    expect(screen.getByText('Waiting in the queue. Reviews run one at a time.')).toBeInTheDocument()
    expect(statusCalls(fetchMock)).toBe(2)
  })
})
