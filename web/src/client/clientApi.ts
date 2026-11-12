import { useSyncExternalStore } from 'react'
import { API_BASE_URL, ApiError } from '../lib/api'
import type { Brand, Category, PreparedZip } from '../lib/types'

/** The couple's portal API (/api/client), with its own login, separate from the studio dashboard. */
const KEY = 'mcreatik.client.session'

export interface ClientSession {
  token: string
  expiresAt: string
  name: string
  mustChangePassword: boolean
}

export interface ClientMe {
  name: string
  email: string
  mustChangePassword: boolean
  event: { name: string; eventDate: string; coverUrl: string | null; brand?: Brand }
  albumLimit: number | null
  selectedCount: number
  totalPhotos: number
  uncategorizedPhotos: number
  categories: Category[]
}

export interface ClientPhoto {
  id: string
  thumbnailUrl: string
  webUrl: string
  width: number | null
  height: number | null
  originalAvailable: boolean
  selected: boolean
  categoryId: string | null
}

const listeners = new Set<() => void>()
let current: ClientSession | null = read()

/** One of the events a client's email and password open (sent with 409 CHOOSE_EVENT). */
export interface EventChoice {
  slug: string
  name: string
  eventDate: string
}

/** The email has a login on several events: the client picks one and logs in again with its address. */
export class ChooseEventError extends ApiError {
  readonly events: EventChoice[]

  constructor(message: string, events: EventChoice[]) {
    super(409, 'CHOOSE_EVENT', message)
    this.events = events
  }
}

function read(): ClientSession | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as ClientSession
    return Date.parse(s.expiresAt) > Date.now() ? s : null
  } catch {
    return null
  }
}

function set(s: ClientSession | null) {
  current = s
  try {
    if (s) localStorage.setItem(KEY, JSON.stringify(s))
    else localStorage.removeItem(KEY)
  } catch {
    // storage unavailable: the session lives in memory only
  }
  listeners.forEach((l) => l())
}

export const clientSession = {
  get: () => current,
  set,
  update: (patch: Partial<ClientSession>) => current && set({ ...current, ...patch }),
  signOut: () => set(null),
  subscribe: (l: () => void) => {
    listeners.add(l)
    return () => {
      listeners.delete(l)
    }
  },
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
  if (current) headers.set('Authorization', `Bearer ${current.token}`)
  let res: Response
  try {
    res = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check your internet connection.')
  }
  if (res.status === 401 && current) clientSession.signOut()
  if (!res.ok) {
    let code = `HTTP_${res.status}`
    let message = 'Something went wrong'
    let events: EventChoice[] | null = null
    try {
      const body = await res.json()
      code = body.code ?? code
      message = body.message ?? message
      if (code === 'CHOOSE_EVENT' && Array.isArray(body.events)) events = body.events
    } catch {
      // not JSON
    }
    if (events) throw new ChooseEventError(message, events)
    throw new ApiError(res.status, code, message)
  }
  return (res.status === 204 ? undefined : await res.json()) as T
}

const json = (b: unknown) => JSON.stringify(b)

export const clientApi = {
  /** {@code eventSlug}: the event of the client's link; without it an email on several events gets a choice. */
  login: (email: string, password: string, eventSlug?: string) =>
    request<ClientSession>('/api/client/auth/login', { method: 'POST', body: json({ email, password, ...(eventSlug ? { eventSlug } : {}) }) }),
  forgotPassword: (email: string) =>
    request<void>('/api/client/auth/forgot-password', { method: 'POST', body: json({ email }) }),
  me: () => request<ClientMe>('/api/client/me'),
  photos: (q: { category?: string; selected?: boolean; offset: number; limit: number }) => {
    const p = new URLSearchParams({ offset: String(q.offset), limit: String(q.limit) })
    if (q.category) p.set('category', q.category)
    if (q.selected) p.set('selected', 'true')
    return request<{ photos: ClientPhoto[]; nextOffset: number | null }>(`/api/client/photos?${p}`)
  },
  select: (photoId: string, selected: boolean) =>
    request<{ selectedCount: number; albumLimit: number | null }>(`/api/client/photos/${photoId}/selected`, { method: 'PUT', body: json({ selected }) }),
  /** album: the selection; photoIds: these photos; category: an id, 'none' or 'all'. */
  prepareZip: (input: { album?: boolean; photoIds?: string[]; category?: string }) =>
    request<PreparedZip>('/api/client/downloads', { method: 'POST', body: json(input) }),
  download: (photoId: string) => request<{ url: string; original: boolean }>(`/api/client/photos/${photoId}/download`),
  /** Returns a fresh login for this device: the change logs out every other device. */
  changePassword: (currentPassword: string, newPassword: string) =>
    request<{ token: string; expiresAt: string } | undefined>('/api/client/password', { method: 'POST', body: json({ currentPassword, newPassword }) }),
}

export function useClientSession() {
  return useSyncExternalStore(clientSession.subscribe, clientSession.get)
}
