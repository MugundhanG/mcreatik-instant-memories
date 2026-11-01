import { useSyncExternalStore } from 'react'
import { API_BASE_URL, ApiError } from '../lib/api'
import type { Brand, Category } from '../lib/types'

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
    try {
      const body = await res.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      // not JSON
    }
    throw new ApiError(res.status, code, message)
  }
  return (res.status === 204 ? undefined : await res.json()) as T
}

const json = (b: unknown) => JSON.stringify(b)

export const clientApi = {
  login: (email: string, password: string) =>
    request<ClientSession>('/api/client/auth/login', { method: 'POST', body: json({ email, password }) }),
  me: () => request<ClientMe>('/api/client/me'),
  photos: (q: { category?: string; selected?: boolean; offset: number; limit: number }) => {
    const p = new URLSearchParams({ offset: String(q.offset), limit: String(q.limit) })
    if (q.category) p.set('category', q.category)
    if (q.selected) p.set('selected', 'true')
    return request<{ photos: ClientPhoto[]; nextOffset: number | null }>(`/api/client/photos?${p}`)
  },
  select: (photoId: string, selected: boolean) =>
    request<{ selectedCount: number; albumLimit: number | null }>(`/api/client/photos/${photoId}/selected`, { method: 'PUT', body: json({ selected }) }),
  download: (photoId: string) => request<{ url: string; original: boolean }>(`/api/client/photos/${photoId}/download`),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/client/password', { method: 'POST', body: json({ currentPassword, newPassword }) }),
}

export function useClientSession() {
  return useSyncExternalStore(clientSession.subscribe, clientSession.get)
}
