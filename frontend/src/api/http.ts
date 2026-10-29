/** A PIN pad locked by too many wrong PINs: seconds until it unlocks, and whether a manager can unlock it sooner. */
export interface Lockout {
  seconds: number
}

/** Error carrying the RFC 9457 problem detail returned by the API, when there is one. */
export class ApiError extends Error {
  readonly status: number
  /** A 429 from a PIN pad. */
  readonly lockout?: Lockout

  constructor(status: number, message: string, lockout?: Lockout) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.lockout = lockout
  }
}

interface ProblemDetail {
  title?: string
  detail?: string
  retryAfterSeconds?: number
}

// The bearer token for the current session. Kept here (set by AuthProvider) so every request
// picks it up without threading it through components.
let authToken: string | null = null
let onUnauthorized: (() => void) | null = null

export function setAuthToken(token: string | null) {
  authToken = token
}

/** Called when an authenticated request is rejected with 401 (expired or revoked session). */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

/**
 * `token` overrides the session token for one request, e.g. a manager's short-lived
 * special-access token approving an action at a cashier's terminal.
 */
async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const sentToken = token ?? authToken
  if (sentToken) headers.set('Authorization', `Bearer ${sentToken}`)

  const response = await fetch(path, { ...init, headers })
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail
    // Only a rejected *session* ends the session; a stale override token does not.
    if (response.status === 401 && sentToken && sentToken === authToken) onUnauthorized?.()
    const lockout: Lockout | undefined = response.status === 429 && typeof problem.retryAfterSeconds === 'number'
      ? { seconds: problem.retryAfterSeconds }
      : undefined
    throw new ApiError(response.status, problem.detail ?? problem.title ?? `Request failed (${response.status})`, lockout)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  return request<T>(path, { signal })
}

export function postJson<T>(path: string, body: unknown, options: { token?: string } = {}): Promise<T> {
  return sendJson<T>('POST', path, body, options.token)
}

export function putJson<T>(path: string, body: unknown): Promise<T> {
  return sendJson<T>('PUT', path, body)
}

/** A DELETE whose response says what happened (e.g. deleted, or only deactivated because it has history). */
export function deleteJson<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

export function deleteRequest(path: string): Promise<void> {
  return request<void>(path, { method: 'DELETE' })
}

function sendJson<T>(method: string, path: string, body: unknown, token?: string): Promise<T> {
  return request<T>(
    path,
    {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
    token,
  )
}

/** The lock-out behind a failed PIN, if the PIN pad is locked (too many wrong PINs in a row). */
export function pinLockout(error: unknown): Lockout | undefined {
  return error instanceof ApiError ? error.lockout : undefined
}
