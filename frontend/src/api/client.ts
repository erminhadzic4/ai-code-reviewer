import { clearToken, readToken } from '../auth/tokenStore'

/**
 * An error answer of the backend. The backend sends RFC 9457 problem details; a
 * validation error also lists the fields that failed.
 */
export class ApiError extends Error {
  readonly status: number
  /** Messages by field name, for a validation error. */
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

const NETWORK_MESSAGE = 'Could not reach the server. Check that the backend is running and try again.'

let onSessionExpired: () => void = () => {}

/** Called when a request that carried a token is refused with 401. */
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  signal?: AbortSignal
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = readToken()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal?.aborted) {
      throw error
    }
    throw new ApiError(0, NETWORK_MESSAGE)
  }

  if (response.ok) {
    return (await response.json()) as T
  }

  // A token that is refused has expired or lost its user; a 401 without one is a wrong
  // password and belongs to the form that sent it.
  if (response.status === 401 && token) {
    clearToken()
    onSessionExpired()
  }
  throw await toApiError(response)
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    // Not JSON, for example a proxy error page.
  }
  if (response.status >= 500) {
    // The backend keeps internals out of a 500, and a proxy's 502 has nothing to show.
    return new ApiError(response.status, 'The server ran into a problem. Try again in a moment.')
  }
  if (!isProblem(body)) {
    return new ApiError(response.status, `The request failed (${response.status}).`)
  }
  const fieldErrors: Record<string, string> = {}
  for (const violation of body.errors ?? []) {
    fieldErrors[violation.field] ??= violation.message
  }
  return new ApiError(response.status, body.detail, fieldErrors)
}

interface Problem {
  detail: string
  errors?: { field: string; message: string }[]
}

function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && typeof (body as Problem).detail === 'string'
}
