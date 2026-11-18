import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useZipDownload } from './zipDownload'

vi.mock('../lib/api', async (orig) => ({ ...(await orig<typeof import('../lib/api')>()), openZip: vi.fn() }))

const prepared = { path: '/z', progressPath: '/z/progress', photos: 3, fileName: 'a.zip' }

describe('ZIP download polling', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('stops with a retryable error when the download is gone (404)', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 404 }))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useZipDownload())
    await act(async () => {
      await result.current.start(async () => prepared)
    })
    expect(result.current.busy).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(result.current.busy).toBe(false)
    expect(result.current.status?.state).toBe('FAILED')
    expect(result.current.status?.error).toMatch(/no longer available/)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(fetchMock).toHaveBeenCalledTimes(1) // no more polling
  })

  it('gives up after ten minutes of waiting', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ state: 'RUNNING', done: 1, total: 3, percent: 33 })))
    const { result } = renderHook(() => useZipDownload())
    await act(async () => {
      await result.current.start(async () => prepared)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 3000)
    })
    expect(result.current.busy).toBe(false)
    expect(result.current.status?.error).toMatch(/too long/)
  })
})
