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
})
