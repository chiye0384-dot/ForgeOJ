export interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

export interface SessionUser {
  id: number
  username: string
}

export interface SessionResponse {
  authenticated: boolean
  user: SessionUser | null
  csrf: CsrfToken
}

export interface PublicSample {
  input: string
  output: string
}

export interface ProblemResponse {
  slug: string
  title: string
  statement: string
  inputDescription: string
  outputDescription: string
  publicSamples: PublicSample[]
  judgeVersion: number
  resourceLimits: {
    timeLimitMs: number
    memoryLimitMb: number
    outputLimitBytes: number
  }
}

export type ProblemDifficulty = 'EASY' | 'MEDIUM' | 'HARD'

export interface ProblemListQuery {
  keyword?: string
  difficulty?: ProblemDifficulty
  tag?: string
  page: number
  size: number
}

export interface ProblemListItem {
  slug: string
  title: string
  difficulty: ProblemDifficulty | null
  tags: string[]
  judgeVersion: number
}

export interface ProblemListResponse {
  items: ProblemListItem[]
  page: number
  size: number
  total: number
}

export interface SubmissionCreatedResponse {
  submissionId: string
  processingStatus: string
  statusVersion: number
}

export interface SubmissionStatusResponse extends SubmissionCreatedResponse {
  verdict: string | null
  diagnosticMessage: string | null
}

export class ApiRequestError extends Error {
  constructor(readonly status: number) {
    super(`请求失败（HTTP ${status}）`)
  }
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  let response = await fetch(path, {
    credentials: 'same-origin',
    ...init,
  })

  const protectedAuth = [
    'logout-all',
    'password/change',
    'email-binding/request',
    'email-binding/confirm',
  ].some((action) => path === `/api/v1/auth/${action}`)
  if (response.status === 401 && (!path.startsWith('/api/v1/auth/') || protectedAuth)) {
    await refreshSession()
    response = await fetch(path, { credentials: 'same-origin', ...init })
  }

  if (!response.ok) {
    throw new ApiRequestError(response.status)
  }

  return (response.status === 204 ? undefined : await response.json()) as T
}

let refreshing: Promise<SessionResponse> | undefined
async function rawSession(): Promise<SessionResponse> {
  const response = await fetch('/api/v1/auth/session', { credentials: 'same-origin' })
  if (!response.ok) throw new ApiRequestError(response.status)
  return (await response.json()) as SessionResponse
}

export function refreshSession(): Promise<SessionResponse> {
  if (refreshing) return refreshing
  // Rotating a shared refresh cookie without a cross-tab lock can revoke a valid session.
  // Older browsers can still log in; they require a new login when the short JWT expires.
  if (!navigator.locks) return Promise.reject(new ApiRequestError(401))
  const perform = async (): Promise<SessionResponse> => {
    // Another tab may have rotated cookies while this tab waited for the lock.
    const current = await rawSession()
    if (current.authenticated) return current
    return requestJson('/api/v1/auth/refresh', {
      method: 'POST',
      headers: jsonHeaders(current.csrf),
    })
  }
  refreshing = navigator.locks.request('forgeoj-refresh', perform).finally(() => {
    refreshing = undefined
  })
  return refreshing
}

export function jsonHeaders(csrf?: CsrfToken): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (csrf) {
    headers[csrf.headerName] = csrf.token
  }
  return headers
}

export function getSession(): Promise<SessionResponse> {
  return requestJson('/api/v1/auth/session')
}

export async function restoreSession(): Promise<SessionResponse> {
  const current = await getSession()
  if (current.authenticated) return current
  try {
    return await refreshSession()
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) return current
    throw error
  }
}

export function accountAction(path: string, body: unknown, csrf: CsrfToken): Promise<void> {
  return requestJson(`/api/v1/auth/${path}`, {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify(body),
  })
}

export function login(
  username: string,
  password: string,
  csrf: CsrfToken,
): Promise<SessionResponse> {
  return requestJson('/api/v1/auth/login', {
    method: 'POST',
    headers: jsonHeaders(csrf),
    body: JSON.stringify({ username, password }),
  })
}

export function getProblem(slug: string): Promise<ProblemResponse> {
  return requestJson(`/api/v1/problems/${encodeURIComponent(slug)}`)
}

export function getProblems(query: ProblemListQuery): Promise<ProblemListResponse> {
  const parameters = new URLSearchParams({ page: String(query.page), size: String(query.size) })
  if (query.keyword) parameters.set('keyword', query.keyword)
  if (query.difficulty) parameters.set('difficulty', query.difficulty)
  if (query.tag) parameters.set('tag', query.tag)
  return requestJson(`/api/v1/problems?${parameters}`)
}

export function getProblemTags(): Promise<{ tags: string[] }> {
  return requestJson('/api/v1/problem-tags')
}

export function createSubmission(
  slug: string,
  sourceCode: string,
  csrf: CsrfToken,
  idempotencyKey: string,
): Promise<SubmissionCreatedResponse> {
  return requestJson(`/api/v1/problems/${encodeURIComponent(slug)}/submissions`, {
    method: 'POST',
    headers: {
      ...jsonHeaders(csrf),
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify({ language: 'JAVA_21', sourceCode }),
  })
}

export function getSubmission(submissionId: string): Promise<SubmissionStatusResponse> {
  return requestJson(`/api/v1/submissions/${encodeURIComponent(submissionId)}`)
}
