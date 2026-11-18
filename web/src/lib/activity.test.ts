import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushActivity, track, trackVisit } from './activity'

describe('Guest activity tracking', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('batches items per gallery and counts one visit per tab', () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    trackVisit('arun-priya', 'qr')
    trackVisit('arun-priya', 'qr') // reload: not a second visit
    track('arun-priya', { type: 'PHOTO_VIEW', photoId: 'p1' })
    track('arun-priya', { type: 'DOWNLOAD', photoId: 'p1' })
    flushActivity()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/api\/public\/events\/arun-priya\/activity$/)
    expect(init.keepalive).toBe(true)
    const body = JSON.parse(String(init.body))
    expect(body.visitorId).toMatch(/^[A-Za-z0-9-]{8,40}$/)
    expect(body.items).toEqual([
      { type: 'VISIT', source: 'qr' },
      { type: 'PHOTO_VIEW', photoId: 'p1' },
      { type: 'DOWNLOAD', photoId: 'p1' },
    ])
  })

  it('keeps a batch that failed on the network and retries it later without losing or doubling it', async () => {
    vi.useFakeTimers()
    try {
      const fetchMock = vi.fn()
        .mockRejectedValueOnce(new TypeError('offline'))
        .mockResolvedValue(new Response(null, { status: 204 }))
      vi.stubGlobal('fetch', fetchMock)
      track('retry-gallery', { type: 'PHOTO_VIEW', photoId: 'p9' })
      flushActivity()
      await vi.advanceTimersByTimeAsync(0)
      expect(fetchMock).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(60_000) // backoff elapsed: sent again
      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(JSON.parse(String((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body)).items).toEqual([{ type: 'PHOTO_VIEW', photoId: 'p9' }])
      await vi.advanceTimersByTimeAsync(120_000)
      expect(fetchMock).toHaveBeenCalledTimes(2) // delivered once, not again
    } finally {
      vi.useRealTimers()
    }
  })

  it('hands the queue to sendBeacon when the page is hidden, and does not send it twice', () => {
    const beacon = vi.fn(() => true)
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('navigator', { ...navigator, sendBeacon: beacon })
    track('beacon-gallery', { type: 'DOWNLOAD', photoId: 'p1' })
    flushActivity(true)
    flushActivity(true) // pagehide right after visibilitychange: nothing left
    expect(beacon).toHaveBeenCalledTimes(1)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
