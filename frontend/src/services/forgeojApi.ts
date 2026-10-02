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

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...init,
  })

  if (!response.ok) {
    throw new ApiRequestError(response.status)
  }

  return (await response.json()) as T
}

function jsonHeaders(csrf?: CsrfToken): Record<string, string> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (csrf) {
    headers[csrf.headerName] = csrf.token
  }
  return headers
}

export function getSession(): Promise<SessionResponse> {
  return requestJson('/api/v1/auth/session')
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
