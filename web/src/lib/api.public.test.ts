import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchHqRequests, fetchPublicCategories, publicApi, requestHq, setHqDone } from './api'

describe('categories and HD requests api', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('calls the category, filter and HD endpoints', async () => {
    const fetchMock = vi.fn(async (...args: [RequestInfo | URL, RequestInit?]) => (void args, new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    vi.stubGlobal('fetch', fetchMock)
    await fetchPublicCategories('s1')
    await publicApi.photos('s1', { category: 'c1', limit: 5 })
    await fetchHqRequests('e1')
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/public\/events\/s1\/categories$/)
    expect(String(fetchMock.mock.calls[1][0])).toContain('category=c1')
    expect(String(fetchMock.mock.calls[2][0])).toMatch(/\/api\/admin\/events\/e1\/hq-requests$/)

    fetchMock.mockImplementation(async () => new Response(null, { status: 204 }))
    await requestHq('s1', { photoId: 'p', contact: 'a@b.co', visitorId: 'v' })
    await setHqDone('e1', 'r1', true)
    const [postUrl, post] = fetchMock.mock.calls[3]
    expect(String(postUrl)).toMatch(/\/s1\/hq-requests$/)
    expect(post?.method).toBe('POST')
    expect(JSON.parse(String(post?.body))).toEqual({ photoId: 'p', contact: 'a@b.co', visitorId: 'v' })
    const patch = fetchMock.mock.calls[4][1]
    expect(patch?.method).toBe('PATCH')
    expect(JSON.parse(String(patch?.body))).toEqual({ done: true })
  })

  it('surfaces the server message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 'INVALID_CONTACT', message: 'Enter your email' }), { status: 400 })))
    await expect(requestHq('s1', { photoId: 'p', contact: 'x', visitorId: 'v' })).rejects.toMatchObject({ code: 'INVALID_CONTACT', message: 'Enter your email' })
    await expect(requestHq('s1', { photoId: 'p', contact: 'x', visitorId: 'v' })).rejects.toBeInstanceOf(ApiError)
  })
})
