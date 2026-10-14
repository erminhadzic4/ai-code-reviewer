import { vi } from 'vitest'

/** Answers a fetch call: JSON with a status, or a thrown network error. */
export type FakeAnswer = { status: number; body?: unknown } | { networkError: true }

/**
 * Replaces fetch with a function that answers by method and path. A route is either one
 * answer or a list that is used in turn, the last one repeating.
 */
export function fakeBackend(routes: Record<string, FakeAnswer | FakeAnswer[]>) {
  const used: Record<string, number> = {}
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`
    const route = routes[key]
    if (!route) {
      throw new Error(`No fake answer for ${key}`)
    }
    const answers = Array.isArray(route) ? route : [route]
    const index = Math.min(used[key] ?? 0, answers.length - 1)
    used[key] = (used[key] ?? 0) + 1
    const answer = answers[index]
    if ('networkError' in answer) {
      throw new TypeError('Failed to fetch')
    }
    const json = answer.body === undefined ? '' : JSON.stringify(answer.body)
    return new Response(json, {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function problem(status: number, detail: string, errors?: { field: string; message: string }[]): FakeAnswer {
  return { status, body: { type: 'about:blank', status, detail, ...(errors ? { errors } : {}) } }
}
