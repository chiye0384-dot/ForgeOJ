import { ApiRequestError, getSubmission, type SubmissionStatusResponse } from './forgeojApi'

export interface NotificationSocket {
  onmessage: ((event: { data: unknown }) => void) | null
  onopen: (() => void) | null
  onclose: (() => void) | null
  onerror: (() => void) | null
  close(): void
}

interface MonitorDependencies {
  read(id: string): Promise<SubmissionStatusResponse>
  connect(id: string): NotificationSocket
}

const statuses = new Set(['QUEUED', 'RUNNING', 'RETRYING', 'FINISHED', 'CANCELLED', 'SYSTEM_ERROR'])
const terminal = (status: string): boolean =>
  status === 'FINISHED' || status === 'CANCELLED' || status === 'SYSTEM_ERROR'

function connectSocket(id: string): NotificationSocket {
  const url = new URL(`/api/v1/submissions/${encodeURIComponent(id)}/events`, window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return new WebSocket(url) as unknown as NotificationSocket
}

// Push is only an invalidation hint. All rendered verdicts come from the owner-only GET.
export function monitorSubmission(
  initial: SubmissionStatusResponse,
  onStatus: (value: SubmissionStatusResponse) => void,
  onError: (error: unknown) => void,
  dependencies: MonitorDependencies = { read: getSubmission, connect: connectSocket },
): () => void {
  let active = true
  let highestVersion = initial.statusVersion
  let appliedVersion = initial.statusVersion
  let reading = false
  let refreshPending = false
  let reconnectAttempts = 0
  let socket: NotificationSocket | undefined
  let pollTimer: ReturnType<typeof setTimeout> | undefined
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined

  function stop(): void {
    active = false
    clearTimeout(pollTimer)
    clearTimeout(reconnectTimer)
    socket?.close()
    socket = undefined
  }

  function schedulePoll(): void {
    clearTimeout(pollTimer)
    if (active)
      pollTimer = setTimeout(() => {
        void refresh()
      }, 1000)
  }

  async function refresh(): Promise<void> {
    if (!active) return
    if (reading) {
      refreshPending = true
      return
    }
    reading = true
    clearTimeout(pollTimer)
    try {
      const latest = await dependencies.read(initial.submissionId)
      if (
        active &&
        latest.submissionId === initial.submissionId &&
        Number.isSafeInteger(latest.statusVersion) &&
        statuses.has(latest.processingStatus) &&
        latest.statusVersion >= highestVersion &&
        latest.statusVersion >= appliedVersion
      ) {
        appliedVersion = latest.statusVersion
        highestVersion = latest.statusVersion
        onStatus(latest)
        if (terminal(latest.processingStatus)) stop()
      }
    } catch (error) {
      if (active) {
        onError(error)
        if (error instanceof ApiRequestError && [401, 403, 404].includes(error.status)) stop()
      }
    } finally {
      reading = false
      if (active && refreshPending) {
        refreshPending = false
        void refresh()
      } else {
        schedulePoll()
      }
    }
  }

  function reconnect(): void {
    if (!active || reconnectTimer !== undefined || reconnectAttempts >= 3) return
    reconnectAttempts += 1
    reconnectTimer = setTimeout(
      () => {
        reconnectTimer = undefined
        connect()
      },
      1000 * 2 ** reconnectAttempts,
    )
  }

  function connect(): void {
    if (!active || terminal(initial.processingStatus)) return
    try {
      const opened = dependencies.connect(initial.submissionId)
      socket = opened
      opened.onopen = () => {
        if (active && socket === opened) void refresh()
      }
      opened.onmessage = ({ data }) => {
        if (!active || socket !== opened || typeof data !== 'string') return
        try {
          const notice: unknown = JSON.parse(data)
          if (typeof notice !== 'object' || notice === null || Array.isArray(notice)) return
          const fields = notice as Record<string, unknown>
          if (
            Object.keys(fields).sort().join(',') !==
              'processingStatus,statusVersion,submissionId' ||
            fields.submissionId !== initial.submissionId ||
            typeof fields.processingStatus !== 'string' ||
            !statuses.has(fields.processingStatus) ||
            typeof fields.statusVersion !== 'number' ||
            !Number.isSafeInteger(fields.statusVersion) ||
            fields.statusVersion <= highestVersion
          )
            return
          highestVersion = fields.statusVersion
          void refresh()
        } catch {
          // Untrusted notification payloads never enter the rendered result.
        }
      }
      opened.onclose = () => {
        if (active && socket === opened) {
          socket = undefined
          reconnect()
        }
      }
      opened.onerror = () => {
        if (active && socket === opened) {
          socket = undefined
          opened.close()
          reconnect()
        }
      }
    } catch {
      reconnect()
    }
  }

  connect()
  void refresh()
  return stop
}
