import { vi } from 'vitest'
import type { PublicEvent, PublicPhoto } from '../lib/types'

export class MockEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  static instances: MockEventSource[] = []

  readonly url: string
  readyState = MockEventSource.CONNECTING
  onerror: (() => void) | null = null
  private listeners: Record<string, ((e: MessageEvent) => void)[]> = {}

  constructor(url: string) {
    this.url = url
    MockEventSource.instances.push(this)
  }

  addEventListener(type: string, fn: (e: MessageEvent) => void) {
    ;(this.listeners[type] ??= []).push(fn)
  }

  emit(type: string, data: unknown) {
    this.readyState = MockEventSource.OPEN
    for (const fn of this.listeners[type] ?? []) fn(new MessageEvent(type, { data: JSON.stringify(data) }))
  }

  fail() {
    this.onerror?.()
  }

  close() {
    this.readyState = MockEventSource.CLOSED
  }

  static latest() {
    return MockEventSource.instances[MockEventSource.instances.length - 1]
  }
}

export function photo(n: number, readyAt = `2026-10-10T18:${String(n).padStart(2, '0')}:00Z`): PublicPhoto {
  return {
    id: `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`,
    thumbnailUrl: `https://media.test/thumb/${n}.jpg`,
    webUrl: `https://media.test/web/${n}.jpg`,
    width: 2048,
    height: 1365,
    readyAt,
    cursor: `cursor-${n}`,
  }
}

export const liveEvent: PublicEvent = {
  name: 'Arun & Priya Wedding',
  slug: 'arun-priya-7k3d',
  eventDate: '2026-10-10',
  status: 'LIVE',
  live: true,
  coverUrl: null,
  photoCount: 3,
}

type Handler = (url: URL) => unknown | Response

/** Routes fetch() calls by path; returns JSON. */
export function mockFetch(routes: Record<string, Handler>) {
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
    const handler = routes[url.pathname]
    if (!handler) return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'Not found' }), { status: 404 })
    const result = handler(url)
    if (result instanceof Response) return result
    return new Response(JSON.stringify(result), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}
