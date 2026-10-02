import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { monitorSubmission, type NotificationSocket } from '../services/submissionMonitor'
import { ApiRequestError, type SubmissionStatusResponse } from '../services/forgeojApi'

const id = '11111111-1111-4111-8111-111111111111'
const state = (version: number, processingStatus = 'RUNNING'): SubmissionStatusResponse => ({
  submissionId: id,
  processingStatus,
  statusVersion: version,
  verdict: processingStatus === 'FINISHED' ? 'AC' : null,
  diagnosticMessage: null,
})

class FakeSocket implements NotificationSocket {
  onmessage: ((event: { data: unknown }) => void) | null = null
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn<() => void>()

  notice(version: number, processingStatus = 'RUNNING', submissionId = id): void {
    this.onmessage?.({
      data: JSON.stringify({ submissionId, processingStatus, statusVersion: version }),
    })
  }
}

async function settle(): Promise<void> {
  for (let iteration = 0; iteration < 10; iteration += 1) await Promise.resolve()
}

describe('M1 submission notification and polling', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('ignores duplicate/older/foreign notices and fetches the real terminal verdict', async () => {
    const socket = new FakeSocket()
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>()
    read.mockResolvedValueOnce(state(1))
    read.mockResolvedValueOnce(state(3, 'FINISHED'))
    const onStatus = vi.fn<(value: SubmissionStatusResponse) => void>()
    monitorSubmission(state(0, 'QUEUED'), onStatus, vi.fn<(error: unknown) => void>(), {
      read,
      connect: () => socket,
    })
    await settle()
    socket.notice(1)
    socket.notice(0, 'QUEUED')
    socket.notice(9, 'RUNNING', 'other-submission')
    expect(read).toHaveBeenCalledTimes(1)
    socket.notice(3, 'FINISHED')
    socket.notice(2)
    await settle()
    expect(onStatus.mock.calls.map(([value]) => value.statusVersion)).toEqual([1, 3])
    expect(onStatus).toHaveBeenLastCalledWith(state(3, 'FINISHED'))
    expect(socket.close).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not apply an old HTTP response after a higher version notice arrived', async () => {
    const socket = new FakeSocket()
    let release!: (value: SubmissionStatusResponse) => void
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>()
    read.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )
    read.mockResolvedValueOnce(state(4, 'FINISHED'))
    const onStatus = vi.fn<(value: SubmissionStatusResponse) => void>()
    monitorSubmission(state(0, 'QUEUED'), onStatus, vi.fn<(error: unknown) => void>(), {
      read,
      connect: () => socket,
    })
    socket.notice(4, 'FINISHED')
    release(state(1))
    await settle()
    expect(onStatus).toHaveBeenCalledExactlyOnceWith(state(4, 'FINISHED'))
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('uses polling after disconnect or connection failure and recognizes CANCELLED as terminal', async () => {
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>()
    read.mockResolvedValueOnce(state(1))
    read.mockResolvedValueOnce(state(2, 'CANCELLED'))
    const onStatus = vi.fn<(value: SubmissionStatusResponse) => void>()
    monitorSubmission(state(0, 'QUEUED'), onStatus, vi.fn<(error: unknown) => void>(), {
      read,
      connect: () => {
        throw new Error('WebSocket unavailable')
      },
    })
    await settle()
    await vi.advanceTimersByTimeAsync(1000)
    expect(onStatus).toHaveBeenLastCalledWith(state(2, 'CANCELLED'))
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps polling after a transient error but stops on authorization loss', async () => {
    const socket = new FakeSocket()
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>()
    read.mockRejectedValueOnce(new Error('Temporary failure'))
    read.mockResolvedValueOnce(state(2))
    read.mockRejectedValueOnce(new ApiRequestError(401))
    const onError = vi.fn<(error: unknown) => void>()
    monitorSubmission(
      state(0, 'QUEUED'),
      vi.fn<(value: SubmissionStatusResponse) => void>(),
      onError,
      { read, connect: () => socket },
    )
    await settle()
    await vi.advanceTimersByTimeAsync(1000)
    expect(read).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1000)
    expect(onError).toHaveBeenCalledTimes(2)
    expect(socket.close).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores malformed or extra-field messages and never renders pushed diagnostics', async () => {
    const socket = new FakeSocket()
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>().mockResolvedValue(state(1))
    const stop = monitorSubmission(
      state(0, 'QUEUED'),
      vi.fn<(value: SubmissionStatusResponse) => void>(),
      vi.fn<(error: unknown) => void>(),
      {
        read,
        connect: () => socket,
      },
    )
    await settle()
    for (const data of [
      'invalid-json',
      JSON.stringify({
        submissionId: id,
        processingStatus: 'FINISHED',
        statusVersion: 2,
        diagnosticMessage: 'hidden-sentinel',
      }),
      JSON.stringify({ submissionId: id, processingStatus: 'RUNNING', statusVersion: -1 }),
    ]) {
      socket.onmessage?.({ data })
    }
    expect(read).toHaveBeenCalledOnce()
    stop()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('stopping a replaced/unmounted monitor discards late HTTP and WebSocket callbacks', async () => {
    const socket = new FakeSocket()
    let release!: (value: SubmissionStatusResponse) => void
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>(
      () =>
        new Promise<SubmissionStatusResponse>((resolve) => {
          release = resolve
        }),
    )
    const onStatus = vi.fn<(value: SubmissionStatusResponse) => void>()
    const stop = monitorSubmission(
      state(0, 'QUEUED'),
      onStatus,
      vi.fn<(error: unknown) => void>(),
      {
        read,
        connect: () => socket,
      },
    )
    stop()
    socket.notice(9)
    release(state(2, 'FINISHED'))
    await settle()
    expect(onStatus).not.toHaveBeenCalled()
    expect(read).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('bounds reconnection attempts while polling remains available', async () => {
    const sockets: FakeSocket[] = []
    const connect = vi.fn<() => FakeSocket>(() => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    })
    const read = vi.fn<() => Promise<SubmissionStatusResponse>>().mockResolvedValue(state(1))
    const stop = monitorSubmission(
      state(0, 'QUEUED'),
      vi.fn<(value: SubmissionStatusResponse) => void>(),
      vi.fn<(error: unknown) => void>(),
      { read, connect },
    )
    await settle()
    for (const delay of [2000, 4000, 8000]) {
      sockets[sockets.length - 1]?.onerror?.()
      sockets[sockets.length - 1]?.onclose?.() // Error + close must not double schedule.
      await vi.advanceTimersByTimeAsync(delay)
    }
    expect(connect).toHaveBeenCalledTimes(4)
    sockets[sockets.length - 1]?.onclose?.()
    await vi.advanceTimersByTimeAsync(30000)
    expect(connect).toHaveBeenCalledTimes(4)
    expect(read.mock.calls.length).toBeGreaterThan(30)
    stop()
    expect(vi.getTimerCount()).toBe(0)
  })
})
