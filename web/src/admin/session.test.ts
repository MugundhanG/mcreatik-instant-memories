import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adminApi, ApiError } from '../lib/api'
import type { LoginResponse } from '../lib/types'
import { auth, refreshSessionIfNeeded, sessionNeedsRefresh } from './auth'

const HOUR = 3_600_000
const login = (token: string, hours = 12): LoginResponse => ({
  token, expiresAt: new Date(Date.now() + hours * HOUR).toISOString(), email: 'a@studio.test', displayName: 'Studio', role: 'STUDIO', mustChangePassword: false,
})
const reply = (status: number, body: unknown = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('A long session is never logged out by working', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['Date'] }))
  afterEach(() => {
    auth.signOut()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('refreshes once half of the token life has passed, not before', () => {
    auth.signIn(login('t1'))
    const s = auth.session()!
    expect(sessionNeedsRefresh(s)).toBe(false)
    expect(sessionNeedsRefresh(s, Date.now() + 5 * HOUR)).toBe(false)
    expect(sessionNeedsRefresh(s, Date.now() + 6 * HOUR)).toBe(true)
  })

  it('swaps in a fresh token before an old one runs out, ahead of the next request', async () => {
    auth.signIn(login('old'))
    vi.setSystemTime(Date.now() + 7 * HOUR) // 5 hours left of 12
    const seen: (string | null)[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Headers(init?.headers).get('Authorization'))
      if (String(url).endsWith('/account/refresh')) return reply(200, login('new'))
      return reply(200, { email: 'a@studio.test' })
    }))
    await adminApi.account()
    expect(seen).toEqual(['Bearer old', 'Bearer new'])
    expect(auth.session()?.token).toBe('new')
    expect(Date.parse(auth.session()!.expiresAt)).toBeGreaterThan(Date.now() + 11 * HOUR)
  })

  it('keeps the session when a refresh fails for a passing reason', async () => {
    auth.signIn(login('old'))
    vi.setSystemTime(Date.now() + 7 * HOUR)
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('offline')
    }))
    await refreshSessionIfNeeded()
    expect(auth.session()?.token).toBe('old')
  })

  it('a failed upload (too large, server error) never logs out; only a 401 does', async () => {
    auth.signIn(login('tok'))
    vi.stubGlobal('fetch', vi.fn(async () => reply(413)))
    await expect(adminApi.account()).rejects.toBeInstanceOf(ApiError)
    vi.stubGlobal('fetch', vi.fn(async () => reply(502)))
    await expect(adminApi.account()).rejects.toBeInstanceOf(ApiError)
    expect(auth.session()?.token).toBe('tok')
    vi.stubGlobal('fetch', vi.fn(async () => reply(401)))
    await expect(adminApi.account()).rejects.toBeInstanceOf(ApiError)
    expect(auth.session()).toBeNull()
  })

  it('a late 401 for a token that was already replaced does not end the new session', async () => {
    auth.signIn(login('old'))
    let release: () => void = () => {}
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => {
      release = () => resolve(reply(401))
    }))
    vi.stubGlobal('fetch', fetchMock)
    const pending = adminApi.account().catch(() => {})
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
    auth.signIn(login('new')) // refreshed (or signed in again) while the request was in flight
    release()
    await pending
    expect(auth.session()?.token).toBe('new')
  })
})
