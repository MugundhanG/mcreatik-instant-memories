import type {
  AdminPhotoPage,
  EventDetail,
  EventStats,
  EventStatus,
  EventSummary,
  LoginResponse,
  PublicEvent,
  PublicPhotoPage,
  UploaderCredentials,
} from './types'

export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type TokenProvider = () => string | null
let tokenProvider: TokenProvider = () => null
let onUnauthorized: () => void = () => {}

export function configureAuth(provider: TokenProvider, unauthorized: () => void) {
  tokenProvider = provider
  onUnauthorized = unauthorized
}

async function request<T>(path: string, init: RequestInit = {}, auth = false): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (auth) {
    const token = tokenProvider()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  if (response.status === 401 && auth) onUnauthorized()
  if (!response.ok) {
    let code = 'HTTP_' + response.status
    let message = response.statusText || 'Request failed'
    try {
      const body = await response.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      // non-JSON error body
    }
    throw new ApiError(response.status, code, message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

const json = (body: unknown) => JSON.stringify(body)

// ---- Guest API ----

export const publicApi = {
  event: (slug: string) => request<PublicEvent>(`/api/public/events/${encodeURIComponent(slug)}`),
  photos: (slug: string, opts: { before?: string; after?: string; limit?: number } = {}) => {
    const q = new URLSearchParams()
    if (opts.before) q.set('before', opts.before)
    if (opts.after) q.set('after', opts.after)
    if (opts.limit) q.set('limit', String(opts.limit))
    const qs = q.toString()
    return request<PublicPhotoPage>(`/api/public/events/${encodeURIComponent(slug)}/photos${qs ? `?${qs}` : ''}`)
  },
  streamUrl: (slug: string) => `${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/stream`,
}

// ---- Admin API ----

export interface CreateEventInput {
  name: string
  eventDate: string
  status?: EventStatus
  slug?: string
}

export interface UpdateEventInput {
  name?: string
  slug?: string
  eventDate?: string
  status?: EventStatus
  coverPhotoId?: string
  retentionUntil?: string
  clearRetention?: boolean
}

export const adminApi = {
  login: (email: string, password: string) =>
    request<LoginResponse>('/api/auth/login', { method: 'POST', body: json({ email, password }) }),
  events: () => request<EventSummary[]>('/api/admin/events', {}, true),
  createEvent: (input: CreateEventInput) =>
    request<EventDetail>('/api/admin/events', { method: 'POST', body: json(input) }, true),
  event: (id: string) => request<EventDetail>(`/api/admin/events/${id}`, {}, true),
  updateEvent: (id: string, input: UpdateEventInput) =>
    request<EventDetail>(`/api/admin/events/${id}`, { method: 'PATCH', body: json(input) }, true),
  deleteEvent: (id: string) => request<void>(`/api/admin/events/${id}`, { method: 'DELETE' }, true),
  stats: (id: string) => request<EventStats>(`/api/admin/events/${id}/stats`, {}, true),
  photos: (id: string, before?: string) =>
    request<AdminPhotoPage>(`/api/admin/events/${id}/photos${before ? `?before=${encodeURIComponent(before)}` : ''}`, {}, true),
  deletePhoto: (photoId: string) => request<void>(`/api/admin/photos/${photoId}`, { method: 'DELETE' }, true),
  originalUrl: (photoId: string) => request<{ url: string }>(`/api/admin/photos/${photoId}/original`, {}, true),
  createUploader: (eventId: string, name: string) =>
    request<UploaderCredentials>(`/api/admin/events/${eventId}/uploaders`, { method: 'POST', body: json({ name }) }, true),
  rotateUploader: (uploaderId: string) =>
    request<UploaderCredentials>(`/api/admin/uploaders/${uploaderId}/rotate-token`, { method: 'POST' }, true),
  deleteUploader: (uploaderId: string) => request<void>(`/api/admin/uploaders/${uploaderId}`, { method: 'DELETE' }, true),
}
