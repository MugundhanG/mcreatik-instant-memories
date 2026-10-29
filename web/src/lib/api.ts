import type { PreparedPhoto } from '../admin/prepareUpload'
import type {
  AdminPhoto,
  AdminPhotoPage,
  PhotoFacets,
  AdminPhotoQuery,
  EventDetail,
  EventStats,
  EventStatus,
  EventSummary,
  LoginResponse,
  PublicEvent,
  PublicPhotoPage,
  UploaderCredentials,
  Account,
  Studio,
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
  // JSON bodies are strings; FormData (photo uploads) sets its own multipart Content-Type.
  if (typeof init.body === 'string' && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
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
  /** Super admin only: create the event for this studio. */
  ownerId?: string
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
  photos: (id: string, query: AdminPhotoQuery = {}) => {
    const q = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== '') q.set(k, String(v))
    const qs = q.toString()
    return request<AdminPhotoPage>(`/api/admin/events/${id}/photos${qs ? `?${qs}` : ''}`, {}, true)
  },
  photoFacets: (id: string) => request<PhotoFacets>(`/api/admin/events/${id}/photo-facets`, {}, true),
  updatePhoto: (photoId: string, input: { hidden?: boolean; liked?: boolean }) =>
    request<AdminPhoto>(`/api/admin/photos/${photoId}`, { method: 'PATCH', body: json(input) }, true),
  /** Dashboard upload: the browser-made gallery and thumbnail JPEGs plus the original's details. */
  uploadPhoto: (eventId: string, file: File, p: PreparedPhoto) => {
    const form = new FormData()
    form.append('web', p.web, 'web.jpg')
    form.append('thumbnail', p.thumbnail, 'thumb.jpg')
    form.append('fileName', file.name)
    form.append('fileSize', String(file.size))
    form.append('mimeType', file.type)
    form.append('checksumSha256', p.checksumSha256)
    form.append('width', String(p.width))
    form.append('height', String(p.height))
    if (p.capturedAt) form.append('capturedAt', p.capturedAt)
    return request<{ outcome: 'UPLOAD' | 'DUPLICATE'; photoId: string }>(`/api/admin/events/${eventId}/photos`, { method: 'POST', body: form }, true)
  },
  deletePhoto: (photoId: string) => request<void>(`/api/admin/photos/${photoId}`, { method: 'DELETE' }, true),
  originalUrl: (photoId: string) => request<{ url: string }>(`/api/admin/photos/${photoId}/original`, {}, true),
  createUploader: (eventId: string, name: string) =>
    request<UploaderCredentials>(`/api/admin/events/${eventId}/uploaders`, { method: 'POST', body: json({ name }) }, true),
  rotateUploader: (uploaderId: string) =>
    request<UploaderCredentials>(`/api/admin/uploaders/${uploaderId}/rotate-token`, { method: 'POST' }, true),
  updateAccount: (input: { displayName?: string; instagram?: string }) =>
    request<Account>('/api/admin/account', { method: 'PATCH', body: json(input) }, true),
  uploadLogo: (png: Blob) => {
    const form = new FormData()
    form.append('logo', png, 'logo.png')
    return request<Account>('/api/admin/account/logo', { method: 'POST', body: form }, true)
  },
  removeLogo: () => request<Account>('/api/admin/account/logo', { method: 'DELETE' }, true),
  account: () => request<Account>('/api/admin/account', {}, true),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/admin/account/password', { method: 'POST', body: json({ currentPassword, newPassword }) }, true),
  studios: () => request<Studio[]>('/api/admin/studios', {}, true),
  createStudio: (input: { name: string; email: string; password: string }) =>
    request<Studio>('/api/admin/studios', { method: 'POST', body: json(input) }, true),
  updateStudio: (id: string, input: { name?: string; disabled?: boolean; newPassword?: string }) =>
    request<Studio>(`/api/admin/studios/${id}`, { method: 'PATCH', body: json(input) }, true),
  deleteUploader: (uploaderId: string) => request<void>(`/api/admin/uploaders/${uploaderId}`, { method: 'DELETE' }, true),
}
